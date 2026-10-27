package com.karaok.tv

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.karaok.engine.VocalCut
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Removes centre-panned vocals from stereo 16-bit audio inside ExoPlayer. */
@UnstableApi
class VocalCutProcessor : BaseAudioProcessor() {
    @Volatile var amount = 0f
    private var cut: VocalCut? = null
    private val frame = FloatArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET     // leave other formats untouched
        }
        cut = VocalCut(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val vc = cut
        val amt = amount
        if (vc == null || amt <= 0.001f) {
            out.put(inp)
        } else {
            vc.amount = amt
            while (inp.remaining() >= 4) {
                val l = inp.short / 32768f
                val r = inp.short / 32768f
                vc.process(l, r, frame)
                out.putShort((frame[0].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                out.putShort((frame[1].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            inp.position(inp.limit())
        }
        out.flip()
    }

    override fun onFlush() {
        cut?.reset()
    }
}

@UnstableApi
private class KaraRenderersFactory(context: Context, private val proc: VocalCutProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf<AudioProcessor>(proc))
        .build()
}

/** The song player: ExoPlayer with key (pitch) and tempo control plus vocal cut. */
@UnstableApi
class KaraPlayer(context: Context, private val listener: Listener) {
    interface Listener {
        fun onEnded()
        fun onHasVideo(hasVideo: Boolean)
        fun onVideoSize(width: Int, height: Int, ratio: Float)
        fun onError(message: String)
    }

    val vocal = VocalCutProcessor()
    val player: ExoPlayer = ExoPlayer.Builder(context, KaraRenderersFactory(context, vocal)).build()
    private var surface: SurfaceView? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener.onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                listener.onHasVideo(tracks.containsType(C.TRACK_TYPE_VIDEO))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    listener.onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener.onError(error.message ?: "Playback error")
            }
        })
    }

    fun attachSurface(sv: SurfaceView?) {
        surface?.let { player.clearVideoSurfaceView(it) }
        surface = sv
        sv?.let { player.setVideoSurfaceView(it) }
    }

    /** Load a file; playback starts when [start] is called (or right away if [autoplay]). */
    fun load(file: File, autoplay: Boolean = false) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = autoplay
    }

    /** Switch to another rendering of the same song (guide melody on/off) without losing the place. */
    fun swap(file: File) {
        val pos = player.currentPosition
        val playing = player.playWhenReady
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), pos)
        player.prepare()
        player.playWhenReady = playing
    }

    fun start() {
        player.seekTo(0)
        player.playWhenReady = true
    }

    fun setKeyTempo(semitones: Int, tempo: Float) {
        player.playbackParameters = PlaybackParameters(tempo, 2.0.pow(semitones / 12.0).toFloat())
    }

    val positionSec: Double get() = player.currentPosition / 1000.0
    val durationSec: Double get() = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0.0 else it / 1000.0 }
    val isPlaying: Boolean get() = player.playWhenReady && player.playbackState == Player.STATE_READY
    val isPlayingOrBuffering: Boolean get() = player.playWhenReady

    fun toggle() { player.playWhenReady = !player.playWhenReady }
    fun pause() { player.playWhenReady = false }
    fun resume() { player.playWhenReady = true }
    fun seekTo(sec: Double) = player.seekTo((sec.coerceAtLeast(0.0) * 1000).toLong())
    fun stop() { player.stop(); player.clearMediaItems() }
    fun release() = player.release()
}

/** Muted looping background video for songs that have no video of their own. */
@UnstableApi
class BackgroundPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ALL
    }
    private var surface: SurfaceView? = null
    var active = false
        private set

    fun start(files: List<File>, sv: SurfaceView) {
        if (files.isEmpty()) return
        surface = sv
        player.setVideoSurfaceView(sv)
        player.setMediaItems(files.shuffled().map { MediaItem.fromUri(Uri.fromFile(it)) })
        player.prepare()
        player.playWhenReady = true
        active = true
    }

    fun pause() { player.playWhenReady = false }
    fun resume() { if (active) player.playWhenReady = true }

    fun stop() {
        if (!active) return
        player.stop()
        surface?.let { player.clearVideoSurfaceView(it) }
        surface = null
        active = false
    }

    fun release() = player.release()
}
