package com.shortsmaker.proof

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import android.media.MediaCodecInfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Layout { FILL, BLUR }

/** Renders one 1080x1920 MP4 Short from [startMs, endMs) of a video, with burnt-in captions. */
@UnstableApi
class ClipRenderer(private val context: Context) {
    companion object {
        const val OUT_W = 1080
        const val OUT_H = 1920
    }

    /** Set only by tests: the size the FIRST attempt asks for, to imitate a phone encoder that rounds 1080 up to 1088. */
    @Volatile var testFirstAttemptWidth: Int? = null
    /** Filled in for diagnostics / tests: what happened on each attempt. */
    val attemptLog = ArrayList<String>()

    /**
     * Phone hardware encoders often only accept widths that are multiples of 16, so a request for 1080 comes out as
     * 1088 (Media3 rounds up to what the chip supports). So: attempt 1 asks the hardware encoder for exactly 1080x1920
     * (no automatic size change); if it refuses or the file is not exactly 1080x1920, attempt 2 uses the software H.264
     * encoder, which has no such alignment limit. A file of any other size is never accepted.
     */
    suspend fun render(
        source: Uri, info: VideoInfo, startMs: Long, endMs: Long, words: List<Word>, layout: Layout,
        out: File, style: CaptionStyle = CaptionStyle(), onProgress: (Int) -> Unit = {},
    ) {
        attemptLog.clear()
        var lastError: Exception? = null
        for (software in listOf(false, true)) {
            val w = if (!software) (testFirstAttemptWidth ?: OUT_W) else OUT_W
            try {
                renderOnce(source, info, startMs, endMs, words, layout, out, style, w, OUT_H, software, onProgress)
                val c = VideoInfo.read(context, Uri.fromFile(out))
                attemptLog.add("${if (software) "software" else "hardware"} encoder: ${c.displayWidth}x${c.displayHeight}")
                if (c.displayWidth == OUT_W && c.displayHeight == OUT_H) return
                lastError = ProcessingException("Short came out at the wrong size (${c.displayWidth}x${c.displayHeight}).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                attemptLog.add("${if (software) "software" else "hardware"} encoder failed: ${e.message}")
                lastError = e
            }
            out.delete()
        }
        throw (lastError as? ProcessingException) ?: ProcessingException("Could not create the video.", lastError)
    }

    private suspend fun renderOnce(
        source: Uri, info: VideoInfo, startMs: Long, endMs: Long, words: List<Word>, layout: Layout,
        out: File, style: CaptionStyle, OUT_W: Int, OUT_H: Int, software: Boolean, onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        if (endMs <= startMs) throw ProcessingException("The clip has no length.")
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        val mediaItem = MediaItem.Builder().setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()

        val caption = CaptionOverlay(words, startMs, style, OUT_W, OUT_H)
        val captionEffect = OverlayEffect(ImmutableList.of<TextureOverlay>(caption))

        val sequences = ArrayList<EditedMediaItemSequence>()
        var compositorSettings: VideoCompositorSettings? = null
        when (layout) {
            Layout.FILL -> {
                val fx = Effects(emptyList(), listOf<Effect>(
                    Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))
                sequences.add(EditedMediaItemSequence.Builder(EditedMediaItem.Builder(mediaItem).setEffects(fx).build()).build())
            }
            Layout.BLUR -> {
                // Background (second sequence): same video zoomed to fill the frame and blurred. Foreground: whole picture, sharp, centred.
                // Blur at 1/4 size (16x cheaper, looks the same), then stretch back up to full size.
                val bgFx = Effects(emptyList(), listOf<Effect>(
                    Presentation.createForWidthAndHeight(OUT_W / 4, OUT_H / 4, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                    GaussianBlur(8f),
                    Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_STRETCH_TO_FIT)))
                val fgH = (OUT_W.toLong() * info.displayHeight / info.displayWidth).toInt().let { it - it % 2 }.coerceAtMost(OUT_H)
                val fgFx = Effects(emptyList(), listOf<Effect>(
                    Presentation.createForWidthAndHeight(OUT_W, fgH, Presentation.LAYOUT_SCALE_TO_FIT)))
                // The compositor draws the FIRST sequence on top, so the sharp foreground (with the audio) goes first.
                sequences.add(EditedMediaItemSequence.Builder(
                    EditedMediaItem.Builder(mediaItem).setEffects(fgFx).build()).build())
                sequences.add(EditedMediaItemSequence.Builder(
                    EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).setEffects(bgFx).build()).build())
                compositorSettings = object : VideoCompositorSettings {
                    override fun getOutputSize(inputSizes: List<Size>): Size = Size(OUT_W, OUT_H)
                    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                        object : OverlaySettings {}  // centred, natural size
                }
            }
        }

        val builder = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), listOf<Effect>(captionEffect)))
        compositorSettings?.let { builder.setVideoCompositorSettings(it) }
        val composition = builder.build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(6_000_000).build())
            .setEnableFallback(false)   // never let the encoder silently change the size
            .also { b ->
                if (software) b.setVideoEncoderSelector(EncoderSelector { mime ->
                    val all = EncoderSelector.DEFAULT.selectEncoderInfos(mime)
                    val sw = all.filter { it.isSoftwareOnly }
                    ImmutableList.copyOf(if (sw.isNotEmpty()) sw else all)
                })
            }
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacksAndMessages(null)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacksAndMessages(null)
                        if (cont.isActive) cont.resumeWithException(
                            ProcessingException("Could not create the video (${exportException.errorCodeName}).", exportException))
                    }
                })
                .build()
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(this, 400)
                }
            }
            cont.invokeOnCancellation { handler.post { handler.removeCallbacksAndMessages(null); transformer.cancel() } }
            try {
                transformer.start(composition, out.absolutePath)
                handler.postDelayed(poll, 400)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(ProcessingException("Could not start video creation.", e))
            }
        }
        if (!out.exists() || out.length() < 1000) throw ProcessingException("The video file was not created.")
    }
}
