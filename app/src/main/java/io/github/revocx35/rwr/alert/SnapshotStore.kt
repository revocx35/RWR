package io.github.revocx35.rwr.alert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves snapshots and clips to the shared gallery (Pictures/RWR) via MediaStore; no storage permission needed. */
object SnapshotStore {
    const val ALBUM = "RWR"
    private const val TAG = "SnapshotStore"

    fun save(context: Context, bitmap: Bitmap, takenAt: Long = System.currentTimeMillis()): Uri? {
        val name = "RWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(takenAt)) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: error("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saving snapshot failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Copies an MP4 clip into the gallery (same Pictures/RWR album as the photos). */
    fun saveVideo(context: Context, file: java.io.File, takenAt: Long): Uri? {
        val name = "RWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(takenAt)) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Video.Media.DATE_TAKEN, takenAt)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saving clip failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
