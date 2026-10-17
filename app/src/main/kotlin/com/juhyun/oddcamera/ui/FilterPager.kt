package com.juhyun.oddcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juhyun.oddcamera.gl.GlUtil
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Gesture layer over the full-screen preview: horizontal swipe → [onSwipe] (+1 next / -1 previous),
 * pinch → [onZoom] (scale factor, never a swipe), tap inside the letterboxed frame → [onFocus] with
 * frame-normalized coords plus a focus ring. Shows [title] briefly whenever it changes.
 * frameW:frameH = preview frame aspect, drawn aspect-fit (GlUtil.aspectFit) in this layer's bounds.
 */
@Composable
fun FilterPager(
    title: String,
    frameW: Int,
    frameH: Int,
    onSwipe: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    onFocus: (Float, Float) -> Unit,
) {
    var ring by remember { mutableStateOf<Offset?>(null) }
    var showTitle by remember { mutableStateOf(false) }
    LaunchedEffect(title) { showTitle = true; delay(1200); showTitle = false }
    LaunchedEffect(ring) { if (ring != null) { delay(800); ring = null } }

    Box(
        Modifier.fillMaxSize().pointerInput(frameW, frameH) {
            val swipeMin = 60.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var pinched = false
                var drag = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    if (event.changes.count { it.pressed } > 1) pinched = true
                    if (pinched) {
                        val z = event.calculateZoom()
                        if (z != 1f) onZoom(z)
                    } else {
                        event.changes.firstOrNull { it.id == down.id }?.let { drag += it.positionChange() }
                    }
                    event.changes.forEach { it.consume() }
                }
                when {
                    pinched -> Unit
                    abs(drag.x) > swipeMin && abs(drag.x) > abs(drag.y) -> onSwipe(if (drag.x < 0) 1 else -1)
                    drag.getDistance() < viewConfiguration.touchSlop -> {
                        val (rx, ry, rw, rh) = GlUtil.aspectFit(frameW, frameH, size.width, size.height)
                        val p = down.position
                        // Taps on the letterbox bars are ignored.
                        if (p.x >= rx && p.x <= rx + rw && p.y >= ry && p.y <= ry + rh) {
                            ring = p
                            onFocus((p.x - rx) / rw, (p.y - ry) / rh)
                        }
                    }
                }
            }
        },
    ) {
        ring?.let { c ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White, radius = 36.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            }
        }
        if (showTitle) {
            Text(
                title, color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
