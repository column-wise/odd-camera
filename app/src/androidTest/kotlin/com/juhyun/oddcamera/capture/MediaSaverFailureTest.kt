package com.juhyun.oddcamera.capture

import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MediaSaverFailureTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val saver = MediaSaver(resolver)

    /** Rows (pending included) of ours under DCIM/OddCamera, by display name. */
    private fun names(): Set<String> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/OddCamera%'")
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val out = HashSet<String>()
        resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), args, null)!!.use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    @Test fun failedWriteLeavesNoRow() {
        val before = names()
        assertThrows(IOException::class.java) { saver.savePhoto { throw IOException("boom") } }
        assertEquals(before, names())
    }

    @Test fun savedPhotoIsPublishedWithItsBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val uri = saver.savePhoto { it.write(bytes) }
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME), null, null)!!.use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals("DCIM/OddCamera/", c.getString(1))
                val name = c.getString(2)
                assertTrue(name, Regex("""OddCamera_\d{8}_\d{6}_\d{3}\.jpg""").matches(name))
            }
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun pendingVideoCommitAndAbort() {
        val before = names()
        val aborted = saver.beginVideo()
        aborted.abort()
        assertEquals(before, names())

        val v = saver.beginVideo()
        java.io.FileOutputStream(v.fd.fileDescriptor).write(byteArrayOf(9, 9))
        v.commit()
        try {
            resolver.query(v.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null)!!.use { c ->
                c.moveToFirst(); assertEquals(0, c.getInt(0))
            }
        } finally {
            resolver.delete(v.uri, null, null)
        }
    }
}
