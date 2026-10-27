package com.karaok.tv

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.karaok.engine.MicFx
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live microphone through the speakers with echo, delay and reverb.
 * Works with USB mics on most TV boxes. Some boxes add a noticeable delay; a wireless mic
 * receiver with its own echo is the alternative there.
 */
class MicEngine(private val sampleRate: Int = 44100) {
    val fx = MicFx(sampleRate)
    @Volatile var level = 0f
        private set
    @Volatile var running = false
        private set
    private var thread: Thread? = null

    /** Returns an error message, or null when the mic is running. */
    @Suppress("DEPRECATION")
    fun start(): String? {
        if (running) return null
        val inMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (inMin <= 0 || outMin <= 0) return "This device can't record audio at $sampleRate Hz."
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(inMin, 2048))
        } catch (e: Exception) {
            return "No microphone found: ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "No microphone found. Plug in a USB mic and try again."
        }
        val track = try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(outMin)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, outMin, AudioTrack.MODE_STREAM)
            }
        } catch (e: Exception) {
            rec.release()
            return "Couldn't open the speakers for the mic: ${e.message}"
        }
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(256)
            try {
                rec.startRecording()
                track.play()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val x = buf[i] / 32768f
                        sum += x * x
                        val y = fx.process(x).coerceIn(-1f, 1f)
                        buf[i] = (y * 32767f).toInt().toShort()
                    }
                    level = sqrt(sum / n).toFloat()
                    track.write(buf, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                try { track.stop() } catch (_: Exception) {}
                rec.release(); track.release()
                level = 0f
                running = false
            }
        }, "kara-mic").apply { start() }
        return null
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
