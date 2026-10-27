package com.karaok.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Renders MIDI notes with a SoundFont straight to two WAV files:
 * one with the guide melody and one without (so the guide can be switched instantly).
 *
 * Speed tricks for TV boxes: each distinct note sound is rendered once and reused
 * (drums and chords repeat a lot), notes stop once they fade below hearing, and the
 * work is spread over all CPU cores. Rendering goes in 20-second chunks to keep memory small.
 */
class Synth(private val sf: SoundFont, val sampleRate: Int = 44100) {

    class Result(val withMelody: File, val withoutMelody: File, val seconds: Double)

    /** A rendered sample voice: mono, already scaled by its own gain, plus its pan. */
    private class Voice(val data: FloatArray, val pan: Float)

    private val cache = ConcurrentHashMap<Long, List<Voice>>()
    private val cacheFloats = AtomicLong(0)
    var cacheLimitFloats: Long = min(Runtime.getRuntime().maxMemory() / 4 / 4, 24L * 1024 * 1024)

    private fun tc(t: Int): Double = if (t <= -12000) 0.0 else 2.0.pow(t / 1200.0)

    /** Render one zone of a note to mono samples (or null if it makes no sound). */
    private fun renderVoice(g: IntArray, keyIn: Int, velIn: Int, durIn: Double, bend: Float): Voice? {
        val sid = g[SoundFont.SAMPLE]
        if (sid !in sf.headers.indices) return null
        val h = sf.headers[sid]
        val total = sf.sampleCount
        val start = max(0, h.start + g[SoundFont.START] + 32768 * g[SoundFont.START_COARSE])
        val end = min(total - 1, h.end + g[SoundFont.END] + 32768 * g[SoundFont.END_COARSE])
        val sl = h.loopStart + g[SoundFont.STARTLOOP] + 32768 * g[SoundFont.STARTLOOP_COARSE]
        val el = h.loopEnd + g[SoundFont.ENDLOOP] + 32768 * g[SoundFont.ENDLOOP_COARSE]
        if (end - start < 4) return null
        val key = if (g[SoundFont.KEYNUM] >= 0) g[SoundFont.KEYNUM] else keyIn
        val vel = if (g[SoundFont.VELOCITY] >= 0) g[SoundFont.VELOCITY] else velIn
        var root = g[SoundFont.ROOT]
        if (root < 0) root = if (h.pitch <= 127) h.pitch else 60
        val cents = (key - root) * g[SoundFont.SCALE] + g[SoundFont.COARSE] * 100 + g[SoundFont.FINE] + h.corr + bend * 100
        val step = 2.0.pow(cents / 1200.0) * h.rate / sampleRate

        val delay = tc(g[SoundFont.DELAY])
        val attack = tc(g[SoundFont.ATTACK])
        val hold = tc(g[SoundFont.HOLD])
        val decay = max(tc(g[SoundFont.DECAY]), 0.001)
        val susCb = g[SoundFont.SUSTAIN].coerceIn(0, 1440).toDouble()
        var release = tc(g[SoundFont.RELEASE]).coerceIn(0.006, 3.0)
        val modes = g[SoundFont.MODES] and 3
        val looped = (modes == 1 || modes == 3) && start <= sl && sl < el && el <= end && el - sl >= 4
        var dur = durIn
        if (!looped) {
            val natural = (end - start) / step / sampleRate
            if (natural < dur) { dur = natural; release = min(release, 0.05) }
        }
        val n = min(((dur + release) * sampleRate).toLong() + 1, 30L * sampleRate).toInt()
        if (n <= 1) return null

        val sr = sampleRate.toDouble()
        val a0 = (delay * sr).toInt()
        val a1 = ((delay + attack) * sr).toInt()
        val h1 = ((delay + attack + hold) * sr).toInt()
        val relStart = (dur * sr).toInt()
        val decayStep = 10.0.pow(-(1000.0 / (decay * sr)) / 200.0)
        val susAmp = 10.0.pow(-susCb / 200.0)
        val relStep = 10.0.pow(-(1000.0 / (release * sr)) / 200.0)

        val fcCents = g[SoundFont.FILTER_FC]
        var useFilter = false
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var fa1 = 0.0; var fa2 = 0.0
        if (fcCents < 13000) {
            val fc = max(40.0, 8.176 * 2.0.pow(fcCents / 1200.0))
            if (fc < sr * 0.45) {
                useFilter = true
                val w = 2 * PI * fc / sr
                val alpha = sin(w) / (2 * sqrt(0.5))
                val cw = cos(w)
                val a0f = 1 + alpha
                b0 = (1 - cw) / 2 / a0f; b1 = (1 - cw) / a0f; b2 = b0
                fa1 = -2 * cw / a0f; fa2 = (1 - alpha) / a0f
            }
        }
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val atten = max(0, g[SoundFont.ATTEN]) * 0.4
        val vgain = (10.0.pow(-atten / 200.0) * (vel / 127.0).pow(2.0) / 32768.0)

        val out = FloatArray(n)
        val smp = sf.samples
        var pos = start.toDouble()
        val span = (el - sl).toDouble()
        var env = 1.0
        var amp: Double
        var len = n
        val silent = 1e-4
        for (i in 0 until n) {
            if (looped) {
                if (pos >= el) pos = sl + ((pos - sl) % span)
            } else if (pos >= end - 1) { len = i; break }
            val ip = pos.toInt()
            val frac = pos - ip
            val s0 = smp.get(ip).toDouble()
            val s = s0 + (smp.get(ip + 1) - s0) * frac
            pos += step

            if (i < relStart) {
                if (i >= h1 && env > susAmp) { env *= decayStep; if (env < susAmp) env = susAmp }
                amp = when {
                    i < a0 -> 0.0
                    i < a1 -> env * (i - a0).toDouble() / max(1, a1 - a0)
                    else -> env
                }
                if (i == relStart - 1) env = amp
                if (i > a1 && env <= silent && susAmp <= silent) { len = i; break }   // decayed away
            } else {
                env *= relStep
                amp = env
                if (env < silent) { len = i; break }                                 // released
            }
            var y = s * amp
            if (useFilter) {
                val yy = b0 * y + b1 * x1 + b2 * x2 - fa1 * y1 - fa2 * y2
                x2 = x1; x1 = y; y2 = y1; y1 = yy
                y = yy
            }
            out[i] = (y * vgain).toFloat()
        }
        if (len < 2) return null
        val data = if (len < n) out.copyOf(len) else out
        return Voice(data, (g[SoundFont.PAN] / 1000f).coerceIn(-0.5f, 0.5f))
    }

