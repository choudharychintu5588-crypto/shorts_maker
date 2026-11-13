package com.shortsmaker.proof

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * The whole YouTube-link workflow after the link is resolved: ranged download (with resume and errors), picture+sound
 * join, speech recognition, moment selection, vertical rendering with captions, ZIP. YouTube itself is replaced by a
 * local web server that serves the same kind of separate picture / sound files.
 */
@UnstableApi
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipelineTest {
    companion object {
        private const val TAG = "PIPE"
        private val inst = InstrumentationRegistry.getInstrumentation()
        private val app get() = inst.targetContext
        private lateinit var server: MockWebServer
        private lateinit var videoBytes: ByteArray
        private lateinit var audioBytes: ByteArray
        private val hits = ArrayList<String>()
        @Volatile var failNextWith403 = 0
        lateinit var result: JobResult

        private fun asset(name: String): ByteArray = inst.context.assets.open(name).use { it.readBytes() }
        fun note(s: String) { Log.i(TAG, s) }

        @BeforeClass @JvmStatic fun setup() {
            videoBytes = asset("video_only.mp4")
            audioBytes = asset("audio_only.m4a")
            // speech model where the app expects it
            val m = ModelStore.file(app, "tiny")
            inst.context.assets.open("model.bin").use { i -> FileOutputStream(m).use { o -> i.copyTo(o) } }
            server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data = when {
                        request.path!!.startsWith("/video") -> videoBytes
                        request.path!!.startsWith("/audio") -> audioBytes
                        else -> return MockResponse().setResponseCode(404)
                    }
                    synchronized(hits) { hits.add("${request.path} ${request.getHeader("Range")}") }
                    if (failNextWith403 > 0) { failNextWith403--; return MockResponse().setResponseCode(403) }
                    val range = request.getHeader("Range")
                    val r = Regex("bytes=(\\d+)-(\\d+)").find(range ?: "")
                    return if (r != null) {
                        val a = r.groupValues[1].toInt()
                        val b = minOf(r.groupValues[2].toInt(), data.size - 1)
                        if (a >= data.size) return MockResponse().setResponseCode(416)
                        MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes $a-$b/${data.size}")
                            .setBody(Buffer().write(data, a, b - a + 1))
                    } else MockResponse().setResponseCode(200).setBody(Buffer().write(data))
                }
            }
            server.start()
        }

        @AfterClass @JvmStatic fun teardown() { try { server.shutdown() } catch (_: Exception) {} }

        fun choices(): Pair<StreamChoice, StreamChoice> = StreamChoice(server.url("/video.mp4").toString(), 720, "avc1.640028", 1_000_000, "mp4", videoBytes.size.toLong()) to
            StreamChoice(server.url("/audio.m4a").toString(), 0, "mp4a.40.2", 128_000, "m4a", audioBytes.size.toLong())
    }

    private fun work(name: String) = File(app.cacheDir, "ptest/$name").apply { deleteRecursively(); mkdirs() }

    @Test fun t1_rangedDownloadAndJoin() {
        val (v, a) = choices()
        val dir = work("t1")
        val out = File(dir, "source.mp4")
        synchronized(hits) { hits.clear() }
        YouTubeSource().downloadAndMerge(Http.client, v, a, dir, out, { false }) { _, _, _ -> }
        val h = synchronized(hits) { ArrayList(hits) }
        note("download requests: ${h.size}")
        assertTrue("download should use several ranged requests", h.size >= 2 && h.all { it.contains("bytes=") })
        val info = VideoInfo.read(app, Uri.fromFile(out))
        note("joined: ${info.displayWidth}x${info.displayHeight} ${info.durationMs}ms")
        assertEquals(1280, info.displayWidth)
        assertTrue("duration ${info.durationMs}", info.durationMs in 70_000..80_000)
        val ex = MediaExtractor(); ex.setDataSource(out.absolutePath)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString("mime")!! }
        ex.release()
        assertTrue("joined file needs picture and sound: $mimes", mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") })
        // the sound must really be there and not silent
        val pcm = File(dir, "a.pcm")
        val dec = AudioDecoder.decodeToPcm16k(app, Uri.fromFile(out), pcm)
        val e = AudioEnergy.rmsWindows(pcm)
        note("joined audio ${dec.seconds}s, loudest window ${e.maxOrNull()}")
        assertTrue(dec.seconds in 70.0..80.0)
        assertTrue("audio must not be silent", (e.maxOrNull() ?: 0f) > 0.02f)
    }

    @Test fun t2_resumeAfterInterruptionAndErrors() {
        val (v, _) = choices()
        val dir = work("t2")
        val dest = File(dir, "v.mp4")
        // pretend a previous attempt got half of the file
        File(dest.path + ".part").writeBytes(videoBytes.copyOfRange(0, videoBytes.size / 2))
        synchronized(hits) { hits.clear() }
        StreamDownloader.download(Http.client, v.url, dest, v.contentLength, { false }) { _, _ -> }
        assertTrue("resumed file must be identical", dest.readBytes().contentEquals(videoBytes))
        assertTrue("first request must start at the resume point", synchronized(hits) { hits.first() }.contains("bytes=${videoBytes.size / 2}-"))
        // 403 on the first try: switches to the other range style and still completes
        failNextWith403 = 1
        val dest2 = File(dir, "v2.mp4")
        StreamDownloader.download(Http.client, v.url, dest2, v.contentLength, { false }) { _, _ -> }
        assertTrue(dest2.readBytes().contentEquals(videoBytes))
        // permanent 403 -> clear message, no crash
        failNextWith403 = 100
        try {
            StreamDownloader.download(Http.client, v.url, File(dir, "v3.mp4"), v.contentLength, { false }) { _, _ -> }
            fail("403 should fail")
        } catch (e: ProcessingException) { note("403 message: ${e.message}") } finally { failNextWith403 = 0 }
        // cancel
        try {
            StreamDownloader.download(Http.client, v.url, File(dir, "v4.mp4"), v.contentLength, { true }) { _, _ -> }
            fail("cancel ignored")
        } catch (e: CancelledException) { note("download cancel works") }
        // broken files
        try { Mux.merge(File(dir, "v.mp4"), File(dir, "v.mp4"), File(dir, "bad.mp4")); fail("two picture files accepted") }
        catch (e: ProcessingException) { note("bad join rejected: ${e.message}") }
    }

    @Test fun t3_fullWorkflowGeneratesShortsAndZip() {
        val (v, a) = choices()
        val fake = object : VideoSource {
            override suspend fun fetch(input: String, workDir: File, maxHeight: Int, cancelled: () -> Boolean,
                                       onProgress: (Stage, Float, String) -> Unit): SourceVideo {
                val out = File(workDir, "source.mp4")
                YouTubeSource().downloadAndMerge(Http.client, v, a, workDir, out, cancelled, onProgress)
                return SourceVideo(Uri.fromFile(out), "Test video", 78, "aaaaaaaaaaa")
            }
        }
        val settings = Settings(clipCount = 3, minSec = 15, maxSec = 25, language = "en", model = "tiny", layout = Layout.FILL, hinglish = false)
        val stages = LinkedHashSet<Stage>()
        val t0 = System.nanoTime()
        result = runBlocking {
            ShortsPipeline(app, fake).run("https://www.youtube.com/watch?v=aaaaaaaaaaa", settings) { st, f, msg ->
                if (stages.add(st)) note("stage ${st.name}: $msg")
            }
        }
        note("pipeline finished in ${(System.nanoTime() - t0) / 1e9} s with ${result.shorts.size} shorts; notes=${result.notes}")
        assertEquals("all stages reported", Stage.values().toSet(), stages)
        assertTrue("expected at least 2 Shorts, got ${result.shorts.size}", result.shorts.size >= 2)

        var prevEnd = -1L
        for (s in result.shorts.sortedBy { it.startMs }) {
            assertTrue("Shorts must not overlap", s.startMs >= prevEnd); prevEnd = s.endMs
            assertTrue("Short ${s.index} length ${s.durationSec}s", s.durationSec in 9..28)
            val r = MediaMetadataRetriever()
            r.setDataSource(s.file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            r.release()
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            note("short ${s.index}: ${dw}x$dh ${dur}ms score=${s.score} '${s.title}' ${s.hashtags}")
            assertEquals(1080, dw); assertEquals(1920, dh)
            assertTrue("rendered length ${dur}ms vs ${s.endMs - s.startMs}", Math.abs(dur - (s.endMs - s.startMs)) < 800)
            val ex = MediaExtractor(); ex.setDataSource(s.file.absolutePath)
            assertTrue("audio present", (0 until ex.trackCount).any { ex.getTrackFormat(it).getString("mime")!!.startsWith("audio/") })
            ex.release()
            assertTrue(s.title.isNotBlank() && s.hashtags.contains("#shorts") && s.description.isNotBlank())
            assertTrue("transcript text must be speech from the video: ${s.transcript}", s.transcript.lowercase().contains("country"))
            // captions are burnt in: some yellow highlight pixels somewhere during the clip
            val rr = MediaMetadataRetriever(); rr.setDataSource(s.file.absolutePath)
            var seen = 0
            for (sec in listOf(2L, 4L, 6L, 8L)) {
                val bmp = rr.getFrameAtTime(sec * 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                if (yellow(bmp) > 100) seen++
            }
            rr.release()
            assertTrue("burnt-in captions expected in Short ${s.index}", seen >= 1)
        }

        // ZIP holds exactly the shorts + info file, byte for byte
        ZipFile(result.zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            note("zip entries: $names (${result.zip.length() / 1024} KB)")
            for (s in result.shorts) {
                val e = z.getEntry(s.file.name)
                assertTrue("zip has ${s.file.name}", e != null)
                val bytes = z.getInputStream(e).use { it.readBytes() }
                assertTrue("zip copy of ${s.file.name} identical", bytes.contentEquals(s.file.readBytes()))
            }
            assertTrue(names.contains("shorts_info.csv"))
            val csv = z.getInputStream(z.getEntry("shorts_info.csv")).use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(csv.lines().size >= result.shorts.size + 1)
        }
        // result survives an app restart
        val again = JobResult.load(result.dir)
        assertTrue("saved result can be reloaded", again != null && again.shorts.size == result.shorts.size)
        // temporary work files are cleaned
        assertTrue(!File(app.cacheDir, "work/aaaaaaaaaaa").exists())
    }

    private fun yellow(b: Bitmap): Int {
        var n = 0
        for (y in 1100 until 1800 step 2) for (x in 0 until b.width step 2) {
            val p = b.getPixel(x, y)
            if (((p shr 16) and 255) > 200 && ((p shr 8) and 255) > 180 && (p and 255) < 90) n++
        }
        return n
    }

    @Test fun t4_pipelineReportsBadLinksClearly() {
        val settings = Settings(model = "tiny")
        for (bad in listOf("not a link", "https://example.com/x", "https://www.youtube.com/playlist?list=PLxyz")) {
            try {
                runBlocking { ShortsPipeline(app).run(bad, settings) { _, _, _ -> } }
                fail("accepted $bad")
            } catch (e: ProcessingException) {
                note("bad link '$bad' -> ${e.message}")
                assertTrue(e.message!!.contains("YouTube"))
            }
        }
    }
}
