package com.karaok.engine

import java.io.File

class Note(
    val start: Double, var end: Double, val channel: Int, val key: Int, val velocity: Int,
    val program: Int, val bank: Int, val volume: Float, val pan: Float, val bend: Float,
)

class Syllable(val time: Double, val text: String)

class MidiSong(
    val notes: List<Note>,
    val lines: List<List<Syllable>>,
    val duration: Double,
    val title: String,
    val melodyChannel: Int,
    val encoding: String,
    val language: String,
) {
    val hasWords get() = lines.isNotEmpty()
}

class MidiError(msg: String) : Exception(msg)

/** Standard MIDI / karaoke (.kar) reader. Port of the desktop midi_file.py. */
object MidiFile {
    private class Ev(val tick: Long, val track: Int, val order: Int, val kind: Int, val a: Int, val b: Int, val c: Int, val data: ByteArray?)

    private const val TEMPO = 0
    private const val OFF = 1
    private const val CC = 2
    private const val PROGRAM = 3
    private const val BEND = 4
    private const val TEXT = 5
    private const val LYRIC = 6
    private const val ON = 7

    fun load(file: File, encoding: String? = null): MidiSong = parse(file.readBytes(), encoding)

    private fun u32(d: ByteArray, i: Int) =
        ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or
            ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    fun parse(input: ByteArray, encoding: String? = null): MidiSong {
        var data = input
        if (data.size > 12 && String(data, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(data, 8, 4, Charsets.ISO_8859_1) == "RMID"
        ) {
            val j = indexOf(data, "MThd".toByteArray())
            if (j < 0) throw MidiError("No MIDI data inside this RMI file.")
            data = data.copyOfRange(j, data.size)
        }
        if (data.size < 14 || String(data, 0, 4, Charsets.ISO_8859_1) != "MThd") throw MidiError("Not a MIDI file.")
        val hlen = u32(data, 4).toInt()
        val ntracks = u16(data, 10)
        var division = u16(data, 12)
        var ticksPerSec = 0.0
        if (division and 0x8000 != 0) {
            val fps = 256 - (division shr 8)
            ticksPerSec = (fps * (division and 0xFF)).toDouble()
            division = 0
        }
        val events = ArrayList<Ev>(50000)
        var i = 8 + hlen
        var t = 0
        while (i + 8 <= data.size && t < maxOf(ntracks, 1) + 64) {
            val id = String(data, i, 4, Charsets.ISO_8859_1)
            val len = u32(data, i + 4).toInt()
            val start = i + 8
            val end = minOf(data.size, start + len)
            i = start + len
            if (id == "MTrk") {
                parseTrack(data, start, end, t, events)
                t++
            }
        }
        events.sortWith(compareBy<Ev>({ it.tick }, { it.order }))

        // tick -> seconds
        var tempo = 500000.0
        var lastTick = 0L
        var lastSec = 0.0
        fun toSec(tick: Long): Double =
            if (ticksPerSec > 0) tick / ticksPerSec
            else lastSec + (tick - lastTick) * tempo / 1e6 / maxOf(1, division)

        val program = IntArray(16)
        val bank = IntArray(16)
        val vol = IntArray(16) { 100 }
        val expr = IntArray(16) { 127 }
        val pan = IntArray(16) { 64 }
        val bend = FloatArray(16)
        val bendRange = FloatArray(16) { 2f }
        val rpnMsb = IntArray(16) { 127 }
        val rpnLsb = IntArray(16) { 127 }
        val sustain = BooleanArray(16)
        val active = HashMap<Int, ArrayDeque<Note>>()
        val held = Array(16) { ArrayList<Note>() }
        val notes = ArrayList<Note>(20000)
        val texts = ArrayList<Triple<Double, Int, ByteArray>>()
        val lyrics = ArrayList<Triple<Double, Int, ByteArray>>()

        for (e in events) {
            val sec = toSec(e.tick)
            when (e.kind) {
                TEMPO -> {
                    lastSec = sec; lastTick = e.tick
                    tempo = if (e.a > 0) e.a.toDouble() else 500000.0
                }
                ON -> {
                    val ch = e.a
                    val n = Note(
                        sec, sec, ch, e.b, e.c, program[ch], if (ch == 9) 128 else bank[ch],
                        (vol[ch] / 127f) * (expr[ch] / 127f), (pan[ch] - 64) / 128f, bend[ch],
                    )
                    active.getOrPut(ch * 128 + e.b) { ArrayDeque() }.addLast(n)
                }
                OFF -> {
                    val stack = active[e.a * 128 + e.b]
                    if (stack != null && stack.isNotEmpty()) {
                        val n = stack.removeFirst()
                        n.end = sec
                        if (sustain[e.a]) held[e.a].add(n)
                        notes.add(n)
                    }
                }
                CC -> {
                    val ch = e.a
                    val v = e.c
                    when (e.b) {
                        7 -> vol[ch] = v
                        11 -> expr[ch] = v
                        10 -> pan[ch] = v
                        0 -> bank[ch] = v
                        101 -> rpnMsb[ch] = v
                        100 -> rpnLsb[ch] = v
                        6 -> if (rpnMsb[ch] == 0 && rpnLsb[ch] == 0) bendRange[ch] = v.coerceIn(1, 24).toFloat()
                        64 -> {
                            val down = v >= 64
                            if (sustain[ch] && !down) {
                                for (n in held[ch]) n.end = maxOf(n.end, sec)
                                held[ch].clear()
                            }
                            sustain[ch] = down
                        }
                        121 -> { vol[ch] = 100; expr[ch] = 127; pan[ch] = 64; bend[ch] = 0f }
                        120, 123 -> {
                            for ((k, stack) in active) {
                                if (k / 128 == ch) {
                                    for (n in stack) { n.end = sec; notes.add(n) }
                                    stack.clear()
                                }
                            }
                        }
                    }
                }
                PROGRAM -> program[e.a] = e.b
                BEND -> bend[e.a] = e.b / 8192f * bendRange[e.a]
                TEXT -> texts.add(Triple(sec, e.track, e.data!!))
                LYRIC -> lyrics.add(Triple(sec, e.track, e.data!!))
            }
        }
        val endSec = if (events.isEmpty()) 0.0 else toSec(events.last().tick)
        for (stack in active.values) for (n in stack) { n.end = maxOf(endSec, n.start + 0.5); notes.add(n) }
        for (ch in 0 until 16) for (n in held[ch]) n.end = maxOf(n.end, endSec)
        for (n in notes) if (n.end <= n.start) n.end = n.start + 0.05
        notes.sortBy { it.start }

        val enc = encoding ?: run {
            val all = java.io.ByteArrayOutputStream()
            for (x in texts) all.write(x.third)
            for (x in lyrics) all.write(x.third)
            Lang.detectEncoding(all.toByteArray())
        }
        val cs = Lang.charsetFor(enc)
        val (syllables, title) = karaokeText(texts, lyrics, cs)
        val lines = splitLines(syllables)
        val flat = lines.flatten()
        val duration = maxOf(notes.maxOfOrNull { it.end } ?: 0.0, flat.maxOfOrNull { it.time } ?: 0.0, endSec)
        val melody = guessMelody(notes, flat)
        val lang = Lang.guessLanguage(title + " " + flat.joinToString("") { it.text })
        return MidiSong(notes, lines, duration, title, melody, enc, lang)
    }

    private fun indexOf(d: ByteArray, pat: ByteArray): Int {
        outer@ for (i in 0..d.size - pat.size) {
            for (j in pat.indices) if (d[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun parseTrack(d: ByteArray, start: Int, end: Int, track: Int, out: ArrayList<Ev>) {
        var i = start
        var tick = 0L
        var status = 0
        fun vlq(): Int {
            var v = 0
            while (true) {
                if (i >= end) throw MidiError("Truncated MIDI data.")
                val b = d[i++].toInt() and 0xFF
                v = (v shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return v
            }
        }
        try {
            while (i < end) {
                tick += vlq()
                if (i >= end) break
                val b = d[i].toInt() and 0xFF
                if (b == 0xFF) {
                    val type = d[i + 1].toInt() and 0xFF
                    i += 2
                    val len = vlq()
                    val p = i
                    i += len
                    if (i > end) break
                    when (type) {
                        0x51 -> if (len == 3) out.add(
                            Ev(tick, track, 0, TEMPO,
                                ((d[p].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF),
                                0, 0, null)
                        )
                        0x01 -> out.add(Ev(tick, track, 3, TEXT, 0, 0, 0, d.copyOfRange(p, p + len)))
                        0x05 -> out.add(Ev(tick, track, 3, LYRIC, 0, 0, 0, d.copyOfRange(p, p + len)))
                        0x2F -> return
                    }
                    continue
                }
                if (b == 0xF0 || b == 0xF7) {
                    i++
                    val len = vlq()
                    i += len
                    continue
                }
                if (b and 0x80 != 0) {
                    status = b
                    i++
                } else if (status == 0) throw MidiError("Bad MIDI event data.")
                val kind = status and 0xF0
                val ch = status and 0x0F
                if (kind == 0xC0 || kind == 0xD0) {
                    val d1 = d[i++].toInt() and 0x7F
                    if (kind == 0xC0) out.add(Ev(tick, track, 2, PROGRAM, ch, d1, 0, null))
                    continue
                }
                val d1 = d[i].toInt() and 0x7F
                val d2 = d[i + 1].toInt() and 0x7F
                i += 2
                when {
                    kind == 0x90 && d2 > 0 -> out.add(Ev(tick, track, 4, ON, ch, d1, d2, null))
                    kind == 0x80 || kind == 0x90 -> out.add(Ev(tick, track, 1, OFF, ch, d1, 0, null))
                    kind == 0xB0 -> out.add(Ev(tick, track, 2, CC, ch, d1, d2, null))
                    kind == 0xE0 -> out.add(Ev(tick, track, 2, BEND, ch, ((d2 shl 7) or d1) - 8192, 0, null))
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // truncated track: keep what we have
        }
    }

    private fun karaokeText(
        texts: List<Triple<Double, Int, ByteArray>>, lyrics: List<Triple<Double, Int, ByteArray>>,
        cs: java.nio.charset.Charset,
    ): Pair<List<Syllable>, String> {
        fun dec(b: ByteArray) = String(b, cs)
        var title = ""
        var karTrack = -1
        for ((_, track, raw) in texts) {
            val s = dec(raw)
            if (s.startsWith("@")) {
                if (s.length > 1 && s[1] == 'T' && title.isEmpty()) title = s.substring(2).trim()
                if (karTrack < 0) karTrack = track
            }
        }
        var out: List<Pair<Double, String>> = emptyList()
        if (karTrack >= 0) {
            out = texts.filter { it.second == karTrack }.map { it.first to dec(it.third) }.filter { !it.second.startsWith("@") }
        }
        if (out.isEmpty() && lyrics.isNotEmpty()) out = lyrics.map { it.first to dec(it.third) }
        if (out.isEmpty() && texts.isNotEmpty()) {
            val best = texts.groupBy { it.second }.values.maxByOrNull { it.size }!!
            if (best.size > 20) out = best.map { it.first to dec(it.third) }
        }
        return out.filter { it.second.isNotEmpty() }.map { Syllable(it.first, it.second) } to title
    }

    private fun splitLines(syl: List<Syllable>): List<List<Syllable>> {
        val lines = ArrayList<List<Syllable>>()
        var cur = ArrayList<Syllable>()
        fun flush() {
            if (cur.any { it.text.isNotBlank() }) lines.add(cur)
            cur = ArrayList()
        }
        for (s in syl) {
            var text = s.text
            if (text.startsWith("/") || text.startsWith("\\")) { flush(); text = text.substring(1) }
            while (text.startsWith("\r") || text.startsWith("\n")) { flush(); text = text.substring(1) }
            var after = false
            if (text.endsWith("\r") || text.endsWith("\n")) { after = true; text = text.trimEnd('\r', '\n') }
            if (text.isNotEmpty()) cur.add(Syllable(s.time, text))
            if (after) flush()
            if (cur.size > 14 && text.endsWith(" ")) flush()
        }
        flush()
        return lines
    }

    private fun guessMelody(notes: List<Note>, syl: List<Syllable>): Int {
        val times = syl.filter { it.text.isNotBlank() }.map { it.time }
        if (times.isEmpty()) return -1
        val starts = HashMap<Int, ArrayList<Double>>()
        for (n in notes) if (n.channel != 9) starts.getOrPut(n.channel) { ArrayList() }.add(n.start)
        var best = -1
        var bestScore = 0.0
        for ((ch, st) in starts) {
            st.sort()
            var hits = 0
            var j = 0
            for (t in times) {
                while (j < st.size && st[j] < t - 0.08) j++
                if (j < st.size && Math.abs(st[j] - t) <= 0.08) hits++
            }
            val score = hits.toDouble() / times.size
            if (score > bestScore) { best = ch; bestScore = score }
        }
        return if (bestScore >= 0.5) best else -1
    }
}
