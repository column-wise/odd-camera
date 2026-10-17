package com.juhyun.oddcamera.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juhyun.oddcamera.camera.Aspect
import com.juhyun.oddcamera.camera.CountdownTimer
import com.juhyun.oddcamera.camera.FlashMode
import com.juhyun.oddcamera.filter.FilterRegistry
import com.juhyun.oddcamera.filter.ParamStore
import com.juhyun.oddcamera.filter.ParamValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val Context.paramDataStore by preferencesDataStore("filter_params")

enum class CaptureMode(val aspect: Aspect) { PHOTO(Aspect.PHOTO_4_3), VIDEO(Aspect.VIDEO_16_9) }

/** Single source of UI state. Zoom is the camera's own state (CameraController.zoomRatio / zoomRange). */
class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ParamStore(app.paramDataStore)
    val filters = FilterRegistry.all
    /** One live instance per filter; RenderThread snapshots the selected one each frame. */
    val values: List<ParamValues> = filters.map { ParamValues(it.params) }
    /** Bumped on every param change/load so Compose re-reads [values]. */
    val revision = MutableStateFlow(0)

    val filterIndex = MutableStateFlow(0)
    val mode = MutableStateFlow(CaptureMode.PHOTO)
    val flash = MutableStateFlow(FlashMode.OFF)
    val torch = MutableStateFlow(false)
    val timerSeconds = MutableStateFlow(0)
    /** Remaining countdown seconds; 0 = not counting. */
    val countdown = MutableStateFlow(0)
    /** Set by Tasks 9/10 after a save. */
    val lastCapture = MutableStateFlow<Uri?>(null)

    private val saveJobs = arrayOfNulls<Job>(filters.size)
    /** Filters with an edit not yet written; flushed in onCleared. Main thread only. */
    private val dirty = HashSet<Int>()
    private var timer: CountdownTimer? = null

    init {
        viewModelScope.launch {
            filters.forEachIndexed { i, f -> values[i].loadFrom(store.load(f).toStringMap()) }
            revision.value++
        }
    }

    /** value must be the param's exact type (Float/Int/Boolean/String). */
    fun setParam(key: String, value: Any) {
        val i = filterIndex.value
        values[i].set(key, value)
        revision.value++
        dirty += i
        saveJobs[i]?.cancel()
        saveJobs[i] = viewModelScope.launch {
            delay(300)
            store.save(filters[i].id, values[i])
            dirty -= i // after the write: a save cancelled mid-way stays dirty for onCleared
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled: write pending edits on a scope that outlives the VM.
        val pending = dirty.map { filters[it].id to values[it].snapshot() }
        dirty.clear()
        if (pending.isNotEmpty()) CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            pending.forEach { (id, v) -> store.save(id, v) }
        }
    }

    fun setMode(m: CaptureMode) {
        cancelCountdown()
        mode.value = m
    }

    /** Stops a running photo countdown (mode/lens change must never let it capture later). */
    fun cancelCountdown() {
        timer?.cancel()
        timer = null
        countdown.value = 0
    }

    /** Swipe: +1 next, -1 previous, wrapping around. */
    fun stepFilter(delta: Int) {
        filterIndex.value = Math.floorMod(filterIndex.value + delta, filters.size)
    }

    fun cycleTimer() {
        timerSeconds.value = when (timerSeconds.value) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    fun cycleFlash() {
        flash.value = FlashMode.entries[(flash.value.ordinal + 1) % FlashMode.entries.size]
    }

    /** Photo: run (or cancel a running) countdown, then capture. Video: start/stop recording. */
    fun shutter(onCapturePhoto: () -> Unit, onToggleRecording: () -> Unit) {
        if (mode.value == CaptureMode.VIDEO) return onToggleRecording()
        if (timer != null) return cancelCountdown()
        val t = CountdownTimer(timerSeconds.value, { countdown.value = it }, {
            timer = null
            countdown.value = 0
            onCapturePhoto()
        }) { ms, f -> viewModelScope.launch { delay(ms); f() } }
        timer = t // before start(): a 0 s timer finishes inside start()
        t.start()
    }
}
