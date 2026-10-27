package com.karaok.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 2nd-order Butterworth filter section (state kept between blocks). */
class Biquad(sr: Int, freq: Double, highpass: Boolean) {
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    init {
        val w = 2 * PI * freq / sr
        val alpha = sin(w) / (2 * sqrt(0.5))
        val cw = cos(w)
        val a0 = 1 + alpha
        if (highpass) { b0 = (1 + cw) / 2 / a0; b1 = -(1 + cw) / a0 } else { b0 = (1 - cw) / 2 / a0; b1 = (1 - cw) / a0 }
        b2 = b0; a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
}

/**
 * Vocal cut for normal recordings: lead vocals sit in the centre, so left minus right removes them.
 * The bass (also centred) is kept from the original. [amount] 0 = off, 1 = full cut.
 */
class VocalCut(sr: Int) {
    @Volatile var amount = 0f
    private val hp1 = Biquad(sr, 140.0, true); private val hp2 = Biquad(sr, 140.0, true)
    private val lp1 = Biquad(sr, 140.0, false); private val lp2 = Biquad(sr, 140.0, false)

    /** Process interleaved stereo frames in place (values -1..1). */
    fun process(l: Float, r: Float, out: FloatArray) {
        val side = hp2.process(hp1.process((l - r).toDouble()))
        val mid = lp2.process(lp1.process(((l + r) * 0.5).toDouble()))
        val v = amount
        val kara = (side * 0.9 + mid).toFloat()
        out[0] = l * (1 - v) + kara * v
        out[1] = r * (1 - v) + kara * v
    }

    fun reset() { hp1.reset(); hp2.reset(); lp1.reset(); lp2.reset() }
}

/** Live mic effects: echo with adjustable delay time, plus a small reverb. */
class MicFx(private val sr: Int) {
    @Volatile var echo = 0.45f
    @Volatile var reverb = 0.30f
    @Volatile var gain = 1.0f
    @Volatile var delaySec = 0.26f

    private val echoBuf = FloatArray((0.8 * sr).toInt() + 1)
    private var echoPos = 0
    private val combs = listOf(0.0297, 0.0371, 0.0411, 0.0437).map { FloatArray((it * sr).toInt()) }
    private val combG = floatArrayOf(0.80f, 0.78f, 0.76f, 0.74f)
    private val combI = IntArray(4)
    private val aps = listOf(0.0116, 0.0134).map { FloatArray((it * sr).toInt()) }
    private val apI = IntArray(2)

    fun process(x0: Float): Float {
        val x = x0 * gain
        // echo: feedback delay line
        val d = (delaySec.coerceIn(0.06f, 0.8f) * sr).toInt()
        var rp = echoPos - d
        if (rp < 0) rp += echoBuf.size
        val past = echoBuf[rp]
        echoBuf[echoPos] = x + 0.45f * echo * past
        echoPos = if (echoPos + 1 == echoBuf.size) 0 else echoPos + 1
        // reverb
        var wet = 0f
        for (k in 0 until 4) {
            val buf = combs[k]; val i = combI[k]
            val p = buf[i]
            buf[i] = x + combG[k] * p
            combI[k] = if (i + 1 == buf.size) 0 else i + 1
            wet += p
        }
        wet *= 0.25f
        for (k in 0 until 2) {
            val buf = aps[k]; val i = apI[k]
            val p = buf[i]
            val v = wet + 0.6f * p
            buf[i] = v
            apI[k] = if (i + 1 == buf.size) 0 else i + 1
            wet = -0.6f * v + p
        }
        return x + past * 0.6f * echo + wet * 0.9f * reverb
    }
}