    private fun voicesFor(nt: Note, key: Int, dur: Double): List<Voice> {
        val durQ = (dur * 50).toLong().coerceAtMost(4095)          // 20 ms steps
        val bendQ = ((nt.bend * 50).toLong() + 2048).coerceIn(0, 4095)
        val ck = (nt.bank.toLong() shl 52) or (nt.program.toLong() shl 44) or (key.toLong() shl 36) or
            ((nt.velocity / 4).toLong() shl 30) or (durQ shl 12) or bendQ
        cache[ck]?.let { return it }
        val list = sf.voices(nt.bank, nt.program, key, nt.velocity).mapNotNull { renderVoice(it, key, nt.velocity, durQ / 50.0 + 0.01, nt.bend) }
        val size = list.sumOf { it.data.size.toLong() }
        if (cacheFloats.get() + size > cacheLimitFloats) { cache.clear(); cacheFloats.set(0) }
        cache[ck] = list
        cacheFloats.addAndGet(size)
        return list
    }

    private fun accumulate(v: Voice, s0: Long, winStart: Long, winEnd: Long, out: FloatArray, gain: Float, notePan: Float) {
        val p = (v.pan + notePan).coerceIn(-0.5f, 0.5f)
        val angle = (p + 0.5) * PI / 2
        val gl = (cos(angle) * gain).toFloat()
        val gr = (sin(angle) * gain).toFloat()
        val from = max(0L, winStart - s0).toInt()
        val to = min(v.data.size.toLong(), winEnd - s0).toInt()
        val d = v.data
        var o = ((s0 + from - winStart) * 2).toInt()
        for (i in from until to) {
            val x = d[i]
            out[o] += x * gl
            out[o + 1] += x * gr
            o += 2
        }
    }

    /** Small stateful stereo reverb (comb + allpass), continuous across chunks. */
    private class Reverb(sr: Int, spread: Int) {
        private val combs = listOf(0.0297, 0.0371, 0.0411, 0.0437).map { FloatArray((it * sr).toInt() + spread) }
        private val combG = floatArrayOf(0.80f, 0.78f, 0.76f, 0.74f)
        private val combI = IntArray(4)
        private val aps = listOf(0.0116, 0.0134).map { FloatArray((it * sr).toInt() + spread / 2) }
        private val apI = IntArray(2)
        fun process(x: Float): Float {
            var wet = 0f
            for (k in 0 until 4) {
                val buf = combs[k]
                val i = combI[k]
                val past = buf[i]
                buf[i] = x + combG[k] * past
                combI[k] = if (i + 1 == buf.size) 0 else i + 1
                wet += past
            }
            wet *= 0.25f
            for (k in 0 until 2) {
                val buf = aps[k]
                val i = apI[k]
                val past = buf[i]
                val v = wet + 0.6f * past
                buf[i] = v
                apI[k] = if (i + 1 == buf.size) 0 else i + 1
                wet = -0.6f * v + past
            }
            return wet
        }
    }

