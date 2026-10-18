package com.juhyun.oddcamera

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.juhyun.oddcamera.ui.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Review Focus 2: backgrounding mid-recording finalizes a playable MP4. Mic denied: VideoEncoderTest (ruling R6). */
@RunWith(AndroidJUnit4::class)
class RecordingLifecycleTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Our video rows (pending included): id -> IS_PENDING. */
    private fun rows(): Map<Long, Int> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/OddCamera%'")
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val out = HashMap<Long, Int>()
        resolver.query(videos, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING), args, null)!!.use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
        }
        return out
    }

    @Test fun backgroundingFinalizesRecording() {
        val before = rows().keys
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.vm.setMode(CaptureMode.VIDEO) }
            Thread.sleep(2000) // rebind to 16:9 and let frames flow
            sc.onActivity { it.toggleRecording() }
            sc.onActivity { assertNotNull("recording did not start", it.vm.recordingSince.value) }
            Thread.sleep(2500)
            sc.moveToState(Lifecycle.State.CREATED)

            var uri: Uri? = null
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (uri == null && SystemClock.elapsedRealtime() < deadline) {
                val new = rows().filterKeys { it !in before }
                if (new.isNotEmpty() && new.values.all { it == 0 }) uri = ContentUris.withAppendedId(videos, new.keys.single())
                else Thread.sleep(200)
            }
            assertNotNull("no finalized video row", uri)
            try {
                val r = MediaMetadataRetriever().apply { setDataSource(context, uri) }
                try {
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("duration $ms", ms >= 1500)
                    assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                } finally {
                    r.release()
                }
            } finally {
                resolver.delete(uri!!, null, null)
            }
        }
    }
}
