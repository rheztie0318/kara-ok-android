package com.karaok.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Phone remote: a tiny web server on the local Wi-Fi. Guests open the page from the QR code,
 * search and reserve songs, use remote buttons, and upload songs to the TV.
 */
class RemoteServer(
    private val page: String,
    private val uploadDir: File,
    private val handler: Handler,
) {
    interface Handler {
        fun songs(): List<Song>
        fun state(): String                                  // JSON
        fun reserve(number: Int, singer: String, first: Boolean)
        fun control(action: String, arg: String)
        fun uploaded(file: File)
    }

    var port = 8686
        private set
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    val url get() = "http://${localIp()}:$port/"

    fun start() {
        var last: Exception? = null
        for (p in listOf(8686, 8687, 8688, 0)) {
            try {
                server = ServerSocket(p).also { port = it.localPort }
                break
            } catch (e: Exception) { last = e }
        }
        val s = server ?: throw IllegalStateException("Couldn't start the phone remote: ${last?.message}")
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { serve(c) }
            }
        }, "remote-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun serve(sock: Socket) {
        sock.use { c ->
            try {
                c.soTimeout = 30000
                val inp = BufferedInputStream(c.getInputStream())
                val out = c.getOutputStream()
                val first = readLine(inp) ?: return
                val parts = first.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(inp) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> send(out, 200, page, "text/html; charset=utf-8")
                    method == "GET" && path == "/api/songs" -> send(out, 200, songsJson(query["q"] ?: "", query["lang"] ?: ""))
                    method == "GET" && path == "/api/langs" -> send(out, 200, langsJson())
                    method == "GET" && path == "/api/state" -> send(out, 200, handler.state())
                    method == "POST" && path == "/api/upload" -> upload(inp, out, query["name"] ?: "", length)
                    method == "POST" -> {
                        val body = String(readBody(inp, minOf(length, 8192L)), Charsets.UTF_8)
                        when (path) {
                            "/api/reserve" -> reserve(out, body)
                            "/api/control" -> {
                                handler.control(jsonStr(body, "action").take(20), jsonStr(body, "arg").take(60))
                                send(out, 200, "{\"ok\":true}")
                            }
                            else -> send(out, 404, "{\"error\":\"not found\"}")
                        }
                    }
                    else -> send(out, 404, "{\"error\":\"not found\"}")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reserve(out: OutputStream, body: String) {
        val num = jsonStr(body, "number").trim()
        val singer = jsonStr(body, "singer").trim().take(30).ifEmpty { "Guest" }
        val first = Regex("\"first\"\\s*:\\s*true").containsMatchIn(body)
        val song = handler.songs().firstOrNull { it.number.toString() == num }
        if (song == null) {
            send(out, 200, "{\"ok\":false,\"msg\":${q("No song number $num")}}")
            return
        }
        handler.reserve(song.number, singer, first)
        val word = if (first) "Reserved FIRST" else "Reserved"
        send(out, 200, "{\"ok\":true,\"msg\":${q("$word: $num ${song.title} for $singer")}}")
    }

    private fun upload(inp: InputStream, out: OutputStream, rawName: String, length: Long) {
        val name = File(rawName).name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        val allowed = Library.SONG_EXT + setOf("cdg", "lrc", "txt", "sf2")
        if (name.isEmpty() || ext !in allowed) {
            readBody(inp, minOf(length, 1L shl 20)); send(out, 200, "{\"ok\":false,\"msg\":${q("Not a song file: $name")}}"); return
        }
        if (length <= 0 || length > 700L * 1024 * 1024) {
            send(out, 200, "{\"ok\":false,\"msg\":${q("File too big: $name")}}"); return
        }
        uploadDir.mkdirs()
        val dest = File(uploadDir, name)
        val tmp = File(uploadDir, ".$name.part")
        tmp.outputStream().use { o ->
            val buf = ByteArray(1 shl 16)
            var left = length
            while (left > 0) {
                val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                o.write(buf, 0, r)
                left -= r
            }
            if (left > 0) { tmp.delete(); send(out, 200, "{\"ok\":false,\"msg\":${q("Upload cut off: $name")}}"); return }
        }
        dest.delete(); tmp.renameTo(dest)
        handler.uploaded(dest)
        send(out, 200, "{\"ok\":true,\"msg\":${q("Uploaded $name")}}")
    }

    private fun readBody(inp: InputStream, n: Long): ByteArray {
        val b = ByteArray(n.toInt())
        var off = 0
        while (off < b.size) {
            val r = inp.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return if (off == b.size) b else b.copyOf(off)
    }

    private fun send(out: OutputStream, code: Int, body: String, type: String = "application/json; charset=utf-8") {
        val data = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Not Found"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(data)
        out.flush()
    }

    private fun songsJson(query: String, lang: String): String {
        val term = query.trim().lowercase()
        var list = handler.songs()
        if (lang.isNotEmpty()) list = list.filter { (it.lang ?: "Other") == lang }
        list = when {
            term.isEmpty() -> list
            term.all { it.isDigit() } -> list.filter { it.number.toString().startsWith(term) }
            else -> { val w = term.split(Regex("\\s+")); list.filter { s -> w.all { s.searchText.contains(it) } } }
        }
        return list.take(150).joinToString(",", "[", "]") {
            "{\"n\":${it.number},\"t\":${q(it.title)},\"a\":${q(it.artist)},\"k\":${q(it.kind)}}"
        }
    }

    private fun langsJson(): String =
        handler.songs().groupingBy { it.lang ?: "Other" }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(",", "[", "]") { q(it.key) }

    companion object {
        fun q(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }

        /** Read a string (or number/bool) field from a small flat JSON object. */
        fun jsonStr(body: String, key: String): String {
            val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}\\s]+)").find(body) ?: return ""
            val raw = m.groups[2]?.value ?: return m.groupValues[1]
            return raw.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
                .replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }

        fun parseQuery(qs: String): Map<String, String> = qs.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        fun localIp(): String {
            try {
                val candidates = ArrayList<String>()
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        // prefer Wi-Fi / Ethernet style addresses
                        if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) candidates.add(0, ip) else candidates.add(ip)
                    }
                }
                return candidates.firstOrNull() ?: "127.0.0.1"
            } catch (_: Exception) {
                return "127.0.0.1"
            }
        }
    }
}