    private class WavWriter(val file: File, val sr: Int) {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private var frames = 0L
        init { out.write(ByteArray(44)) }
        fun write(buf: FloatArray, count: Int, gain: Float) {
            val b = ByteArray(count * 2)
            for (i in 0 until count) {
                var x = buf[i] * gain
                val ax = abs(x)
                if (ax > 0.8f) x = Math.signum(x) * (0.8f + 0.2f * tanh((ax - 0.8f) / 0.2f))
                val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
                b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
            }
            out.write(b)
            frames += count / 2
        }
        fun close() {
            out.close()
            val dataLen = frames * 4
            RandomAccessFile(file, "rw").use { r ->
                val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4)
                h.putShort(4).putShort(16).put("data".toByteArray()).putInt(dataLen.toInt())
                r.seek(0); r.write(h.array())
            }
        }
    }

    /**
     * Render [notes] to two WAV files. [melodyChannel] goes only into the "with melody" file.
     * Returns null if cancelled.
     */
    fun render(
        notes: List<Note>, melodyChannel: Int, withMelody: File, withoutMelody: File,
        progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): Result? {
        val sr = sampleRate
        val songEnd = (notes.maxOfOrNull { it.end } ?: 0.0) + 3.5
        val totalSamples = (songEnd * sr).toLong()
        val chunk = 20L * sr
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(threads)
        val accs = Array(threads) { FloatArray((chunk * 2).toInt()) }
        val mels = Array(threads) { FloatArray((chunk * 2).toInt()) }
        val mixOn = FloatArray((chunk * 2).toInt())
        val wOn = WavWriter(withMelody, sr)
        val wOff = WavWriter(withoutMelody, sr)
        val revL = Reverb(sr, 0); val revR = Reverb(sr, 23)
        val revLm = Reverb(sr, 0); val revRm = Reverb(sr, 23)
        var gain = -1f
        val pending = ArrayList<Pair<FloatArray, FloatArray>>()
        var first = 0
        var cs = 0L
        try {
            while (cs < totalSamples) {
                if (cancelled()) return null
                val ce = min(cs + chunk, totalSamples)
                while (first < notes.size && (notes[first].end + 3.2) * sr < cs) first++
                var last = first
                while (last < notes.size && notes[last].start * sr < ce) last++
                val jobs = ArrayList<Future<*>>()
                for (t in 0 until threads) {
                    val acc = accs[t]; val mel = mels[t]
                    jobs.add(pool.submit {
                        acc.fill(0f); mel.fill(0f)
                        var k = first + t
                        while (k < last) {
                            val nt = notes[k]
                            k += threads
                            if ((nt.end + 3.2) * sr < cs || nt.key !in 0..127) continue
                            val dur = max(0.02, nt.end - nt.start)
                            val s0 = (nt.start * sr).toLong()
                            val target = if (nt.channel == melodyChannel) mel else acc
                            val g = nt.volume * nt.volume * 0.5f
                            for (v in voicesFor(nt, nt.key, dur)) accumulate(v, s0, cs, ce, target, g, nt.pan)
                        }
                    })
                }
                for (j in jobs) j.get()
                val acc = accs[0]; val mel = mels[0]
                val count = ((ce - cs) * 2).toInt()
                for (t in 1 until threads) {
                    val a = accs[t]; val m = mels[t]
                    for (i in 0 until count) { acc[i] += a[i]; mel[i] += m[i] }
                }
                var i = 0
                val wa = 0.18f
                while (i < count) {
                    val la = acc[i]; val ra = acc[i + 1]
                    val lm = mel[i]; val rm = mel[i + 1]
                    val accL = la + wa * revL.process(la + ra)
                    val accR = ra + wa * revR.process(la + ra)
                    val melL = lm + wa * revLm.process(lm + rm)
                    val melR = rm + wa * revRm.process(lm + rm)
                    acc[i] = accL; acc[i + 1] = accR
                    mixOn[i] = accL + melL; mixOn[i + 1] = accR + melR
                    i += 2
                }
                if (gain < 0) {
                    pending.add(mixOn.copyOf(count) to acc.copyOf(count))
                    val heard = pending.sumOf { it.first.size } / 2 / sr
                    if (heard >= 40 || ce >= totalSamples) {
                        gain = autoGain(pending.map { it.first }, sr)
                        for ((on, off) in pending) { wOn.write(on, on.size, gain); wOff.write(off, off.size, gain) }
                        pending.clear()
                    }
                } else {
                    wOn.write(mixOn, count, gain)
                    wOff.write(acc, count, gain)
                }
                progress((ce.toFloat() / totalSamples).coerceIn(0f, 1f))
                cs = ce
            }
            if (pending.isNotEmpty()) {
                gain = autoGain(pending.map { it.first }, sr)
                for ((on, off) in pending) { wOn.write(on, on.size, gain); wOff.write(off, off.size, gain) }
            }
        } finally {
            pool.shutdownNow()
            wOn.close(); wOff.close()
        }
        return Result(withMelody, withoutMelody, totalSamples.toDouble() / sr)
    }

    /** Aim the busy parts of the song's first 40 seconds at a steady loudness. */
    private fun autoGain(chunks: List<FloatArray>, sr: Int): Float {
        val rms = ArrayList<Double>()
        for (c in chunks) {
            var i = 0
            while (i + 2 * sr <= c.size) {
                var s = 0.0
                for (j in i until i + 2 * sr) s += c[j] * c[j]
                rms.add(sqrt(s / (2 * sr)))
                i += 2 * sr
            }
        }
        if (rms.isEmpty()) return 1f
        rms.sort()
        val loud = rms[min(rms.size - 1, (rms.size * 0.9).toInt())]
        return if (loud > 1e-6) min(0.16 / loud, 40.0).toFloat() else 1f
    }
}
