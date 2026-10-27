package com.karaok.engine

import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Built-in sound effects, synthesized so the app owns them outright. Written as WAV files. */
object Sfx {
    val NAMES = listOf("👏 Applause", "📯 Airhorn", "🥁 Drum roll", "🎺 Fanfare", "🤡 Ba-dum-tss")

    private class Buf(val sr: Int, seconds: Double) {
        val l = FloatArray((sr * seconds).toInt())
        val r = FloatArray(l.size)
        fun add(at: Int, x: FloatArray, gl: Float = 1f, gr: Float = 1f) {
            for (i in x.indices) {
                val j = at + i
                if (j < 0 || j >= l.size) continue
                l[j] += x[i] * gl; r[j] += x[i] * gr
            }
        }
        fun normalize(peak: Float) {
            var m = 1e-9f
            for (i in l.indices) m = max(m, max(abs(l[i]), abs(r[i])))
            val g = peak / m
            for (i in l.indices) { l[i] *= g; r[i] *= g }
        }
    }

    private fun bandpass(x: FloatArray, lo: Double, hi: Double, sr: Int) {
        // simple one-pole high-pass then low-pass
        val ah = exp(-2 * PI * lo / sr).toFloat()
        val al = exp(-2 * PI * hi / sr).toFloat()
        var prevX = 0f; var hp = 0f; var lp = 0f
        for (i in x.indices) {
            hp = ah * (hp + x[i] - prevX); prevX = x[i]
            lp = (1 - al) * hp + al * lp
            x[i] = lp
        }
    }

    private fun applause(sr: Int, rnd: Random): Buf {
        val b = Buf(sr, 4.0)
        val n = b.l.size
        val clap = (0.025 * sr).toInt()
        for (k in 0 until 1040) {
            val s = rnd.nextInt(n - clap)
            val t = s.toDouble() / n
            val crowd = min(1.0, t / 0.12) * min(1.0, (1 - t) / 0.4)
            if (rnd.nextDouble() > crowd * 1.05) continue
            val lvl = 0.3f + 0.7f * rnd.nextFloat()
            val burst = FloatArray(clap) { (rnd.nextGaussian() * exp(-7.0 * it / clap)).toFloat() * lvl }
            val pan = 0.15f + 0.7f * rnd.nextFloat()
            b.add(s, burst, 1 - pan, pan)
        }
        bandpass(b.l, 700.0, 6000.0, sr); bandpass(b.r, 700.0, 6000.0, sr)
        b.normalize(0.6f)
        return b
    }

