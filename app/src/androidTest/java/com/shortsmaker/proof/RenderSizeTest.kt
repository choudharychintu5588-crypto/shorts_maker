package com.shortsmaker.proof

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Regression test for "Short came out at the wrong size (1088x1920)": phone hardware encoders round the width up to a
 * multiple of 16. The first attempt is forced to 1088 wide (what such a phone produces); the renderer must reject
 * that file and deliver a file that is exactly 1080x1920 - checked on the real exported MP4, three independent ways.
 */
@UnstableApi
class RenderSizeTest {
    companion object {
        private val inst = InstrumentationRegistry.getInstrumentation()
        private val app get() = inst.targetContext
        private lateinit var video: File
        private lateinit var info: VideoInfo
        private fun note(s: String) = Log.i("SIZE", s)

        @BeforeClass @JvmStatic fun setup() {
            video = File(app.cacheDir, "size_src.mp4")
            inst.context.assets.open("test.mp4").use { i -> FileOutputStream(video).use { o -> i.copyTo(o) } }
            info = VideoInfo.read(app, Uri.fromFile(video))
        }
    }

    private fun exactSizeChecks(f: File) {
        // 1) container metadata
        val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        // 2) real decoded picture
        val bmp = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        r.release()
        // 3) track format
        val ex = MediaExtractor(); ex.setDataSource(f.absolutePath)
        var tw = 0; var th = 0; var hasAudio = false
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            if (mime.startsWith("video/")) { tw = fmt.getInteger(MediaFormat.KEY_WIDTH); th = fmt.getInteger(MediaFormat.KEY_HEIGHT) }
            if (mime.startsWith("audio/")) hasAudio = true
        }
        ex.release()
        val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
        note("exported ${f.name}: metadata ${w}x$h rot=$rot, track ${tw}x$th, frame ${bmp.width}x${bmp.height}, audio=$hasAudio")
        assertEquals("metadata width", 1080, dw); assertEquals("metadata height", 1920, dh)
        assertEquals("track width", 1080, tw); assertEquals("track height", 1920, th)
        assertEquals("decoded frame width", 1080, bmp.width); assertEquals("decoded frame height", 1920, bmp.height)
        assertTrue("audio must be kept", hasAudio)
    }

    private fun run(layout: Layout, simulate1088: Boolean, endMs: Long): File {
        val out = File(app.cacheDir, "size_${layout.name}_$simulate1088.mp4")
        val words = (0 until 6).map { Word("word$it", 300L + it * 500, 300L + it * 500 + 450) }
        val renderer = ClipRenderer(app)
        if (simulate1088) renderer.testFirstAttemptWidth = 1088
        runBlocking { renderer.render(Uri.fromFile(video), info, 0, endMs, words, layout, out) }
        note("${layout.name} simulate1088=$simulate1088 attempts=${renderer.attemptLog}")
        if (simulate1088) {
            assertTrue("the 1088-wide file must have been rejected: ${renderer.attemptLog}",
                renderer.attemptLog.size >= 2 && renderer.attemptLog.first().contains("1088x1920"))
        }
        return out
    }

    @Test fun fillLayoutIsExactEvenWhenFirstAttemptIs1088() = exactSizeChecks(run(Layout.FILL, true, 4000))
    @Test fun fillLayoutNormalRunIsExact() = exactSizeChecks(run(Layout.FILL, false, 4000))
    @Test fun blurLayoutIsExactEvenWhenFirstAttemptIs1088() = exactSizeChecks(run(Layout.BLUR, true, 3000))
}
