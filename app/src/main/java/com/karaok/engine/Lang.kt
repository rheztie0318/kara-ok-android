package com.karaok.engine

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text encoding + song language detection (same rules as the Windows version). */
object Lang {
    val LANGUAGES = listOf("OPM / Tagalog", "English", "Korean", "Japanese", "Chinese", "Thai", "Other")

    private val TAGALOG = """
        ang ng mga sa ko mo ka ikaw ako akin iyo kita tayo kayo sila siya natin namin ninyo nila niya
        hindi oo na pa ba lang din rin naman nga kasi kung pag pagka dahil para kahit bakit paano saan
        mahal pag-ibig ibig puso sinta irog giliw buhay mundo kailan kailanman lagi palagi ngayon bukas
        gabi araw umaga langit bituin luha ulan hangin alaala pangarap salamat paalam ganda maganda
        iniibig minamahal mahalin iibigin ikaw'y ako'y ay at si ni kay kanino walang wala may meron mayroon
        nasaan dito diyan doon ito iyan iyon yan yun lahat bawat tanging nag nang nung noon ganito ganyan
        sana pwede puwede kaya talaga muli isa dalawa ating aking iyong kanyang
    """.trim().split(Regex("\\s+")).toSet()

    private val ENGLISH = """
        the and you i me my your to of in it is that for on with be this was are love baby heart
        all we he she they what when where how why not no yes can will just know like get got
    """.trim().split(Regex("\\s+")).toSet()

    private val OPM_ARTISTS = listOf(
        "apo hiking society", "eraserheads", "regine velasquez", "sarah geronimo", "gary valenciano",
        "martin nievera", "ogie alcasid", "basil valdez", "rey valera", "freddie aguilar", "jose mari chan",
        "lea salonga", "zsa zsa padilla", "sharon cuneta", "rivermaya", "parokya ni edgar", "kamikazee",
        "bamboo", "south border", "side a", "aegis", "moira dela torre", "ben&ben", "ben & ben",
        "december avenue", "this band", "mayonnaise", "silent sanctuary", "imago", "hale", "sponge cola",
        "gloc-9", "yeng constantino", "kz tandingan", "morissette", "angeline quinto", "jaya", "vina morales",
        "lani misalucha", "nina", "juris", "kyla", "erik santos", "jed madela", "piolo pascual",
        "daniel padilla", "bugoy drilon", "michael pangilinan", "juan karlos", "sb19", "bini",
        "cueshe", "6cyclemind", "callalily", "hajji alejandro", "rico j. puno", "victor wood",
        "asin", "florante", "joey ayala", "orient pearl", "introvoys", "true faith",
        "neocolours", "smokey mountain", "vst & company", "hotdog", "sampaguita", "yano", "siakol",
    )

    private fun charset(vararg names: String): Charset? {
        for (n in names) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n)
            } catch (_: Exception) {
            }
        }
        return null
    }

    val CP1252: Charset = charset("windows-1252", "Cp1252", "ISO-8859-1") ?: Charsets.ISO_8859_1
    private val SJIS = charset("windows-31j", "MS932", "Shift_JIS")
    private val KOREAN = charset("x-windows-949", "MS949", "windows-949", "EUC-KR")
    private val THAI = charset("x-windows-874", "windows-874", "TIS-620", "MS874")
    private val GBK = charset("GBK", "GB18030")
    private val BIG5 = charset("Big5", "Big5-HKSCS")

    fun charsetFor(name: String?): Charset = when (name) {
        null, "", "ascii", "cp1252" -> CP1252
        "utf-8" -> Charsets.UTF_8
        "cp932" -> SJIS ?: CP1252
        "cp949" -> KOREAN ?: CP1252
        "cp874" -> THAI ?: CP1252
        "gbk" -> GBK ?: CP1252
        "big5" -> BIG5 ?: CP1252
        else -> CP1252
    }

    private fun strict(raw: ByteArray, cs: Charset?): String? {
        if (cs == null) return null
        return try {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun count(s: CharSequence, lo: Int, hi: Int): Int {
        var n = 0
        for (ch in s) if (ch.code in lo..hi) n++
        return n
    }

    private fun nonAscii(s: String) = s.filter { it.code >= 0x80 }

    private fun thaiOk(text: String): Boolean {
        fun isMark(c: Int) = c == 0x0E31 || c in 0x0E34..0x0E3A || c in 0x0E47..0x0E4E
        var bad = 0
        var total = 0
        var prev = ' '
        for (ch in text) {
            if (isMark(ch.code)) {
                total++
                if (!(prev.code in 0x0E01..0x0E2E || isMark(prev.code))) bad++
            }
            prev = ch
        }
        return total == 0 || bad.toDouble() / total < 0.08
    }

    /** Best guess for the encoding of karaoke words. Returns a short name (see [charsetFor]). */
    fun detectEncoding(raw: ByteArray): String {
        if (raw.isEmpty() || raw.all { it >= 0 }) return "ascii"
        if (strict(raw, Charsets.UTF_8) != null) return "utf-8"
        val high = raw.count { it < 0 }
        val visible = raw.count { (it.toInt() and 0xFF) > 0x20 }
        if (high < 0.25 * maxOf(1, visible)) return "cp1252"
        strict(raw, SJIS)?.let {
            val na = nonAscii(it)
            if (na.isNotEmpty() && count(na, 0x3040, 0x30FF).toDouble() / na.length > 0.15) return "cp932"
        }
        strict(raw, KOREAN)?.let {
            val na = nonAscii(it)
            if (na.isNotEmpty() && count(na, 0xAC00, 0xD7AF).toDouble() / na.length > 0.9) return "cp949"
        }
        strict(raw, THAI)?.let {
            if (count(it, 0x0E00, 0x0E7F).toDouble() / maxOf(1, high) > 0.95 && thaiOk(it)) return "cp874"
        }
        for ((name, cs) in listOf("gbk" to GBK, "big5" to BIG5)) {
            strict(raw, cs)?.let {
                val na = nonAscii(it)
                if (na.isNotEmpty() && count(na, 0x4E00, 0x9FFF).toDouble() / na.length > 0.85) return name
            }
        }
        return "cp1252"
    }

    fun guessLanguage(text: String): String {
        if (text.isBlank()) return "Other"
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return "Other"
        val n = letters.length
        if (count(letters, 0xAC00, 0xD7AF) + count(letters, 0x1100, 0x11FF) > 0.3 * n) return "Korean"
        if (count(letters, 0x3040, 0x30FF) > 0.1 * n) return "Japanese"
        if (count(letters, 0x4E00, 0x9FFF) > 0.3 * n) return "Chinese"
        if (count(letters, 0x0E00, 0x0E7F) > 0.3 * n) return "Thai"
        val low = text.lowercase()
        if (OPM_ARTISTS.any { low.contains(it) }) return "OPM / Tagalog"
        val words = Regex("[a-zñ'\\-]+").findAll(low).map { it.value }.toList()
        if (words.isEmpty()) return "Other"
        val tl = words.count { it in TAGALOG }
        val en = words.count { it in ENGLISH }
        if (tl >= 2 && tl >= en * 0.6 && tl >= 0.15 * words.size) return "OPM / Tagalog"
        if (tl == 1 && en == 0 && words.size <= 6) return "OPM / Tagalog"
        if (en > 0 || letters.all { it.code < 128 }) return "English"
        return "Other"
    }
}
