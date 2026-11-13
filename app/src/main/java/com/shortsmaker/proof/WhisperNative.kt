package com.shortsmaker.proof

import android.os.Build
import java.io.File

/** Thin wrapper around the whisper.cpp native library. */
object WhisperNative {
    fun interface Progress { fun onProgress(percent: Int) }

    @Volatile private var loaded = false

    /** Returns null when this phone can run the engine, or a readable reason when it cannot. */
    fun unsupportedReason(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return "Unknown CPU type."
        if (abi == "x86_64") return null // emulator / Chromebook
        if (abi != "arm64-v8a") return "This app needs a 64-bit ARM phone (your CPU: $abi)."
        val info = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { "" }
        // Redmi 12C (Cortex-A75/A55) has both. Older phones may not.
        val ok = info.contains("asimddp") && (info.contains("asimdhp") || info.contains("fphp"))
        return if (ok) null else "This phone's CPU is too old for the speech engine (needs ARMv8.2 with dot-product)."
    }

    @Synchronized fun load() {
        if (loaded) return
        System.loadLibrary("shortsmaker")
        loaded = true
    }

    @JvmStatic external fun nInit(path: String): Long
    @JvmStatic external fun nFree(ptr: Long)
    @JvmStatic external fun nAbort()
    @JvmStatic external fun nTranscribe(
        ptr: Long, pcmPath: String, threads: Int, lang: String, translate: Boolean,
        listener: Progress?, errOut: Array<String?>
    ): ByteArray?
    @JvmStatic external fun nSystemInfo(): String
}

class WhisperModel(private val modelFile: File) : AutoCloseable {
    private var ptr = 0L

    init {
        WhisperNative.load()
        ptr = WhisperNative.nInit(modelFile.absolutePath)
        if (ptr == 0L) {
            throw ProcessingException("The speech model file looks damaged. Delete it in the app and download it again.")
        }
    }

    fun transcribe(pcm: File, language: String, threads: Int, onProgress: (Int) -> Unit): Transcript {
        val err = arrayOfNulls<String>(1)
        val bytes = WhisperNative.nTranscribe(ptr, pcm.absolutePath, threads, language, false,
            WhisperNative.Progress { onProgress(it) }, err)
        if (bytes == null) {
            if (err[0] == "cancelled") throw CancelledException()
            throw ProcessingException("Speech recognition failed: ${err[0] ?: "unknown error"}")
        }
        return Transcript.parse(String(bytes, Charsets.UTF_8))
    }

    fun cancel() = WhisperNative.nAbort()

    override fun close() {
        if (ptr != 0L) { WhisperNative.nFree(ptr); ptr = 0L }
    }

    companion object {
        /** Threads that give the best speed: the fast cores only, at most 4. */
        fun bestThreadCount(): Int {
            val n = Runtime.getRuntime().availableProcessors()
            return n.coerceIn(2, 4)
        }
    }
}
