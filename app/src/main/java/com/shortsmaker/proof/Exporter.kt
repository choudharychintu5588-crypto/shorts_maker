package com.shortsmaker.proof

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Exporter {
    /** Copies a finished MP4 into the phone's Movies/ShortsMaker folder (visible in Gallery). No permission needed. */
    fun saveToMovies(context: Context, file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShortsMaker")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ProcessingException("Could not save to the Gallery (is the storage full?).")
        try {
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw ProcessingException("Could not save to the Gallery.", e)
        }
        return uri
    }

    /** Saves the ZIP into the phone's Downloads/ShortsMaker folder. */
    fun saveToDownloads(context: Context, file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ShortsMaker")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ProcessingException("Could not save to Downloads (is the storage full?).")
        try {
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw ProcessingException("Could not save the ZIP file.", e)
        }
        return uri
    }
}
