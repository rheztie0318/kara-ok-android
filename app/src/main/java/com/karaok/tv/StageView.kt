package com.karaok.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.karaok.engine.Lyrics
import com.karaok.engine.LyricState
import com.karaok.engine.Song
import kotlin.math.min

/** Everything drawn on the TV: lyrics, CD+G graphics, now playing, overlays and the QR code. */
class StageView(context: Context) : View(context) {
    companion object {
        const val VIDEOKE = "Videoke (2 lines)"
        const val SCROLLING = "Scrolling (4 lines)"
        val HL = Color.rgb(255, 210, 63)
        val HL2 = Color.rgb(255, 79, 163)
        val MUTE = Color.rgb(155, 151, 187)
        val TEXT = Color.rgb(238, 236, 248)
        val BG = Color.rgb(10, 9, 20)
    }

    sealed class Overlay {
        class Loading(val name: String, val progress: Float) : Overlay()
        class UpNext(val singer: String, val song: String, val count: Int) : Overlay()
        class DrumRoll(val singer: String) : Overlay()
        class Score(val singer: String, val shown: String, val message: String) : Overlay()
    }

    var idle = true
    var videoShowing = false
    var song: Song? = null
    var singer = ""
    var nextUp = ""
    var lyrics: Lyrics? = null
    var lyricState = LyricState(-1, 0f, false, 0)
    var lyricStyle = VIDEOKE
    var progress = 0f
    var cdg: Bitmap? = null
    var qr: Bitmap? = null
    var qrUrl = ""
    var showQr = false
    var notice = ""
    var overlay: Overlay? = null
    var hint = ""

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bgShader: Shader? = null
    private var shadeShader: Shader? = null
    private var lastW = 0
    private var lastH = 0

    init { setWillNotDraw(false) }

