package com.shortsmaker.proof

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/** What a source hands to the pipeline: a playable video plus a few facts about it. */
data class SourceVideo(val uri: Uri, val title: String, val durationSec: Long, val id: String)

interface VideoSource {
    suspend fun fetch(
        input: String, workDir: File, maxHeight: Int, cancelled: () -> Boolean,
        onProgress: (Stage, Float, String) -> Unit,
    ): SourceVideo
}

/** Lets the NewPipe library make its web requests through OkHttp. */
class AppDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NpRequest): NpResponse {
        val data = request.dataToSend()
        val method = request.httpMethod()
        val body = if (data != null) data.toRequestBody(null) else if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
        var hasUa = false
        for ((k, vs) in request.headers()) {
            if (k.equals("User-Agent", true)) hasUa = true
            b.removeHeader(k)
            for (v in vs) b.addHeader(k, v)
        }
        if (!hasUa) b.header("User-Agent", Http.USER_AGENT)
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
            val text = resp.body?.string()
            return NpResponse(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
        }
    }
}

data class Resolved(
    val id: String, val title: String, val durationSec: Long,
    val videoOnly: List<StreamChoice>, val audio: List<StreamChoice>, val muxed: List<StreamChoice>,
)

class YouTubeSource(private val client: OkHttpClient = Http.client) : VideoSource {
    companion object {
        const val MAX_MINUTES = 120
        @Volatile private var initDone = false
        @Synchronized private fun initNewPipe(client: OkHttpClient) {
            if (initDone) return
            NewPipe.init(AppDownloader(client), Localization("en", "IN"), ContentCountry("IN"))
            initDone = true
        }

        /** Plain-language reason for any failure while reading a YouTube link. */
        fun friendly(e: Throwable): String = when (e) {
            is ProcessingException -> e.message ?: "Something went wrong."
            is AgeRestrictedContentException -> "This video is age-restricted. YouTube only shows it to signed-in adults, so the app cannot read it."
            is PrivateContentException -> "This video is private. Make it Unlisted or Public (or use a different video) and try again."
            is GeographicRestrictionException -> "This video is not available in your country."
            is PaidContentException, is YoutubeMusicPremiumContentException -> "This video needs a paid YouTube membership, so the app cannot read it."
            is SignInConfirmNotBotException -> "YouTube is asking this network to prove it is not a robot. Try again in a few minutes, or switch between Wi-Fi and mobile data."
            is ReCaptchaException -> "YouTube is temporarily blocking requests from this network. Wait a few minutes, or switch between Wi-Fi and mobile data."
            is ContentNotAvailableException -> "This video is not available: ${e.message ?: "removed or blocked"}."
            is IOException -> "Could not reach YouTube. Check your internet connection."
            else -> "YouTube changed something the app does not understand yet (${e.javaClass.simpleName}). Try another link; if every link fails, the app needs an update."
        }
    }

    /** Downloads the picture and sound streams and joins them into [finalFile]. Shared with the tests. */
    fun downloadAndMerge(
        client: OkHttpClient, v: StreamChoice, a: StreamChoice, workDir: File, finalFile: File,
        cancelled: () -> Boolean, onProgress: (Stage, Float, String) -> Unit,
    ) {
        val vf = File(workDir, "video.mp4")
        val af = File(workDir, "audio.m4a")
        val total = (if (v.contentLength > 0) v.contentLength else 1L) + (if (a.contentLength > 0) a.contentLength else 1L)
        var vDone = 0L; var aDone = 0L
        fun report(msg: String) = onProgress(Stage.DOWNLOAD, ((vDone + aDone).toFloat() / total).coerceIn(0f, 0.97f), msg)
        StreamDownloader.download(client, v.url, vf, v.contentLength, cancelled) { d, _ -> vDone = d; report("Downloading picture ${v.height}p...") }
        StreamDownloader.download(client, a.url, af, a.contentLength, cancelled) { d, _ -> aDone = d; report("Downloading sound...") }
        onProgress(Stage.DOWNLOAD, 0.98f, "Joining picture and sound...")
        Mux.merge(vf, af, finalFile, cancelled)
        vf.delete(); af.delete()
    }

