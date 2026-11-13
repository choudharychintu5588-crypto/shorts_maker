package com.shortsmaker.proof

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream

/**
 * Real end-to-end checks on an Android emulator: decode, Whisper, caption render, audio/subtitle sync.
 * Test assets (model + test video) are placed in src/androidTest/assets by the CI workflow.
 */
@UnstableApi
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProofTest {
    companion object {
        private const val TAG = "PROOF"
        private val inst = InstrumentationRegistry.getInstrumentation()
        private val app get() = inst.targetContext
        private val testCtx get() = inst.context
        private lateinit var video: File
        private lateinit var modelFile: File
        private lateinit var transcript: Transcript
        private lateinit var info: VideoInfo
        private val report = StringBuilder()

        fun note(s: String) { Log.i(TAG, s); report.append(s).append('\n') }

        private fun copyAsset(name: String, to: File) {
            testCtx.assets.open(name).use { i -> FileOutputStream(to).use { o -> i.copyTo(o) } }
        }

        @BeforeClass @JvmStatic fun setup() {
            video = File(app.cacheDir, "test.mp4"); copyAsset("test.mp4", video)
            modelFile = File(app.cacheDir, "model.bin"); copyAsset("model.bin", modelFile)
            info = VideoInfo.read(app, Uri.fromFile(video))
            note("test video ${info.displayWidth}x${info.displayHeight} ${info.durationMs}ms")
        }

        fun outDir() = (app.getExternalFilesDir("proof") ?: app.cacheDir).apply { mkdirs() }
        fun saveReport() { File(outDir(), "report.txt").writeText(report.toString()) }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetter() || it == ' ' }

    @Test fun t1_transcribeEnglishSample() {
        assertEquals(null, WhisperNative.unsupportedReason())
        val pcm = File(app.cacheDir, "t1.pcm")
        val t0 = System.nanoTime()
        val audio = AudioDecoder.decodeToPcm16k(app, Uri.fromFile(video), pcm)
        note("decode: ${audio.seconds}s audio in ${(System.nanoTime() - t0) / 1e6} ms")
        assertTrue("decoded length ~11s, was ${audio.seconds}", audio.seconds in 9.5..12.5)

        WhisperModel(modelFile).use { m ->
            val t1 = System.nanoTime()
            transcript = m.transcribe(pcm, "en", WhisperModel.bestThreadCount()) { }
            note("transcribe: ${(System.nanoTime() - t1) / 1e6} ms")
        }
        note("language=${transcript.language} text=${transcript.fullText}")
        val text = norm(transcript.fullText)
        assertTrue("text should contain 'ask not what your country', was: $text", text.contains("ask not what your country"))
        val words = transcript.words
        assertTrue("word count ${words.size}", words.size >= 18)
        for (i in 1 until words.size) assertTrue("word times must not go backwards at $i", words[i].t0 >= words[i - 1].t0)
        assertTrue("last word before end of clip", words.last().t1 <= 12_000)
        saveReport()
    }

    @Test fun t2_cancelAndBrokenInputsFailCleanly() {
        // not a video
        val junk = File(app.cacheDir, "junk.mp4").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        try { VideoInfo.read(app, Uri.fromFile(junk)); fail("junk accepted") } catch (e: ProcessingException) { note("junk rejected: ${e.message}") }
        try { AudioDecoder.decodeToPcm16k(app, Uri.fromFile(junk), File(app.cacheDir, "j.pcm")); fail("junk decoded") }
        catch (e: ProcessingException) { note("junk audio rejected: ${e.message}") }
        // bad model file
        val badModel = File(app.cacheDir, "bad.bin").apply { writeBytes(ByteArray(2000) { 1 }) }
        try { WhisperModel(badModel).close(); fail("bad model accepted") } catch (e: ProcessingException) { note("bad model rejected: ${e.message}") }
        // cancellation during decode
        try {
            AudioDecoder.decodeToPcm16k(app, Uri.fromFile(video), File(app.cacheDir, "c.pcm"), cancelled = { true })
            fail("cancel ignored")
        } catch (e: CancelledException) { note("cancel works") }
        saveReport()
    }

    private fun yellowCount(b: Bitmap): Int {
        var n = 0
        for (y in 1100 until 1800 step 2) for (x in 0 until b.width step 2) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            if (r > 200 && g > 180 && bl < 90) n++
        }
        return n
    }

    private fun frameAt(file: File, ms: Long): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            return r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw AssertionError("no frame at $ms")
        } finally { r.release() }
    }

    private fun px(b: Bitmap, x: Int, y: Int): Triple<Int, Int, Int> {
        val p = b.getPixel(x, y); return Triple((p shr 16) and 255, (p shr 8) and 255, p and 255)
    }

    private fun savePng(b: Bitmap, name: String) {
        FileOutputStream(File(outDir(), name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(layout: Layout): File {
        val out = File(app.cacheDir, "out_${layout.name}.mp4")
        val end = info.durationMs - 200
        val t0 = System.nanoTime()
        runBlocking { ClipRenderer(app).render(Uri.fromFile(video), info, 0, end, transcript.words, layout, out) }
        note("render ${layout.name}: ${(System.nanoTime() - t0) / 1e6} ms, ${out.length() / 1024} KB")
        return out
    }

    private fun checkOutputBasics(out: File) {
        val r = MediaMetadataRetriever()
        r.setDataSource(out.absolutePath)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        note("${out.name}: ${w}x$h rot=$rot dur=${dur}ms")
        assertEquals(1080, if (rot == 90 || rot == 270) h else w)
        assertEquals(1920, if (rot == 90 || rot == 270) w else h)
        assertTrue("duration ${dur}ms vs ${info.durationMs - 200}", Math.abs(dur - (info.durationMs - 200)) < 700)
        val ex = MediaExtractor(); ex.setDataSource(out.absolutePath)
        var hasAudio = false
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString("mime")!!.startsWith("audio/")) hasAudio = true
        ex.release()
        assertTrue("output must contain audio", hasAudio)
    }

    @Test fun t3_renderFillWithCaptionsAndSync() {
        val out = render(Layout.FILL)
        checkOutputBasics(out)
        val words = transcript.words
        // captions on: middle of the 4th word must show yellow highlight
        val w = words[3]
        val tOn = (w.t0 + w.t1) / 2
        val on = frameAt(out, tOn); savePng(on, "fill_on.png")
        val yOn = yellowCount(on)
        // captions off: shortly after the last word
        val tOff = minOf(words.last().t1 + 450, info.durationMs - 300)
        val off = frameAt(out, tOff); savePng(off, "fill_off.png")
        val yOff = yellowCount(off)
        note("FILL yellow pixels: on=$yOn (t=${tOn}ms) off=$yOff (t=${tOff}ms)")
        assertTrue("caption should be visible while the word is spoken (yellow=$yOn)", yOn > 150)
        assertTrue("caption should be gone after the last word (yellow=$yOff)", yOff < 20)
        // fill layout: centre is the green picture
        val (r, g, b) = px(on, 540, 700)
        assertTrue("fill centre should be green, was $r,$g,$b", g > 120 && r < 90 && b < 90)

        // AUDIO <-> SUBTITLE SYNC: transcribe the rendered file's own audio and compare word times with the original
        val pcm = File(app.cacheDir, "out.pcm")
        val a = AudioDecoder.decodeToPcm16k(app, Uri.fromFile(out), pcm)
        WhisperModel(modelFile).use { m ->
            val t2 = m.transcribe(pcm, "en", WhisperModel.bestThreadCount()) { }
            val ow = t2.words
            assertTrue("re-transcription found words", ow.size >= 15)
            val shift = ow.first().t0 - words.first().t0
            val shiftEnd = ow.last().t0 - words.last().t0
            note("SYNC: first word shift=${shift}ms last word shift=${shiftEnd}ms (audio ${a.seconds}s)")
            assertTrue("audio must stay aligned to caption clock (first shift $shift ms)", Math.abs(shift) < 400)
            assertTrue("audio must stay aligned at the end ($shiftEnd ms)", Math.abs(shiftEnd) < 400)
        }
        saveReport()
    }

    @Test fun t4_renderBlurLayout() {
        val out = render(Layout.BLUR)
        checkOutputBasics(out)
        val words = transcript.words
        val w = words[3]
        val f = frameAt(out, (w.t0 + w.t1) / 2); savePng(f, "blur_on.png")
        // Source is a green picture with a 8px white frame; 1280x720 fitted into 1080 wide => 608 tall, centred (rows 656..1264).
        fun white(y: Int) = px(f, 540, y).let { it.first > 200 && it.second > 200 && it.third > 200 }
        val whiteRowsTop = (640..690).count { white(it) }
        val whiteRowsBottom = (1230..1280).count { white(it) }
        val (r, g, b) = px(f, 540, 300)
        note("BLUR: whiteTopEdgeRows=$whiteRowsTop whiteBottomEdgeRows=$whiteRowsBottom bg@300=$r,$g,$b")
        assertTrue("foreground picture must be centred with its sharp edge near row 656 (white rows=$whiteRowsTop)", whiteRowsTop in 1..20)
        assertTrue("foreground bottom edge near row 1264 (white rows=$whiteRowsBottom)", whiteRowsBottom in 1..20)
        assertTrue("blurred background must be present above the picture, was $r,$g,$b", g > 100 && r < 140 && b < 140)
        assertTrue("caption visible in blur layout", yellowCount(f) > 150)
        saveReport()
    }
}