    private fun shaders(w: Int, h: Int) {
        if (w == lastW && h == lastH) return
        lastW = w; lastH = h
        bgShader = RadialGradient(w / 2f, h * 1.2f, maxOf(w, h) * 0.95f,
            intArrayOf(Color.rgb(42, 20, 80), BG), floatArrayOf(0f, 0.6f), Shader.TileMode.CLAMP)
        shadeShader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
            Color.argb(0, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    /** Draw text fitted to [maxW]; align: 0 centre, -1 left, 1 right. Returns the width used. */
    private fun say(
        c: Canvas, s: String, cy: Float, size: Float, color: Int, alpha: Float = 1f,
        wipe: Float = -1f, maxW: Float = width * 0.9f, align: Int = 0,
    ): Float {
        if (s.isEmpty()) return 0f
        var sz = size
        text.textSize = sz
        var tw = text.measureText(s)
        while (tw > maxW && sz > 16f) { sz *= 0.92f; text.textSize = sz; tw = text.measureText(s) }
        var str = s
        if (tw > maxW) {
            val n = text.breakText(s, true, maxW - text.measureText("…"), null)
            str = s.substring(0, n) + "…"
            tw = text.measureText(str)
        }
        stroke.textSize = sz
        stroke.strokeWidth = sz * 0.12f
        val x = when (align) { -1 -> width * 0.06f; 1 -> width * 0.94f - tw; else -> (width - tw) / 2f }
        val fm = text.fontMetrics
        val y = cy - (fm.ascent + fm.descent) / 2
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        stroke.alpha = a
        c.drawText(str, x, y, stroke)
        text.color = color; text.alpha = a
        c.drawText(str, x, y, text)
        if (wipe > 0f) {
            c.save()
            c.clipRect(x - 4, cy - sz, x + tw * wipe + 2, cy + sz)
            text.color = HL; text.alpha = a
            c.drawText(str, x, y, text)
            c.restore()
        }
        return tw
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        shaders(width, height)
        val unit = min(w / 16f, h / 9f)
        val cdgBmp = cdg
        when {
            cdgBmp != null && !idle -> {
                c.drawColor(Color.BLACK)
                val s = min(w / cdgBmp.width, h / cdgBmp.height)
                val bw = cdgBmp.width * s; val bh = cdgBmp.height * s
                c.drawBitmap(cdgBmp, null, RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), bmpPaint)
            }
            videoShowing && !idle -> {
                c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                fill.shader = shadeShader
                c.drawRect(0f, h * 0.45f, w, h, fill)
                fill.shader = null
            }
            else -> {
                fill.shader = bgShader
                c.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
            }
        }
        if (idle) {
            say(c, "Kara-OK", h * 0.36f, unit * 1.6f, HL)
            say(c, "Press OK or MENU for songs · number keys reserve by song number", h * 0.50f, unit * 0.32f, MUTE)
            say(c, "Phones: scan the QR code to reserve songs and upload new ones", h * 0.56f, unit * 0.32f, MUTE)
            if (hint.isNotEmpty()) say(c, hint, h * 0.64f, unit * 0.3f, HL2)
        } else {
            if (cdgBmp == null) {
                drawNowBar(c, w, unit)
                drawLyrics(c, w, h, unit)
            }
            fill.color = HL2
            c.drawRect(0f, 0f, w * progress, 4f, fill)
        }
        if (showQr || idle) drawQr(c, w, h, unit)
        if (notice.isNotEmpty()) say(c, notice, unit * 1.1f, unit * 0.42f, HL2)
        overlay?.let { drawOverlay(c, w, h, unit, it) }
    }

    private fun drawNowBar(c: Canvas, w: Float, unit: Float) {
        val s = song ?: return
        val m = unit * 0.35f
        text.textSize = unit * 0.3f
        text.color = TEXT; text.alpha = 255
        val y = m + unit * 0.3f
        if (singer.isNotEmpty()) c.drawText("🎤 $singer", m, y, text)
        val right = "${s.number}  ${s.title}"
        c.drawText(right, w - m - text.measureText(right), y, text)
        if (nextUp.isNotEmpty()) {
            text.textSize = unit * 0.24f
            text.color = MUTE
            val n = "Next: $nextUp"
            c.drawText(n, w - m - text.measureText(n), y + unit * 0.42f, text)
        }
    }

    private fun drawLyrics(c: Canvas, w: Float, h: Float, unit: Float) {
        val s = song ?: return
        val lyr = lyrics
        if (lyr == null) {
            say(c, s.title, h * 0.76f, unit * 0.9f, TEXT)
            if (s.artist.isNotEmpty()) say(c, s.artist, h * 0.85f, unit * 0.45f, MUTE)
            return
        }
        if (!lyr.synced) {
            val lh = unit * 0.75f
            val top = h * 0.5f - progress * lyr.lines.size * lh
            for ((k, line) in lyr.lines.withIndex()) {
                val cy = top + k * lh
                if (cy < -lh || cy > h + lh) continue
                val dist = Math.abs(cy - h * 0.5f) / (h * 0.5f)
                say(c, line.second, cy, unit * 0.5f, TEXT, maxOf(0.08f, 1f - dist * dist))
            }
            return
        }
        val st = lyricState
        val i = st.index
        val lines = lyr.lines
        fun txt(k: Int) = if (k in lines.indices) lines[k].second.ifEmpty { "♪" } else ""
        if (lyricStyle == VIDEOKE) {
            val size = unit * 0.9f
            val maxW = w * 0.86f
            val yTop = h - unit * 2.35f
            val yBot = h - unit * 1.05f
            val (top, bot) = when {
                i < 0 -> 0 to 1
                i % 2 == 0 -> i to i + 1
                else -> i + 1 to i
            }
            for ((k, y, align) in listOf(Triple(top, yTop, -1), Triple(bot, yBot, 1))) {
                val t = txt(k)
                if (t.isEmpty()) continue
                val wipe = if (k == i) (if (lines[k].second.isNotEmpty()) st.wipe else 1f) else -1f
                say(c, t, y, size, Color.WHITE, 0.97f, wipe, maxW, align)
            }
            if (i < 0) {
                say(c, s.title, h * 0.42f, unit * 0.9f, HL, 0.95f)
                if (s.artist.isNotEmpty()) say(c, s.artist, h * 0.52f, unit * 0.45f, MUTE)
            }
            if (st.dots > 0) say(c, "●  ".repeat(st.dots - 1) + "●", yTop - unit * 0.95f, unit * 0.45f, HL2, align = -1)
            return
        }
        val sCur = unit * 0.95f; val sNext = unit * 0.68f; val sSmall = unit * 0.5f
        val yN2 = h - unit * 0.6f - sSmall * 0.5f
        val yN1 = yN2 - sSmall * 0.75f - sNext * 0.75f
        val yCur = yN1 - sNext * 0.7f - sCur * 0.75f
        val yPrev = yCur - sCur * 0.75f - sSmall * 0.75f
        if (i < 0) {
            say(c, s.title, yCur, sCur, TEXT, 0.9f)
            say(c, txt(0), yN1, sNext, TEXT, 0.85f)
            say(c, txt(1), yN2, sSmall, TEXT, 0.45f)
        } else {
            say(c, txt(i - 1), yPrev, sSmall, TEXT, 0.35f)
            say(c, txt(i), yCur, sCur, Color.WHITE, 1f, if (lines[i].second.isNotEmpty()) st.wipe else 1f)
            say(c, txt(i + 1), yN1, sNext, TEXT, 0.85f)
            say(c, txt(i + 2), yN2, sSmall, TEXT, 0.45f)
        }
        if (st.dots > 0) say(c, "●  ".repeat(st.dots - 1) + "●", yPrev - sSmall * 1.1f, unit * 0.45f, HL2)
    }

    private fun drawQr(c: Canvas, w: Float, h: Float, unit: Float) {
        val q = qr ?: return
        val size = unit * 2.1f
        val x0 = w - size - unit * 0.35f
        val y0 = if (idle) h - size - unit * 0.9f else unit * 1.5f
        fill.color = Color.WHITE
        c.drawRoundRect(RectF(x0 - 8, y0 - 8, x0 + size + 8, y0 + size + 8), 10f, 10f, fill)
        c.drawBitmap(q, null, RectF(x0, y0, x0 + size, y0 + size), null)
        text.textSize = unit * 0.2f
        text.color = TEXT; text.alpha = 255
        val label = "📱 $qrUrl"
        c.drawText(label, x0 + size - text.measureText(label), y0 + size + unit * 0.42f, text)
    }

    private fun drawOverlay(c: Canvas, w: Float, h: Float, unit: Float, o: Overlay) {
        fill.color = Color.argb(232, 10, 9, 20)
        c.drawRect(0f, 0f, w, h, fill)
        when (o) {
            is Overlay.Loading -> {
                val pct = if (o.progress > 0f) "  ${(o.progress * 100).toInt()}%" else ""
                say(c, "Preparing song…$pct", h * 0.45f, unit * 0.7f, TEXT)
                say(c, o.name, h * 0.56f, unit * 0.4f, MUTE)
            }
            is Overlay.UpNext -> {
                say(c, "NOW SINGING", h * 0.25f, unit * 0.32f, MUTE)
                say(c, o.singer, h * 0.38f, unit * 1.2f, HL)
                say(c, o.song, h * 0.52f, unit * 0.6f, TEXT)
                say(c, if (o.count > 0) o.count.toString() else "♪", h * 0.7f, unit * 1.6f, HL2)
                say(c, "press OK to start now", h * 0.86f, unit * 0.25f, MUTE)
            }
            is Overlay.DrumRoll -> {
                say(c, "${o.singer}'s score".uppercase(), h * 0.34f, unit * 0.4f, MUTE)
                say(c, "🥁 . . .", h * 0.5f, unit * 1.2f, HL2)
            }
            is Overlay.Score -> {
                say(c, "${o.singer} scored".uppercase(), h * 0.3f, unit * 0.36f, MUTE)
                say(c, o.shown, h * 0.48f, unit * 2.0f, HL2)
                say(c, o.message, h * 0.66f, unit * 0.7f, TEXT)
            }
        }
    }
}
