package com.juhyun.oddcamera.filter

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.juhyun.oddcamera.filter.ascii.AsciiFilter
import com.juhyun.oddcamera.filter.boxes.LightBoxesFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParamStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // Okio storage: the default File storage cannot replace an existing file on a Windows test host (renameTo).
    private fun store(path: Path, job: Job) = ParamStore(PreferenceDataStoreFactory.create(
        OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path }, scope = CoroutineScope(Dispatchers.IO + job)))

    @Test fun savedValuesReloadPerFilter() = runBlocking {
        val path = tmp.root.resolve("params.preferences_pb").toOkioPath()
        // DataStore allows one active instance per file: cancel the first scope before reopening.
        val job1 = SupervisorJob()
        val store1 = store(path, job1)
        store1.save(AsciiFilter.id, store1.load(AsciiFilter).apply { set("columns", 90) })
        store1.save(LightBoxesFilter.id, store1.load(LightBoxesFilter).apply { set("maxBoxes", 10) })
        job1.cancelAndJoin()

        val job2 = SupervisorJob()
        val store2 = store(path, job2)
        assertEquals(90, store2.load(AsciiFilter).int("columns"))
        val boxes = store2.load(LightBoxesFilter)
        assertEquals(10, boxes.int("maxBoxes"))
        assertEquals(3f, boxes.float("topPercent")) // untouched default; ASCII's topPercent didn't leak in
        job2.cancelAndJoin()
    }

    /** Only non-defaults are stored, so a changed default still reaches users who once touched another slider. */
    @Test fun defaultsAreNotPersisted() = runBlocking {
        val path = tmp.root.resolve("params.preferences_pb").toOkioPath()
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(
            OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path }, scope = CoroutineScope(Dispatchers.IO + job))
        val store = ParamStore(ds)
        val v = store.load(AsciiFilter).apply { set("columns", 90) }
        store.save(AsciiFilter.id, v)
        assertEquals(setOf("ascii.columns"), ds.data.first().asMap().keys.map { it.name }.toSet())
        v.set("columns", 60) // back to the default: the key goes away
        store.save(AsciiFilter.id, v)
        assertEquals(emptySet<String>(), ds.data.first().asMap().keys.map { it.name }.toSet())
        job.cancelAndJoin()
    }
}
