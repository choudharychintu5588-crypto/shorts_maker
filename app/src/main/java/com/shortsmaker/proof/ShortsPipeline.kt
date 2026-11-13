package com.shortsmaker.proof

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class Stage(val label: String, val weight: Float) {
    LINK("Reading the link", 0.02f),
    DOWNLOAD("Downloading the video", 0.18f),
    AUDIO("Preparing the sound", 0.05f),
    SPEECH("Listening to the speech", 0.40f),
    PICK("Choosing the best moments", 0.02f),
    RENDER("Creating the Shorts", 0.30f),
    PACK("Packing the ZIP file", 0.03f);

    /** Share of the whole job that is finished when this stage starts. */
    val startsAt: Float get() = values().takeWhile { it != this }.sumOf { it.weight.toDouble() }.toFloat()
}

data class ShortResult(
    val index: Int, val file: File, val startMs: Long, val endMs: Long, val score: Int,
    val title: String, val hashtags: String, val description: String, val transcript: String, val reason: String,
) {
    val durationSec: Int get() = ((endMs - startMs) / 1000).toInt()
}

data class JobResult(val videoTitle: String, val dir: File, val shorts: List<ShortResult>, val zip: File, val notes: List<String>) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in shorts) arr.put(JSONObject()
            .put("index", s.index).put("file", s.file.name).put("startMs", s.startMs).put("endMs", s.endMs)
            .put("score", s.score).put("title", s.title).put("hashtags", s.hashtags)
            .put("description", s.description).put("transcript", s.transcript).put("reason", s.reason))
        return JSONObject().put("videoTitle", videoTitle).put("zip", zip.name).put("shorts", arr)
            .put("notes", JSONArray(notes))
    }

    companion object {
        fun load(dir: File): JobResult? = try {
            val o = JSONObject(File(dir, "result.json").readText())
            val shorts = ArrayList<ShortResult>()
            val arr = o.getJSONArray("shorts")
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val f = File(dir, s.getString("file"))
                if (f.exists()) shorts.add(ShortResult(s.getInt("index"), f, s.getLong("startMs"), s.getLong("endMs"),
                    s.getInt("score"), s.getString("title"), s.getString("hashtags"), s.getString("description"),
                    s.getString("transcript"), s.optString("reason", "")))
            }
            val zip = File(dir, o.getString("zip"))
            val notes = o.optJSONArray("notes")?.let { n -> (0 until n.length()).map { n.getString(it) } } ?: emptyList()
            if (shorts.isEmpty() || !zip.exists()) null else JobResult(o.getString("videoTitle"), dir, shorts, zip, notes)
        } catch (e: Exception) { null }
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@UnstableApi
class ShortsPipeline(private val context: Context, private val youtube: VideoSource = YouTubeSource()) {
    @Volatile private var whisper: WhisperModel? = null

    fun cancelNative() { whisper?.cancel() }

