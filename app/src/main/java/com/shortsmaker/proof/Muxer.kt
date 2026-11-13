package com.shortsmaker.proof

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Joins a picture-only MP4 and a sound-only M4A into one MP4 without re-encoding (fast, lossless). */
object Mux {
    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return -1
    }

    fun merge(video: File, audio: File, out: File, cancelled: () -> Boolean = { false }) {
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            try { vEx.setDataSource(video.path); aEx.setDataSource(audio.path) }
            catch (e: Exception) { throw ProcessingException("The downloaded video could not be read.", e) }
            val vt = findTrack(vEx, "video/")
            val at = findTrack(aEx, "audio/")
            if (vt < 0) throw ProcessingException("The downloaded picture stream is not a video.")
            if (at < 0) throw ProcessingException("The downloaded sound stream is not audio.")
            vEx.selectTrack(vt); aEx.selectTrack(at)
            if (out.exists()) out.delete()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vi = m.addTrack(vEx.getTrackFormat(vt))
            val ai = m.addTrack(aEx.getTrackFormat(at))
            m.start(); started = true

            val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            while (!(vDone && aDone)) {
                if (cancelled()) throw CancelledException()
                val vTime = if (vDone) Long.MAX_VALUE else vEx.sampleTime.also { if (it < 0) vDone = true }
                val aTime = if (aDone) Long.MAX_VALUE else aEx.sampleTime.also { if (it < 0) aDone = true }
                if (vDone && aDone) break
                val useVideo = !vDone && (aDone || vTime <= aTime)
                val ex = if (useVideo) vEx else aEx
                val idx = if (useVideo) vi else ai
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) { if (useVideo) vDone = true else aDone = true; continue }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, ex.sampleTime, flags)
                m.writeSampleData(idx, buf, info)
                ex.advance()
            }
        } catch (e: ProcessingException) {
            throw e
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            throw ProcessingException("Could not join the picture and sound of the video (${e.javaClass.simpleName}).", e)
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            vEx.release(); aEx.release()
        }
        if (!out.exists() || out.length() < 10_000) throw ProcessingException("The joined video file was not created.")
    }
}
