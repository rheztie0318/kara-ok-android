package com.karaok.engine

/** State of the lyrics at a moment: current line, colour-wipe 0..1, singing?, countdown dots. */
data class LyricState(val index: Int, val wipe: Float, val singing: Boolean, val dots: Int)

/** Lines with start times (LRC), or plain text lines (synced = false). */
open class Lyrics protected constructor() {
    var offset = 0.0
    var synced = false
    val lines = ArrayList<Pair<Double, String>>()
    var raw = ""

    companion object {
        private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]")
        private val META = Regex("^\\s*\\[(ti|ar|al|by|au|length|re|ve|#):", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)
        private val STRIP = Regex("\\[[^\\]]*\\]")
        private val WORDTAG = Regex("<[^>]*>")

        fun parse(text: String): Lyrics {
            val l = Lyrics()
            l.raw = text
            val timed = ArrayList<Pair<Double, String>>()
            val plain = ArrayList<String>()
            for (line in text.replace("\r", "").split("\n")) {
                OFFSET.find(line)?.let { l.offset = it.groupValues[1].toInt() / 1000.0; return@let }
                if (OFFSET.containsMatchIn(line) || META.containsMatchIn(line)) continue
                val times = TAG.findAll(line).map {
                    it.groupValues[1].toInt() * 60 + it.groupValues[2].replace(':', '.').toDouble()
                }.toList()
                val t = WORDTAG.replace(STRIP.replace(line, ""), "").trim()
                if (times.isNotEmpty()) times.forEach { timed.add(it to t) } else if (t.isNotEmpty()) plain.add(t)
            }
            if (timed.isNotEmpty()) {
                l.synced = true
                l.lines.addAll(timed.sortedBy { it.first })
            } else {
                l.lines.addAll(plain.map { 0.0 to it })
            }
            return l
        }

        fun plainLines(text: String): List<String> = text.replace("\r", "").split("\n")
            .filter { !META.containsMatchIn(it) && !OFFSET.containsMatchIn(it) }
            .map { WORDTAG.replace(STRIP.replace(it, ""), "").trim() }.filter { it.isNotEmpty() }

        fun fmtLrc(t: Double): String {
            val m = (t / 60).toInt()
            return String.format(java.util.Locale.US, "%02d:%05.2f", m, t - m * 60)
        }

        fun buildLrc(title: String, stamps: List<Double>, lines: List<String>): String {
            val sb = StringBuilder("[ti:$title]\n")
            for (k in stamps.indices) sb.append("[").append(fmtLrc(stamps[k])).append("]").append(lines[k]).append("\n")
            return sb.toString()
        }
    }

    open fun lineDuration(i: Int): Double {
        val (start, text) = lines[i]
        val next = if (i + 1 < lines.size) lines[i + 1].first else start + 8.0
        return maxOf(0.3, minOf(next - start, 0.7 + text.length * 0.085))
    }

    open fun wipeAt(i: Int, t: Double): Double =
        ((t - lines[i].first) / lineDuration(i)).coerceIn(0.0, 1.0)

    fun stateAt(time: Double, hint: Int = -1): LyricState {
        val t = time + offset
        var i = hint.coerceIn(-1, lines.size - 1)
        if (i >= 0 && lines[i].first > t) i = -1
        while (i + 1 < lines.size && lines[i + 1].first <= t) i++
        var wipe = 0.0
        var singing = false
        if (i >= 0 && lines[i].second.isNotEmpty()) {
            wipe = wipeAt(i, t)
            singing = t < lines[i].first + lineDuration(i)
        }
        var dots = 0
        if (i + 1 < lines.size) {
            val over = i < 0 || lines[i].second.isEmpty() || t > lines[i].first + lineDuration(i)
            if (over) {
                val gapFrom = if (i < 0) 0.0 else lines[i].first + lineDuration(i)
                val remain = lines[i + 1].first - t
                if (lines[i + 1].first - gapFrom > 4 && remain > 0 && remain <= 3) dots = Math.ceil(remain).toInt()
            }
        }
        return LyricState(i, wipe.toFloat(), singing, dots)
    }
}

/** Word-by-word lyrics from a MIDI karaoke file. */
class KarLyrics(song: MidiSong) : Lyrics() {
    private val syl = ArrayList<List<Triple<Double, Int, Int>>>()   // (time, char start, char length)

    init {
        synced = true
        val sb = StringBuilder()
        for (line in song.lines) {
            val text = line.joinToString("") { it.text }
            val lead = text.length - text.trimStart().length
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            val spans = ArrayList<Triple<Double, Int, Int>>()
            var pos = -lead
            for (s in line) {
                spans.add(Triple(s.time, maxOf(0, pos), s.text.length))
                pos += s.text.length
            }
            lines.add(line[0].time to trimmed)
            syl.add(spans)
            sb.append("[").append(fmtLrc(line[0].time)).append("]").append(trimmed).append("\n")
        }
        raw = sb.toString()
    }

    private fun sylEnd(i: Int, k: Int): Double {
        val spans = syl[i]
        val (t, _, len) = spans[k]
        val next = when {
            k + 1 < spans.size -> spans[k + 1].first
            i + 1 < lines.size -> lines[i + 1].first
            else -> t + 2.0
        }
        return t + maxOf(0.05, minOf(next - t, 0.25 + 0.12 * len))
    }

    override fun lineDuration(i: Int): Double = maxOf(0.3, sylEnd(i, syl[i].size - 1) - lines[i].first)

    override fun wipeAt(i: Int, t: Double): Double {
        val textLen = maxOf(1, lines[i].second.length)
        var covered = 0.0
        for ((k, s) in syl[i].withIndex()) {
            val (st, start, len) = s
            if (t < st) break
            val end = sylEnd(i, k)
            val frac = if (t >= end) 1.0 else (t - st) / maxOf(0.01, end - st)
            covered = start + len * frac
        }
        return minOf(1.0, covered / textLen)
    }
}
