package com.shortsmaker.proof

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Downloads one file in small ranged pieces (YouTube slows down single huge requests), with resume and retries. */
object StreamDownloader {
    private const val CHUNK = 4L * 1024 * 1024

    fun download(
        client: OkHttpClient, url: String, dest: File, expectedLen: Long,
        cancelled: () -> Boolean, onBytes: (done: Long, total: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        if (dest.exists() && dest.length() > 0 && (expectedLen <= 0 || dest.length() == expectedLen)) {
            onBytes(dest.length(), dest.length()); return
        }
        val part = File(dest.path + ".part")
        var total = expectedLen
        var pos = if (part.exists()) part.length() else 0L
        var paramMode = false
        var failures = 0
        val buf = ByteArray(1 shl 16)

        while (true) {
            if (cancelled()) throw CancelledException()
            if (total > 0 && pos >= total) break
            val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
            val rb = Request.Builder().header("User-Agent", Http.USER_AGENT)
            if (paramMode) rb.url(url + (if (url.contains('?')) "&" else "?") + "range=$pos-$end")
            else { rb.url(url); rb.header("Range", "bytes=$pos-$end") }
            try {
                client.newCall(rb.build()).execute().use { resp ->
                    when (resp.code) {
                        206, 200 -> {
                            var append = true
                            if (resp.code == 200 && pos > 0 && !paramMode) { append = false; pos = 0 }   // server ignored Range
                            val cr = resp.header("Content-Range")
                            if (cr != null) cr.substringAfter('/', "").toLongOrNull()?.let { total = it }
                            else if (resp.code == 200 || paramMode) resp.body?.contentLength()?.takeIf { it > 0 }?.let { if (pos == 0L) total = it }
                            val body = resp.body ?: throw IOException("empty response")
                            var got = 0L
                            FileOutputStream(part, append).use { out ->
                                body.byteStream().use { input ->
                                    while (true) {
                                        if (cancelled()) throw CancelledException()
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        got += n
                                        onBytes(pos + got, total)
                                    }
                                }
                            }
                            pos += got
                            if (got == 0L) throw IOException("no data")
                            if (resp.code == 200 && !paramMode) total = pos       // whole file arrived in one go
                            else if (total <= 0 && got < (end - (pos - got) + 1)) total = pos   // short piece = end of file
                            failures = 0
                        }
                        416 -> { if (pos > 0) total = pos else throw IOException("range not satisfiable") }
                        403, 410 -> {
                            if (!paramMode) paramMode = true
                            else throw ProcessingException("YouTube refused to give the video file (HTTP ${resp.code}). Try again in a few minutes, or switch between Wi-Fi and mobile data.")
                        }
                        else -> throw IOException("HTTP ${resp.code}")
                    }
                }
            } catch (e: ProcessingException) {
                throw e
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (failures > 5) throw ProcessingException("The download kept failing. Check your internet connection and try again.", e)
                try { Thread.sleep(1000L * failures) } catch (_: InterruptedException) { throw CancelledException() }
            }
        }
        if (total > 0 && part.length() != total) throw ProcessingException("The downloaded file is incomplete. Please try again.")
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw ProcessingException("Could not save the downloaded video (is the phone storage full?).")
    }
}
