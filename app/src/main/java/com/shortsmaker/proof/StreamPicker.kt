package com.shortsmaker.proof

import java.net.URI

data class StreamChoice(
    val url: String, val height: Int, val codec: String, val bitrate: Int,
    val container: String, val contentLength: Long,
)

object StreamPicker {
    /** MP4/H.264 video-only stream, as high as allowed (falls back to the smallest one above the limit). */
    fun pickVideo(list: List<StreamChoice>, maxHeight: Int): StreamChoice? {
        val ok = list.filter { it.container.equals("mp4", true) && it.codec.startsWith("avc1", true) && it.height > 0 }
        if (ok.isEmpty()) return null
        val within = ok.filter { it.height <= maxHeight }
        return if (within.isNotEmpty()) within.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
        else ok.minWithOrNull(compareBy({ it.height }, { it.bitrate }))
    }

    /** AAC (m4a) audio-only stream around 128 kbps. */
    fun pickAudio(list: List<StreamChoice>): StreamChoice? {
        val ok = list.filter { it.container.equals("m4a", true) || it.container.equals("mp4", true) }
        if (ok.isEmpty()) return null
        val good = ok.filter { it.bitrate in 90_000..200_000 }
        return if (good.isNotEmpty()) good.maxByOrNull { it.bitrate } else ok.maxByOrNull { it.bitrate }
    }

    /** Muxed (picture + sound) MP4 as a last resort. */
    fun pickMuxed(list: List<StreamChoice>): StreamChoice? =
        list.filter { it.container.equals("mp4", true) }.maxByOrNull { it.height }
}

object YouTubeUrl {
    private val idRegex = Regex("^[A-Za-z0-9_-]{11}$")

    /** Returns a clean https://www.youtube.com/watch?v=ID link, or null if this is not a single-video YouTube link. */
    fun normalize(input: String): String? = extractId(input)?.let { "https://www.youtube.com/watch?v=$it" }

    fun extractId(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        // the user may paste a whole sentence; take the first thing that looks like a link
        val m = Regex("""(https?://\S+|(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/\S+)""", RegexOption.IGNORE_CASE).find(s)
        if (m != null) s = m.value
        if (!s.startsWith("http", true)) s = "https://$s"
        val uri = try { URI(s) } catch (e: Exception) { return null }
        val host = (uri.host ?: return null).lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val path = uri.path ?: ""
        val query = uri.rawQuery ?: ""
        fun ok(id: String?) = id?.takeIf { idRegex.matches(it) }
        return when (host) {
            "youtu.be" -> ok(path.trim('/').substringBefore('/'))
            "youtube.com", "youtube-nocookie.com" -> {
                val v = query.split('&').firstOrNull { it.startsWith("v=") }?.substring(2)
                ok(v) ?: run {
                    val parts = path.trim('/').split('/')
                    if (parts.size >= 2 && parts[0] in listOf("shorts", "embed", "live", "v")) ok(parts[1]) else null
                }
            }
            else -> null
        }
    }
}