    /** Reads the link and lists the download options. Blocking network work - runs off the main thread. */
    suspend fun resolve(input: String): Resolved = withContext(Dispatchers.IO) {
        val link = YouTubeUrl.normalize(input)
            ?: throw ProcessingException("That does not look like a link to one YouTube video. Paste a link like https://www.youtube.com/watch?v=... or https://youtu.be/...")
        val id = YouTubeUrl.extractId(input)!!
        initNewPipe(client)
        val info = try { StreamInfo.getInfo(link) } catch (e: Throwable) { throw ProcessingException(friendly(e), e) }
        if (info.streamType != StreamType.VIDEO_STREAM) {
            throw ProcessingException("This link is a live stream or audio-only. Use a normal finished video.")
        }
        val dur = info.duration
        if (dur <= 0) throw ProcessingException("YouTube did not report a length for this video.")
        if (dur > MAX_MINUTES * 60) throw ProcessingException("This video is longer than $MAX_MINUTES minutes. Use a shorter video (10-30 minutes works best).")
        fun toChoice(url: String, h: Int, codec: String?, br: Int, fmt: String?, len: Long) =
            StreamChoice(url, h, codec ?: "", br, fmt ?: "", len.coerceAtLeast(0))
        val vo = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { toChoice(it.content, it.height, it.codec, it.bitrate, it.format?.suffix, it.itagItem?.contentLength ?: 0) }
        val au = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { toChoice(it.content, 0, it.codec, it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate, it.format?.suffix, it.itagItem?.contentLength ?: 0) }
        val mx = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { toChoice(it.content, it.height, it.codec, it.bitrate, it.format?.suffix, it.itagItem?.contentLength ?: 0) }
        Resolved(id, info.name ?: "YouTube video", dur, vo, au, mx)
    }

    override suspend fun fetch(
        input: String, workDir: File, maxHeight: Int, cancelled: () -> Boolean,
        onProgress: (Stage, Float, String) -> Unit,
    ): SourceVideo {
        onProgress(Stage.LINK, 0.1f, "Reading the YouTube link...")
        val r = resolve(input)
        onProgress(Stage.LINK, 1f, "Found: ${r.title}")
        workDir.mkdirs()
        val finalFile = File(workDir, "source.mp4")
        val marker = File(workDir, "source.ok")
        if (finalFile.exists() && marker.exists()) {
            onProgress(Stage.DOWNLOAD, 1f, "Video already downloaded")
            return SourceVideo(Uri.fromFile(finalFile), r.title, r.durationSec, r.id)
        }
        val v = StreamPicker.pickVideo(r.videoOnly, maxHeight)
        val a = StreamPicker.pickAudio(r.audio)
        withContext(Dispatchers.IO) {
            if (v != null && a != null) {
                downloadAndMerge(client, v, a, workDir, finalFile, cancelled, onProgress)
            } else {
                val m = StreamPicker.pickMuxed(r.muxed)
                    ?: throw ProcessingException("YouTube did not offer a downloadable version of this video (no MP4 streams). Try again later or try another video.")
                StreamDownloader.download(client, m.url, finalFile, m.contentLength, cancelled) { d, t ->
                    onProgress(Stage.DOWNLOAD, if (t > 0) (d.toFloat() / t).coerceIn(0f, 0.98f) else 0.5f, "Downloading ${m.height}p video...")
                }
            }
            marker.writeText("ok")
        }
        onProgress(Stage.DOWNLOAD, 1f, "Download finished")
        return SourceVideo(Uri.fromFile(finalFile), r.title, r.durationSec, r.id)
    }
}

/** Fallback only: a video file picked from the phone. */
class LocalSource(private val context: Context) : VideoSource {
    override suspend fun fetch(
        input: String, workDir: File, maxHeight: Int, cancelled: () -> Boolean,
        onProgress: (Stage, Float, String) -> Unit,
    ): SourceVideo {
        val uri = Uri.parse(input)
        val info = VideoInfo.read(context, uri)
        onProgress(Stage.LINK, 1f, "Using the chosen video file")
        onProgress(Stage.DOWNLOAD, 1f, "No download needed")
        return SourceVideo(uri, "My video", info.durationMs / 1000, "local" + (input.hashCode().toLong() and 0xffffffffL))
    }
}
