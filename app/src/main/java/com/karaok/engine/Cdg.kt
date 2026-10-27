package com.karaok.engine

import java.io.File

/** CD+G karaoke graphics decoder (300x216, 16 colours, 300 packets per second). */
class Cdg(data: ByteArray) {
    companion object {
        const val W = 300
        const val H = 216
        private const val PER_SEC = 300
        fun load(f: File) = Cdg(f.readBytes())
    }

    private val index: IntArray      // packet number of each graphics packet
    private val instr: ByteArray
    private val payload: ByteArray   // 16 bytes per graphics packet
    val duration: Double

    private val screen = ByteArray(W * H)
    private val palette = IntArray(16) { 0xFF000000.toInt() }
    private var hOff = 0
    private var vOff = 0
    private var pos = 0
    private var dirty = true
    val pixels = IntArray(W * H)     // ARGB, ready for a Bitmap

    init {
        val n = data.size / 24
        if (n == 0) throw IllegalArgumentException("Empty .cdg file.")
        var count = 0
        for (k in 0 until n) if ((data[k * 24].toInt() and 0x3F) == 0x09) count++
        index = IntArray(count); instr = ByteArray(count); payload = ByteArray(count * 16)
        var j = 0
        for (k in 0 until n) {
            val b = k * 24
            if ((data[b].toInt() and 0x3F) != 0x09) continue
            index[j] = k
            instr[j] = (data[b + 1].toInt() and 0x3F).toByte()
            for (q in 0 until 16) payload[j * 16 + q] = (data[b + 4 + q].toInt() and 0x3F).toByte()
            j++
        }
        duration = n / PER_SEC.toDouble()
    }

    fun reset() {
        screen.fill(0); palette.fill(0xFF000000.toInt())
        hOff = 0; vOff = 0; pos = 0; dirty = true
    }

    private fun d(k: Int, q: Int) = payload[k * 16 + q].toInt() and 0x3F

    private fun tile(k: Int, xor: Boolean) {
        val c0 = d(k, 0) and 0x0F
        val c1 = d(k, 1) and 0x0F
        val y = (d(k, 2) and 0x1F) * 12
        val x = (d(k, 3) and 0x3F) * 6
        if (y + 12 > H || x + 6 > W) return
        for (r in 0 until 12) {
            val bits = d(k, 4 + r)
            for (c in 0 until 6) {
                val on = (bits shr (5 - c)) and 1 == 1
                val col = if (on) c1 else c0
                val at = (y + r) * W + x + c
                screen[at] = if (xor) (screen[at].toInt() xor col).toByte() else col.toByte()
            }
        }
        dirty = true
    }

    private fun scroll(k: Int, copy: Boolean) {
        val color = (d(k, 0) and 0x0F).toByte()
        val hs = d(k, 1)
        val vs = d(k, 2)
        val hCmd = (hs and 0x30) shr 4
        val vCmd = (vs and 0x30) shr 4
        hOff = minOf(hs and 0x07, 5)
        vOff = minOf(vs and 0x0F, 11)
        val old = screen.copyOf()
        if (hCmd == 1 || hCmd == 2) {
            val shift = if (hCmd == 1) 6 else -6
            for (y in 0 until H) for (x in 0 until W) {
                var sx = x - shift
                val outside = sx < 0 || sx >= W
                sx = ((sx % W) + W) % W
                screen[y * W + x] = if (outside && !copy) color else old[y * W + sx]
            }
        }
        if (vCmd == 1 || vCmd == 2) {
            val src = screen.copyOf()
            val shift = if (vCmd == 1) 12 else -12
            for (y in 0 until H) {
                var sy = y - shift
                val outside = sy < 0 || sy >= H
                sy = ((sy % H) + H) % H
                for (x in 0 until W) screen[y * W + x] = if (outside && !copy) color else src[sy * W + x]
            }
        }
        dirty = true
    }

    private fun apply(k: Int) {
        when (instr[k].toInt()) {
            6 -> tile(k, false)
            38 -> tile(k, true)
            1 -> if ((d(k, 1) and 0x0F) == 0) { screen.fill((d(k, 0) and 0x0F).toByte()); dirty = true }
            2 -> {
                val c = (d(k, 0) and 0x0F).toByte()
                for (y in 0 until H) for (x in 0 until W) if (y < 12 || y >= 204 || x < 6 || x >= 294) screen[y * W + x] = c
                dirty = true
            }
            20 -> scroll(k, false)
            24 -> scroll(k, true)
            30, 31 -> {
                val base = if (instr[k].toInt() == 31) 8 else 0
                for (i in 0 until 8) {
                    val hi = d(k, 2 * i); val lo = d(k, 2 * i + 1)
                    val r = (hi and 0x3C) shr 2
                    val g = ((hi and 0x03) shl 2) or ((lo and 0x30) shr 4)
                    val b = lo and 0x0F
                    palette[base + i] = (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
                }
                dirty = true
            }
        }
    }

    /** Apply packets up to [seconds]; true if the picture changed. */
    fun advanceTo(seconds: Double): Boolean {
        val target = (maxOf(0.0, seconds) * PER_SEC).toInt()
        var lo = 0
        var hi = index.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (index[m] <= target) lo = m + 1 else hi = m }
        val stop = lo
        if (stop < pos) reset()
        var changed = dirty
        while (pos < stop) { apply(pos); pos++; changed = true }
        return changed
    }

    /** Fill [pixels] with the current picture (smooth-scroll offsets applied). */
    fun render(): IntArray {
        dirty = false
        for (y in 0 until H) {
            val sy = (y + vOff) % H
            for (x in 0 until W) {
                val sx = (x + hOff) % W
                pixels[y * W + x] = palette[screen[sy * W + sx].toInt() and 0x0F]
            }
        }
        return pixels
    }
}
