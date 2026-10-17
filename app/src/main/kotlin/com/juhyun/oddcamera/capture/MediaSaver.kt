package com.juhyun.oddcamera.capture

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes captures to MediaStore under DCIM/OddCamera, hidden (IS_PENDING) until complete. Any thread but main. */
class MediaSaver(private val resolver: ContentResolver) {

    /** On any exception the row is deleted and the exception rethrown. */
    fun savePhoto(write: (OutputStream) -> Unit): Uri {
        val uri = insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", "jpg")
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "openOutputStream failed" }.use(write)
            publish(uri)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    /** A pending video row with an open read/write descriptor; finish with commit() or abort(). */
    fun beginVideo(): PendingVideo {
        val uri = insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "video/mp4", "mp4")
        try {
            return PendingVideo(uri, checkNotNull(resolver.openFileDescriptor(uri, "rw")) { "openFileDescriptor failed" })
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    inner class PendingVideo(val uri: Uri, val fd: ParcelFileDescriptor) {
        /** Closes the descriptor and makes the video visible; on failure the row is deleted and the error rethrown. */
        fun commit() {
            try {
                fd.close()
                publish(uri)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        fun abort() {
            runCatching { fd.close() }
            resolver.delete(uri, null, null)
        }
    }

    private fun insert(collection: Uri, mime: String, ext: String): Uri {
        val name = "OddCamera_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".$ext"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return checkNotNull(resolver.insert(collection, values)) { "MediaStore insert failed" }
    }

    private fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    companion object {
        const val RELATIVE_PATH = "DCIM/OddCamera"
    }
}
