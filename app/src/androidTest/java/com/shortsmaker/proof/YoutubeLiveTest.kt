package com.shortsmaker.proof

import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Test

/**
 * INFORMATION ONLY - never fails the build. GitHub's servers are usually blocked by YouTube, so this can only
 * report what YouTube answers to a data-centre address; the phone's home connection is a different case.
 */
@UnstableApi
class YoutubeLiveTest {
    @Test fun reportWhatYouTubeAnswers() {
        val link = "https://www.youtube.com/watch?v=jNQXAC9IVRw"   // the first video ever uploaded to YouTube (19 s)
        try {
            val r = runBlocking { YouTubeSource().resolve(link) }
            Log.i("YTLIVE", "RESOLVED title='${r.title}' duration=${r.durationSec}s videoOnly=${r.videoOnly.size} audio=${r.audio.size} muxed=${r.muxed.size}")
            val v = StreamPicker.pickVideo(r.videoOnly, 720)
            val a = StreamPicker.pickAudio(r.audio)
            Log.i("YTLIVE", "PICKED video=${v?.height}p ${v?.codec} audio=${a?.bitrate}")
            if (a != null) {
                val req = Request.Builder().url(a.url).header("Range", "bytes=0-65535").header("User-Agent", Http.USER_AGENT).build()
                Http.client.newCall(req).execute().use { resp ->
                    Log.i("YTLIVE", "DOWNLOAD_TEST http=${resp.code} bytes=${resp.body?.bytes()?.size}")
                }
            }
        } catch (e: Throwable) {
            Log.i("YTLIVE", "BLOCKED_OR_FAILED ${e.javaClass.simpleName}: ${YouTubeSource.friendly(e)}")
        }
    }
}
