package com.karaok.engine

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Song library with permanent song numbers (same numbering idea as the Windows version). */
class Song(
    val file: File, val number: Int, private val dataDir: File,
    title: String = "", artist: String = "", var lang: String? = null, var enc: String? = null,
) {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val isMidi = ext in Library.MIDI_EXT
    val isZip = ext == "zip"
    val hasVideo = ext in Library.VIDEO_EXT
    val cdgFile: File? = if (isMidi || isZip || hasVideo) null else findCdg(file)
    val title: String
    val artist: String
    var lyrics: Lyrics? = null
        private set

    init {
        val (t, a) = Library.splitName(file.nameWithoutExtension)
        this.title = title.ifEmpty { t }
        this.artist = artist.ifEmpty { a }
    }

    val name get() = if (artist.isNotEmpty()) "$title — $artist" else title
    val hasCdg get() = isZip || cdgFile != null
    val exists get() = file.isFile
    val searchText by lazy { "$number $title $artist".lowercase() }
    val kind get() = when { isMidi -> "MIDI"; hasCdg -> "CD+G"; hasVideo -> "Video"; else -> "Audio" }

    private val hash by lazy { Library.sha1(file.absolutePath.lowercase()).take(16) }
    private val userLyrics get() = File(File(dataDir, "lyrics").apply { mkdirs() }, "$hash.lrc")
    private fun sidecars() = listOf("lrc", "LRC", "txt").map { File(file.parentFile, file.nameWithoutExtension + "." + it) }

    init { loadLyrics() }   // after the properties it uses

    fun loadLyrics() {
        lyrics = null
        for (f in listOf(userLyrics) + sidecars()) {
            if (!f.isFile) continue
            val raw = try { f.readText() } catch (_: Exception) { null } ?: continue
            val text = raw.removePrefix("\uFEFF")
            if (text.isNotBlank()) { lyrics = Lyrics.parse(text); return }
        }
    }

    /** Save lyrics next to the song if possible, otherwise in the app's own folder. */
    fun saveLyrics(text: String): String {
        if (text.isBlank()) {
            userLyrics.delete(); File(file.parentFile, file.nameWithoutExtension + ".lrc").delete()
            loadLyrics()
            return "Lyrics removed."
        }
        val side = File(file.parentFile, file.nameWithoutExtension + ".lrc")
        val msg = try {
            side.writeText(text); userLyrics.delete(); "Saved ${side.name} next to the song."
        } catch (_: Exception) {
            userLyrics.writeText(text); "Saved the lyrics in Kara-OK's own folder."
        }
        lyrics = Lyrics.parse(text)
        return msg
    }

    fun detectLanguage(): String {
        val sb = StringBuilder("$title $artist")
        lyrics?.lines?.forEach { sb.append(' ').append(it.second) }
        return Lang.guessLanguage(sb.toString())
    }

    /** (audio file, cdg file or null) ready to play; unpacks MP3+G zips into the cache. */
    fun mediaFiles(cacheDir: File): Pair<File, File?> {
        if (!isZip) return file to cdgFile
        val members = Library.zipKaraoke(file) ?: throw IllegalStateException("This zip has no MP3 + CDG karaoke song.")
        val dir = File(cacheDir, "cdg_$hash").apply { mkdirs() }
        val out = ArrayList<File>()
        ZipFile(file).use { z ->
            for ((i, m) in members.toList().withIndex()) {
                val e = z.getEntry(m)
                val dest = File(dir, if (i == 0) "audio." + m.substringAfterLast('.').lowercase() else "song.cdg")
                if (!(dest.isFile && dest.length() == e.size)) z.getInputStream(e).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                out.add(dest)
            }
        }
        return out[0] to out[1]
    }

    companion object {
        fun findCdg(f: File): File? {
            val dir = f.parentFile ?: return null
            for (e in listOf("cdg", "CDG", "Cdg")) {
                val c = File(dir, f.nameWithoutExtension + "." + e)
                if (c.isFile) return c
            }
            return null
        }
    }
}

class Library(private val dataDir: File) {
    companion object {
        val MIDI_EXT = setOf("mid", "midi", "kar", "rmi")
        val VIDEO_EXT = setOf("mp4", "m4v", "mov", "mkv", "avi", "webm", "mpg", "mpeg", "wmv", "flv", "3gp", "ts")
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "wma")
        val SONG_EXT = MIDI_EXT + VIDEO_EXT + AUDIO_EXT + "zip"
        const val FIRST_NUMBER = 10001
        private val CDG_AUDIO = listOf(".mp3", ".ogg", ".wav", ".flac", ".m4a")

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun splitName(stem: String): Pair<String, String> {
            var s = stem.replace('_', ' ').trim()
            s = s.replace(Regex("^\\d{1,6}\\s*[-._)]\\s*"), "")
            s = s.replace(Regex("\\s*[(\\[](karaoke|videoke|instrumental|minus one|lyrics?)[)\\]]\\s*", RegexOption.IGNORE_CASE), " ")
            s = s.replace(Regex("\\s+"), " ").trim()
            val i = s.indexOf(" - ")
            return if (i > 0) s.substring(i + 3).trim() to s.substring(0, i).trim() else s to ""
        }

