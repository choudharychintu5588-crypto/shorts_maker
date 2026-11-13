package com.shortsmaker.proof

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long, val rotation: Int, val hasVideo: Boolean) {
    /** Size as the viewer sees it (after rotation). */
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    companion object {
        fun read(context: Context, uri: Uri): VideoInfo {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w <= 0 || h <= 0) throw ProcessingException("This file has no picture, or it is not a supported video.")
                return VideoInfo(w, h, d, rot, true)
            } catch (e: ProcessingException) {
                throw e
            } catch (e: Exception) {
                throw ProcessingException("This file cannot be opened as a video.", e)
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }
    }
}