    private fun airhorn(sr: Int): Buf {
        val b = Buf(sr, 1.4)
        var at = 0
        for (d in listOf(0.16, 0.16, 0.7)) {
            val n = (d * sr).toInt()
            val x = FloatArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val vib = 1 + 0.004 * sin(2 * PI * 6 * t)
                var v = 0.0
                for (f in listOf(349.2, 440.0, 523.3)) { val ph = (f * vib * t) % 1.0; v += 2 * ph - 1 }
                val env = min(1.0, t / 0.01) * min(1.0, (d - t) / 0.06)
                x[i] = (v * env).toFloat()
            }
            bandpass(x, 250.0, 3500.0, sr)
            b.add(at, x)
            at += n + (0.07 * sr).toInt()
        }
        b.normalize(0.55f)
        return b
    }

    private fun snare(sr: Int, rnd: Random, dur: Double, level: Float): FloatArray {
        val n = (dur * sr).toInt()
        return FloatArray(n) {
            val t = it.toDouble() / sr
            ((rnd.nextGaussian() * exp(-t * 28) * 0.8 + sin(2 * PI * 190 * t) * exp(-t * 40) * 0.6) * level).toFloat()
        }
    }

    private fun cymbal(sr: Int, rnd: Random, dur: Double): FloatArray {
        val n = (dur * sr).toInt()
        val x = FloatArray(n) { (rnd.nextGaussian() * exp(-2.2 * it / sr)).toFloat() }
        var prev = 0f; var hp = 0f
        val a = exp(-2 * PI * 4500 / sr).toFloat()
        for (i in x.indices) { hp = a * (hp + x[i] - prev); prev = x[i]; x[i] = hp }
        return x
    }

    private fun drumRoll(sr: Int, rnd: Random): Buf {
        val secs = 2.2
        val b = Buf(sr, secs + 2.3)
        var t = 0.0
        var k = 0
        while (t < secs) {
            val lvl = (0.25 + 0.75 * t / secs).toFloat() * if (k % 2 == 1) 0.85f else 1f
            b.add((t * sr).toInt(), snare(sr, rnd, 0.08, lvl))
            t += 1 / 22.0; k++
        }
        val s = (secs * sr).toInt()
        b.add(s, cymbal(sr, rnd, 2.2), 1.6f, 1.5f)
        val kick = FloatArray((0.3 * sr).toInt()) {
            val tt = it.toDouble() / sr
            (sin(2 * PI * (55 + 90 * exp(-tt * 30)) * tt) * exp(-tt * 9) * 1.4).toFloat()
        }
        b.add(s, kick)
        b.normalize(0.7f)
        return b
    }

    private fun rimshot(sr: Int, rnd: Random): Buf {
        val b = Buf(sr, 1.9)
        for ((at, f) in listOf(0.0 to 190.0, 0.18 to 140.0)) {
            val tom = FloatArray((0.22 * sr).toInt()) {
                val t = it.toDouble() / sr
                (sin(2 * PI * f * (1 + 0.6 * exp(-t * 25)) * t) * exp(-t * 14)).toFloat()
            }
            b.add((at * sr).toInt(), tom)
        }
        val s = (0.38 * sr).toInt()
        b.add(s, snare(sr, rnd, 0.15, 1f))
        b.add(s, cymbal(sr, rnd, 1.4), 1.2f, 1.1f)
        b.normalize(0.65f)
        return b
    }

    private val FANFARE = listOf(Triple(0.00, 0.16, 67), Triple(0.18, 0.16, 67), Triple(0.36, 0.16, 67),
        Triple(0.54, 0.55, 72), Triple(1.12, 0.30, 76), Triple(1.45, 1.10, 79))

    private fun fanfareSynth(sr: Int): Buf {
        val b = Buf(sr, 3.0)
        for ((st, du, k) in FANFARE) {
            val f = 440.0 * Math.pow(2.0, (k - 69) / 12.0)
            val n = (du * sr).toInt()
            val x = FloatArray(n) {
                val t = it.toDouble() / sr
                val ph = (f * t) % 1.0
                ((2 * ph - 1) * min(1.0, t / 0.02) * min(1.0, (du - t) / 0.08)).toFloat()
            }
            bandpass(x, 200.0, 4000.0, sr)
            b.add((st * sr).toInt(), x)
        }
        b.normalize(0.6f)
        return b
    }

    private fun write(b: Buf, f: File) {
        val n = b.l.size
        val bb = java.nio.ByteBuffer.allocate(44 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(b.sr).putInt(b.sr * 4)
        bb.putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) {
            bb.putShort((b.l[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
            bb.putShort((b.r[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
        }
        f.writeBytes(bb.array())
    }

    /** Make the built-in effects as WAV files in [dir]; returns name -> file. Uses the SoundFont trumpet if given. */
    fun makeBuiltin(dir: File, sr: Int = 44100, sf: SoundFont? = null): LinkedHashMap<String, File> {
        dir.mkdirs()
        val rnd = Random(42)
        val out = LinkedHashMap<String, File>()
        fun save(name: String, file: String, make: () -> Buf) {
            val f = File(dir, file)
            if (!f.isFile || f.length() < 1000) write(make(), f)
            out[name] = f
        }
        save(NAMES[0], "applause.wav") { applause(sr, rnd) }
        save(NAMES[1], "airhorn.wav") { airhorn(sr) }
        save(NAMES[2], "drumroll.wav") { drumRoll(sr, rnd) }
        val fan = File(dir, "fanfare.wav")
        if (!fan.isFile || fan.length() < 1000) {
            var done = false
            if (sf != null) try {
                val notes = ArrayList<Note>()
                for ((st, du, k) in FANFARE) {
                    notes.add(Note(st, st + du, 0, k, 110, 56, 0, 1f, -0.1f, 0f))
                    notes.add(Note(st, st + du, 1, k - 12, 95, 57, 0, 0.9f, 0.1f, 0f))
                }
                notes.add(Note(1.45, 2.6, 2, 48, 100, 47, 0, 1f, 0f, 0f))
                val tmp = File(dir, "fanfare_tmp.wav")
                Synth(sf, sr).render(notes, -1, tmp, fan)
                tmp.delete()
                done = fan.length() > 1000
            } catch (_: Exception) {
            }
            if (!done) write(fanfareSynth(sr), fan)
        }
        out[NAMES[3]] = fan
        save(NAMES[4], "rimshot.wav") { rimshot(sr, rnd) }
        return out
    }
}
