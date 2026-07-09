package com.krejci.qringset.ui.components

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Shared pinch-to-zoom + pan state for the custom Canvas charts (hypnogram, sleep vitals).
 *
 * [start] is the left edge of the visible window as a fraction of the whole data span [0,1]; the
 * visible window is `[start, start + width]` where `width = 1/scale`. Map a normalised data
 * position `u∈[0,1]` to a screen x with [x]; map a screen fraction back to a data fraction with
 * [frac].
 */
class ZoomPan(private val maxScale: Float = 8f) {
    var scale by mutableFloatStateOf(1f)
    var start by mutableFloatStateOf(0f)

    val zoomed: Boolean get() = scale > 1.001f
    val width: Float get() = 1f / scale

    fun reset() { scale = 1f; start = 0f }

    /** Apply one transform gesture: [centroidX]/[panX] in px, [zoom] the pinch factor, [viewW] px. */
    fun apply(centroidX: Float, panX: Float, zoom: Float, viewW: Float) {
        if (viewW <= 0f) return
        val ns = (scale * zoom).coerceIn(1f, maxScale)
        val vwOld = 1f / scale
        val vwNew = 1f / ns
        // Keep the point under the gesture centroid anchored while zooming, then apply the pan.
        val cFrac = start + (centroidX / viewW) * vwOld
        start = (cFrac - (centroidX / viewW) * vwNew - (panX / viewW) * vwNew)
            .coerceIn(0f, (1f - vwNew).coerceAtLeast(0f))
        scale = ns
    }

    /** Underlying data fraction [0,1] at a screen fraction [viewFrac] of the plot. */
    fun frac(viewFrac: Float): Float = (start + viewFrac * width).coerceIn(0f, 1f)

    /** Screen x for a normalised data position [u]∈[0,1] within a plot `[left, left+plotW]`. */
    fun x(u: Float, left: Float, plotW: Float): Float = left + (u - start) / width * plotW
}

/** Remembered [ZoomPan]; pass the chart's data as [keys] so zoom resets when the data changes. */
@Composable
fun rememberZoomPan(vararg keys: Any?): ZoomPan = remember(*keys) { ZoomPan() }

/** Drives [z] from pinch/pan gestures on the element. Coexists with a tap detector on the same node. */
fun Modifier.zoomPan(z: ZoomPan): Modifier = pointerInput(z) {
    detectTransformGestures { centroid, pan, zoom, _ ->
        z.apply(centroid.x, pan.x, zoom, size.width.toFloat())
    }
}
