package com.juhyun.oddcamera.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juhyun.oddcamera.camera.CameraController
import com.juhyun.oddcamera.camera.FlashMode
import com.juhyun.oddcamera.filter.BoolParam
import com.juhyun.oddcamera.filter.ChoiceParam
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FloatParam
import com.juhyun.oddcamera.filter.IntParam
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.TextParam
import com.juhyun.oddcamera.render.RenderThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 5f)

/** Camera UI over the full-screen [preview]: controls, gestures, countdown, param panel. */
@Composable
fun CameraScreen(
    vm: CameraViewModel,
    camera: CameraController,
    renderThread: RenderThread,
    onShutter: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val index by vm.filterIndex.collectAsState()
    val mode by vm.mode.collectAsState()
    val flash by vm.flash.collectAsState()
    val torch by vm.torch.collectAsState()
    val timerSeconds by vm.timerSeconds.collectAsState()
    val countdown by vm.countdown.collectAsState()
    val capturing by vm.capturing.collectAsState()
    val recordingSince by vm.recordingSince.collectAsState()
    val zoom by camera.zoomRatio.collectAsState()
    val zoomRange by camera.zoomRange.collectAsState()
    val hasFlash by camera.hasFlash.collectAsState()
    var panelOpen by remember { mutableStateOf(false) }
    val filter = vm.filters[index]

    LaunchedEffect(index) { renderThread.setFilter(filter, vm.values[index]) }
    LaunchedEffect(mode) {
        camera.setAspect(mode.aspect) // also the initial bind
        if (mode == CaptureMode.PHOTO && vm.torch.value) { vm.torch.value = false; camera.setTorch(false) }
    }
    LaunchedEffect(flash) { camera.setFlash(flash) }
    LaunchedEffect(Unit) { camera.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            preview()
            val (fw, fh) = if (mode == CaptureMode.PHOTO) 3 to 4 else 9 to 16
            FilterPager(
                title = filter.title, frameW = fw, frameH = fh,
                onSwipe = vm::stepFilter,
                onZoom = { camera.setZoomRatio(camera.zoomRatio.value * it) },
                onFocus = camera::focusAt,
            )
            recordingSince?.let { RecordingTime(it, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp)) }
            if (countdown > 0) {
                Text("$countdown", color = Color.White, fontSize = 120.sp, modifier = Modifier.align(Alignment.Center))
            }

            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (mode == CaptureMode.PHOTO) {
                        // No flash unit (front camera): greyed out and shown off, since capture forces it off.
                        Control(if (!hasFlash) "플래시 끔" else when (flash) { FlashMode.OFF -> "플래시 끔"; FlashMode.AUTO -> "플래시 자동"; FlashMode.ON -> "플래시 켬" },
                            enabled = hasFlash) { vm.cycleFlash() }
                    } else {
                        Control(if (torch) "토치 켬" else "토치 끔") { vm.torch.value = !torch; camera.setTorch(!torch) }
                    }
                    Control(if (timerSeconds == 0) "타이머 끔" else "타이머 ${timerSeconds}초") { vm.cycleTimer() }
                    Control("조정", selected = panelOpen) { panelOpen = !panelOpen }
                    Control("전환") { vm.cancelCountdown(); vm.torch.value = false; camera.switchLens() }
                }
                Spacer(Modifier.weight(1f))
                if (panelOpen) ParamPanel(vm, filter, vm.values[index])
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    for (z in ZOOM_PRESETS.filter { it in zoomRange }) {
                        Control(if (z < 1f) "${z}x" else "${z.toInt()}x", selected = abs(zoom - z) < 0.05f) { camera.setZoomRatio(z) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LastCaptureThumbnail(vm)
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (capturing) Color.Gray else Color.White)
                            .border(4.dp, Color.Gray, CircleShape)
                            .clickable(onClick = onShutter),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Video: red dot to start, red square while recording.
                        if (mode == CaptureMode.VIDEO) Box(
                            if (recordingSince != null) Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(RED)
                            else Modifier.size(56.dp).clip(CircleShape).background(RED),
                        )
                    }
                    Column {
                        Control("사진", selected = mode == CaptureMode.PHOTO) { vm.setMode(CaptureMode.PHOTO) }
                        Control("동영상", selected = mode == CaptureMode.VIDEO) { vm.setMode(CaptureMode.VIDEO) }
                    }
                }
            }
        }
    }
}

private val RED = Color(0xFFE53935)

/** Red dot + elapsed mm:ss since `since` (SystemClock.elapsedRealtime()). */
@Composable
private fun RecordingTime(since: Long, modifier: Modifier) {
    val elapsed by produceState(0L, since) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - since) / 1000
            delay(1000 - (SystemClock.elapsedRealtime() - since) % 1000)
        }
    }
    Row(modifier.background(Color(0x80000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(RED))
        Spacer(Modifier.width(6.dp))
        Text("%02d:%02d".format(elapsed / 60, elapsed % 60), color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun Control(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled) {
        Text(text, color = when { !enabled -> Color.Gray; selected -> Color(0xFFFFD54F); else -> Color.White })
    }
}

@Composable
private fun LastCaptureThumbnail(vm: CameraViewModel) {
    val context = LocalContext.current
    val uri by vm.lastCapture.collectAsState()
    val thumb by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(u, Size(160, 160), null).asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.DarkGray).clickable(enabled = uri != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) {}
        },
    ) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Generated from filter.params; writes go through the ViewModel (live preview + debounced save). */
@Composable
private fun ParamPanel(vm: CameraViewModel, filter: Filter, values: ParamValues) {
    vm.revision.collectAsState().value // recompose on every change
    Column(
        Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Color(0xB3000000))
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (filter.params.isEmpty()) Text("조정할 항목이 없습니다", color = Color.White)
        val customChosen = filter.params.any { it is ChoiceParam && values.string(it.key) == "custom" }
        for (p in filter.params.filter { q -> q.visibleWhen?.let { (k, v) -> values.string(k) == v } ?: true }) when (p) {
            is FloatParam -> {
                val v = values.float(p.key)
                Text("${p.label}  ${if (p.step < 1f) "%.1f".format(v) else v.roundToInt()}", color = Color.White)
                Slider(v, { x ->
                    if (x.isFinite()) vm.setParam(p.key, (p.min + ((x - p.min) / p.step).roundToInt() * p.step).coerceIn(p.min, p.max))
                }, valueRange = p.min..p.max)
            }
            is IntParam -> {
                val v = values.int(p.key)
                Text("${p.label}  $v", color = Color.White)
                Slider(v.toFloat(), { x -> if (x.isFinite()) vm.setParam(p.key, x.roundToInt()) },
                    valueRange = p.min.toFloat()..p.max.toFloat(), steps = (p.max - p.min - 1).coerceAtLeast(0))
            }
            is BoolParam -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, color = Color.White, modifier = Modifier.weight(1f))
                Switch(values.bool(p.key), { vm.setParam(p.key, it) })
            }
            is ChoiceParam -> {
                Text(p.label, color = Color.White)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((id, label) in p.options) {
                        FilterChip(values.string(p.key) == id, { vm.setParam(p.key, id) }, { Text(label) })
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            is TextParam -> if (customChosen) {
                OutlinedTextField(values.string(p.key), { vm.setParam(p.key, it) }, Modifier.fillMaxWidth(),
                    label = { Text(p.label) }, singleLine = true)
            }
        }
    }
}
