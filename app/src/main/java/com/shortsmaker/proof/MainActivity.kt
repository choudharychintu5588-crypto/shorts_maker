package com.shortsmaker.proof

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PROOF screen: pick a video, run the real pipeline on the first minutes of it, and print timings so we know
 * what this phone can do. The full app replaces this screen.
 */
@UnstableApi
class MainActivity : androidx.activity.ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var model: WhisperModel? = null
    private var picked: Uri? = null
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var modelSpinner: Spinner
    private lateinit var langSpinner: Spinner

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { picked = uri; say("Picked: $uri"); runBtn.isEnabled = true }
    }

    private fun say(s: String) { log.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Shorts Maker - proof build"; textSize = 22f; gravity = Gravity.START })
        root.addView(TextView(this).apply {
            text = "Tests this phone: speech recognition speed and 9:16 caption rendering."
            textSize = 14f
        })
        modelSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ModelStore.names) }
        langSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("hi", "en", "auto")) }
        root.addView(TextView(this).apply { text = "Speech model (tiny = fastest):" })
        root.addView(modelSpinner)
        root.addView(TextView(this).apply { text = "Spoken language:" })
        root.addView(langSpinner)
        val pick = Button(this).apply { text = "1. Pick a video from gallery"; setOnClickListener { picker.launch("video/*") } }
        runBtn = Button(this).apply { text = "2. Run proof test"; isEnabled = false; setOnClickListener { run() } }
        stopBtn = Button(this).apply { text = "Stop"; isEnabled = false; setOnClickListener { job?.cancel(); model?.cancel() } }
        root.addView(pick); root.addView(runBtn); root.addView(stopBtn)
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        say("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
        val rt = Runtime.getRuntime()
        say("Java heap limit: ${rt.maxMemory() / 1048576} MB, CPU cores: ${rt.availableProcessors()}")
        WhisperNative.unsupportedReason()?.let { say("PROBLEM: $it"); runBtn.isEnabled = false }
    }

    override fun onDestroy() { scope.cancel(); model?.close(); super.onDestroy() }

    private fun run() {
        val uri = picked ?: return
        val modelName = modelSpinner.selectedItem as String
        val lang = langSpinner.selectedItem as String
        runBtn.isEnabled = false; stopBtn.isEnabled = true
        job = scope.launch {
            try {
                withContext(Dispatchers.Default) { pipeline(uri, modelName, lang) }
            } catch (e: CancelledException) {
                say("Stopped.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("Stopped.")
            } catch (e: ProcessingException) {
                say("ERROR: ${e.message}")
            } catch (e: Throwable) {
                say("UNEXPECTED ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runBtn.isEnabled = true; stopBtn.isEnabled = false
            }
        }
    }

    private suspend fun pipeline(uri: Uri, modelName: String, lang: String) {
        fun s(msg: String) { runOnUiThread { say(msg) } }
        val ctx = applicationContext
        val info = VideoInfo.read(ctx, uri)
        s("Video: ${info.displayWidth}x${info.displayHeight}, ${info.durationMs / 1000}s")

        // 1. model
        var lastPct = -1
        val t0 = System.nanoTime()
        val modelFile = ModelStore.ensure(ctx, modelName, { job?.isActive == false }) { done, total ->
            val p = if (total > 0) (done * 100 / total).toInt() else 0
            if (p / 10 != lastPct / 10) { lastPct = p; s("Downloading $modelName model: $p%") }
        }
        s("Model ready (${modelFile.length() / 1048576} MB)")

        // 2. audio (first 3 minutes is enough to measure speed)
        val pcm = File(ctx.cacheDir, "proof_audio.pcm")
        val ta = System.nanoTime()
        val audio = AudioDecoder.decodeToPcm16k(ctx, uri, pcm, maxSeconds = 180.0, cancelled = { job?.isActive == false }) {}
        val decodeSec = (System.nanoTime() - ta) / 1e9
        s("Audio decoded: ${"%.0f".format(audio.seconds)}s of sound in ${"%.1f".format(decodeSec)}s")

        // 3. speech recognition
        val tl = System.nanoTime()
        val m = WhisperModel(modelFile).also { model?.close(); model = it }
        s("Model loaded in ${"%.1f".format((System.nanoTime() - tl) / 1e9)}s")
        val tt = System.nanoTime()
        var lastP = -10
        val tr = m.transcribe(pcm, lang, WhisperModel.bestThreadCount()) { p ->
            if (p >= lastP + 10) { lastP = p; s("Transcribing: $p%") }
        }
        val trSec = (System.nanoTime() - tt) / 1e9
        val rtf = trSec / audio.seconds
        s("Transcribed ${"%.0f".format(audio.seconds)}s of audio in ${"%.1f".format(trSec)}s  (speed ${"%.2f".format(rtf)}x real time)")
        s("=> a 20-minute video would take about ${"%.0f".format(rtf * 20)} minutes with this model")
        s("Language: ${tr.language}, words: ${tr.words.size}")
        s("Text: " + tr.fullText.take(300))
        if (tr.words.size < 5) throw ProcessingException("Very little speech was found in the first 3 minutes.")

        // 4. render two Shorts: 20 s from first speech, both layouts
        val first = tr.words.first().t0
        val startMs = maxOf(0L, first - 300)
        val endMs = minOf(startMs + 20_000, info.durationMs)
        val renderer = ClipRenderer(ctx)
        for (layout in Layout.values()) {
            val out = File(ctx.cacheDir, "proof_${layout.name.lowercase()}.mp4")
            val tv = System.nanoTime()
            var lp = -20
            renderer.render(uri, info, startMs, endMs, tr.words, layout, out) { p ->
                if (p >= lp + 20) { lp = p; s("Rendering ${layout.name}: $p%") }
            }
            val sec = (System.nanoTime() - tv) / 1e9
            val saved = Exporter.saveToMovies(ctx, out, "proof_${layout.name.lowercase()}_${System.currentTimeMillis() / 1000}.mp4")
            s("${layout.name} Short done in ${"%.1f".format(sec)}s for ${(endMs - startMs) / 1000}s of video -> saved to Gallery (Movies/ShortsMaker)")
        }
        s("PROOF FINISHED. Open your Gallery and check the two videos (captions, audio sync, layout).")
    }
}