    suspend fun run(input: String, settings: Settings, onProgress: (Stage, Float, String) -> Unit): JobResult {
        val s = settings.sanitized()
        val job = currentCoroutineContext()[Job]!!
        val cancelled = { !job.isActive }
        WhisperNative.unsupportedReason()?.let { throw ProcessingException(it) }

        val isLocal = input.startsWith("content://") || input.startsWith("file://")
        val source: VideoSource = if (isLocal) LocalSource(context) else youtube
        val key = (if (isLocal) null else YouTubeUrl.extractId(input)) ?: ("local" + (input.hashCode().toLong() and 0xffffffffL))
        val workDir = File(context.cacheDir, "work/$key").apply { mkdirs() }

        // 0. speech model (downloaded once; ~30-180 MB)
        val modelFile = ModelStore.ensure(context, s.model, cancelled) { d, t ->
            onProgress(Stage.LINK, 0.05f, "Downloading the speech model (one time only): ${if (t > 0) (d * 100 / t) else 0}%")
        }

        // 1-2. link + download
        val video = source.fetch(input, workDir, s.maxVideoHeight, cancelled, onProgress)
        val info = VideoInfo.read(context, video.uri)
        val totalMs = if (info.durationMs > 0) info.durationMs else video.durationSec * 1000

        // 3. audio
        val pcm = File(workDir, "audio.pcm")
        onProgress(Stage.AUDIO, 0f, "Preparing the sound...")
        AudioDecoder.decodeToPcm16k(context, video.uri, pcm, cancelled = cancelled) { f ->
            onProgress(Stage.AUDIO, f, "Preparing the sound...")
        }

        // 4. speech (cached so a retry after a crash does not start over)
        val cache = File(workDir, "transcript_${s.model}_${s.language}.json")
        var transcript: Transcript? = null
        if (cache.exists()) transcript = try { Transcript.parse(cache.readText()) } catch (e: Exception) { null }
        if (transcript == null) {
            onProgress(Stage.SPEECH, 0f, "Listening to the speech...")
            val m = WhisperModel(modelFile)
            whisper = m
            try {
                transcript = m.transcribe(pcm, s.language, WhisperModel.bestThreadCount()) { p ->
                    onProgress(Stage.SPEECH, p / 100f, "Listening to the speech... $p%")
                }
            } finally { m.close(); whisper = null }
            cache.writeText(transcript!!.toJson())
        } else onProgress(Stage.SPEECH, 1f, "Speech already recognised")
        if (cancelled()) throw CancelledException()

        val rawWords = transcript!!.words
        val romanize = s.hinglish && s.language != "en"
        val words = if (romanize) rawWords.map { w -> Hinglish.romanize(w.text).ifBlank { w.text }.let { Word(it, w.t0, w.t1) } } else rawWords
        if (words.size < 10) throw ProcessingException("Very little speech was found in this video, so there is nothing to cut into Shorts.")

        // 5. choose moments
        onProgress(Stage.PICK, 0f, "Choosing the best moments...")
        val energy = try { AudioEnergy.rmsWindows(pcm) } catch (e: Exception) { null }
        val moments = MomentSelector(s.minSec * 1000L, s.maxSec * 1000L, s.clipCount).select(words, energy, totalMs)
        if (moments.isEmpty()) throw ProcessingException("Could not find a stretch of clear speech long enough for a Short. Try a lower minimum length in Settings.")
        onProgress(Stage.PICK, 1f, "Found ${moments.size} moments")

        // 6. render
        val outDir = File(context.filesDir, "outputs/${key}_${System.currentTimeMillis()}").apply { mkdirs() }
        val renderer = ClipRenderer(context)
        val style = CaptionStyle(textSizePx = s.captionSizePx.toFloat(), highlightColor = s.captionColor)
        val notes = ArrayList<String>()
        if (moments.size < s.clipCount) notes.add("Only ${moments.size} good moments were found (you asked for ${s.clipCount}).")
        val results = ArrayList<ShortResult>()
        for ((n, m) in moments.withIndex()) {
            if (cancelled()) throw CancelledException()
            val idx = n + 1
            val out = File(outDir, "short_%02d.mp4".format(idx))
            val inRange = words.filter { it.t1 >= m.startMs && it.t0 <= m.endMs }
            try {
                renderer.render(video.uri, info, m.startMs, m.endMs, inRange, s.layout, out, style) { p ->
                    onProgress(Stage.RENDER, (n + p / 100f) / moments.size, "Creating Short $idx of ${moments.size}... $p%")
                }
                val ti = Titles.make(m, inRange)
                results.add(ShortResult(idx, out, m.startMs, m.endMs, m.score, ti.title, ti.hashtags, ti.description,
                    inRange.joinToString(" ") { it.text }, m.reason))
            } catch (e: CancelledException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ProcessingException) {
                out.delete()
                notes.add("Short $idx was skipped: ${e.message}")
            }
        }
        if (results.isEmpty()) throw ProcessingException("None of the Shorts could be created. ${notes.firstOrNull() ?: ""}")

        // 7. ZIP + info files
        onProgress(Stage.PACK, 0f, "Packing the ZIP file...")
        val zip = File(outDir, "shorts.zip")
        writeZip(zip, video.title, results)
        val result = JobResult(video.title, outDir, results, zip, notes)
        File(outDir, "result.json").writeText(result.toJson().toString())
        onProgress(Stage.PACK, 1f, "Done")

        // clean up the big temporary files; keep only the last few outputs
        try { workDir.deleteRecursively() } catch (_: Exception) {}
        try {
            File(context.filesDir, "outputs").listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {}
        return result
    }

    @Suppress("FunctionName")
    private fun Uri_fromFile(f: File) = android.net.Uri.fromFile(f)

    private fun csv(v: String) = "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\""

    private fun writeZip(zip: File, title: String, shorts: List<ShortResult>) {
        val sb = StringBuilder("number,file,start_in_video,end_in_video,seconds,score,title,hashtags,description,why_chosen\n")
        for (s in shorts) {
            sb.append("${s.index},${s.file.name},${formatTime(s.startMs)},${formatTime(s.endMs)},${s.durationSec},${s.score},")
                .append(csv(s.title)).append(',').append(csv(s.hashtags)).append(',').append(csv(s.description)).append(',')
                .append(csv(s.reason)).append('\n')
        }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { z ->
            z.setLevel(1)
            for (s in shorts) {
                z.putNextEntry(ZipEntry(s.file.name))
                s.file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("shorts_info.csv"))
            z.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))   // BOM so Excel shows Hindi/emoji correctly
            z.write(sb.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }
}
