package com.shortsmaker.proof

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads Whisper models (free public files) once and keeps them in the app's private storage. */
object ModelStore {
    val names = listOf("tiny", "base", "small")

    fun file(context: Context, name: String) = File(File(context.filesDir, "models").apply { mkdirs() }, "ggml-$name-q5_1.bin")

    private fun url(name: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$name-q5_1.bin"

    // Smallest believable size for each quantised model, to reject truncated downloads.
    private fun minBytes(name: String) = when (name) { "tiny" -> 25_000_000L; "base" -> 45_000_000L; else -> 150_000_000L }

    fun isReady(context: Context, name: String): Boolean = file(context, name).let { it.exists() && it.length() >= minBytes(name) }

    fun delete(context: Context, name: String) { file(context, name).delete(); File(file(context, name).path + ".part").delete() }

    /** Blocking. Resumes a partial download. Throws ProcessingException with a readable message. */
    fun ensure(context: Context, name: String, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): File {
        val target = file(context, name)
        if (isReady(context, name)) return target
        val part = File(target.path + ".part")
        var lastError: Exception? = null
        for (attempt in 1..4) {
            try {
                download(url(name), part, cancelled, onProgress)
                if (part.length() < minBytes(name)) throw ProcessingException("The model download was incomplete.")
                if (!part.renameTo(target)) throw ProcessingException("Could not save the model file (is the phone storage full?).")
                return target
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
            }
        }
        throw ProcessingException("Could not download the speech model. Check your internet connection and try again.", lastError)
    }

    private fun download(url: String, part: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShortsMaker/0.1")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) { part.delete(); have = 0; throw ProcessingException("Restarting download.") }
            if (code != 200 && code != 206) throw ProcessingException("Server answered $code.")
            val append = code == 206
            if (!append) have = 0
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = have
                    while (true) {
                        if (cancelled()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