        /** (audio entry, cdg entry) inside an MP3+G zip, or null. */
        fun zipKaraoke(f: File): Pair<String, String>? = try {
            ZipFile(f).use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                val cdg = names.firstOrNull { it.lowercase().endsWith(".cdg") } ?: return null
                val auds = names.filter { n -> CDG_AUDIO.any { n.lowercase().endsWith(it) } }
                if (auds.isEmpty()) return null
                val stem = cdg.substringBeforeLast('.').lowercase()
                (auds.firstOrNull { it.substringBeforeLast('.').lowercase() == stem } ?: auds[0]) to cdg
            }
        } catch (_: Exception) {
            null
        }

        fun dedupeKey(s: Song) =
            s.title.lowercase().replace(Regex("[^a-z0-9]+"), "") + "|" + s.artist.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private val store = File(dataDir.apply { mkdirs() }, "library.tsv")
    private val all = ArrayList<Song>()          // includes songs whose drive is unplugged (keeps their numbers)
    private val byNumber = HashMap<Int, Song>()
    private val byPath = HashMap<String, Song>()
    var nextNumber = FIRST_NUMBER
        private set

    init { load() }

    /** Songs that are available right now, by number. */
    @get:Synchronized
    val songs: List<Song> get() = all.filter { it.exists }

    @Synchronized
    private fun load() {
        if (!store.isFile) return
        for (line in store.readLines()) {
            val p = line.split('\t')
            if (p.size < 2) continue
            if (p[0] == "#next") { nextNumber = p[1].toIntOrNull() ?: nextNumber; continue }
            val num = p[0].toIntOrNull() ?: continue
            val s = Song(File(p[1]), num, dataDir, p.getOrElse(2) { "" }, p.getOrElse(3) { "" },
                p.getOrElse(4) { "" }.ifEmpty { null }, p.getOrElse(5) { "" }.ifEmpty { null })
            add(s)
        }
        all.sortBy { it.number }
    }

    @Synchronized
    fun save() {
        val tmp = File(store.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("#next\t$nextNumber\n")
            for (s in all) {
                fun c(x: String?) = (x ?: "").replace('\t', ' ').replace('\n', ' ')
                w.write("${s.number}\t${c(s.file.absolutePath)}\t${c(s.title)}\t${c(s.artist)}\t${c(s.lang)}\t${c(s.enc)}\n")
            }
        }
        tmp.renameTo(store)
    }

    private fun add(s: Song) {
        all.add(s)
        byNumber[s.number] = s
        byPath[s.file.absolutePath.lowercase()] = s
        nextNumber = maxOf(nextNumber, s.number + 1)
    }

    /** Find song files under [roots] (depth-limited) and give new ones numbers. Returns how many were added. */
    fun scan(roots: List<File>, cancelled: () -> Boolean = { false }): Int {
        val found = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (cancelled() || depth > 6) return
            val list = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return
            for (f in list.sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth == 0 && f.name.equals("Android", true)) continue
                    walk(f, depth + 1)
                } else if (f.extension.lowercase() in SONG_EXT) found.add(f)
            }
        }
        for (r in roots) if (r.isDirectory) walk(r, 0)
        return addFiles(found)
    }

    @Synchronized
    fun addFiles(files: List<File>): Int {
        var added = 0
        for (f in files.sortedBy { it.name.lowercase() }) {
            val ext = f.extension.lowercase()
            if (ext !in SONG_EXT || !f.isFile) continue
            if (byPath.containsKey(f.absolutePath.lowercase())) continue
            if (ext == "zip" && zipKaraoke(f) == null) continue
            add(Song(f, nextNumber, dataDir))
            added++
        }
        all.sortBy { it.number }
        if (added > 0) save()
        return added
    }

    @Synchronized
    fun remove(s: Song) {
        all.remove(s); byNumber.remove(s.number); byPath.remove(s.file.absolutePath.lowercase())
        save()
    }

    @Synchronized
    fun find(number: Int): Song? = byNumber[number]?.takeIf { it.exists }

    fun find(number: String): Song? = number.trim().toIntOrNull()?.let { find(it) }

    fun search(query: String, lang: String? = null, dedupe: Boolean = false, limit: Int = 5000): List<Song> {
        val q = query.trim().lowercase()
        var list = songs
        if (!lang.isNullOrEmpty()) list = list.filter { (it.lang ?: "Other") == lang }
        list = when {
            q.isEmpty() -> list
            q.all { it.isDigit() } -> list.filter { it.number.toString().startsWith(q) }
            else -> { val words = q.split(Regex("\\s+")); list.filter { s -> words.all { s.searchText.contains(it) } } }
        }
        if (dedupe) {
            val seen = HashSet<String>()
            list = list.filter { seen.add(dedupeKey(it)) }
        }
        return list.take(limit)
    }

    @Synchronized
    fun undetected(): List<Song> = all.filter { it.lang == null && it.exists }

    fun languageCounts(): Map<String, Int> = songs.groupingBy { it.lang ?: "Other" }.eachCount()
}
