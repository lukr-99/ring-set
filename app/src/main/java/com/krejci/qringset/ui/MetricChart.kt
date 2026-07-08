package com.krejci.qringset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.krejci.qringset.data.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private fun DrawScope.series(points: List<Point>, a: Float, b: Float, color: Color, main: Boolean, axisArgb: Int) {
    val n = points.size
    val i0 = floor(a * (n - 1)).toInt().coerceIn(0, n - 1)
    val i1 = ceil(b * (n - 1)).toInt().coerceIn(0, n - 1)
    val seg = points.subList(i0, (i1 + 1).coerceAtMost(n))
    if (seg.size < 2) return
    val rawLo = seg.minOf { it.value }; val rawHi = seg.maxOf { it.value }
    val pad = ((rawHi - rawLo) * 0.15f).coerceAtLeast(1f)
    val lo = rawLo - pad; val hi = rawHi + pad

    val leftInset = if (main) 42f else 1f
    val rightPad = if (main) 8f else 1f
    val topPad = if (main) 10f else 5f
    val bottomInset = if (main) 18f else 0f
    val plotTop = topPad
    val plotBottom = size.height - bottomInset
    fun px(i: Int) = leftInset + i.toFloat() / (seg.size - 1) * (size.width - leftInset - rightPad)
    fun py(v: Float) = plotBottom - (v - lo) / (hi - lo) * (plotBottom - plotTop)

    if (main) {
        val grid = Color.White.copy(alpha = 0.06f)
        val paint = android.graphics.Paint().apply { isAntiAlias = true; this.color = axisArgb; textSize = 22f }
        for (g in 0..3) {
            val gy = plotTop + g * (plotBottom - plotTop) / 3
            drawLine(grid, Offset(leftInset, gy), Offset(size.width, gy), 1f)
            val value = (hi - g * (hi - lo) / 3).roundToInt()
            drawContext.canvas.nativeCanvas.drawText(value.toString(), 3f, gy + 7f, paint)
        }
        // X time labels — several ticks across the window, not just the ends
        val span = seg.last().epoch - seg.first().epoch
        val fmt = SimpleDateFormat(if (span < 2 * 86400) "HH:mm" else "MMM d", Locale.US)
        val ticks = 4
        for (t in 0 until ticks) {
            val idx = (t.toFloat() / (ticks - 1) * (seg.size - 1)).roundToInt().coerceIn(0, seg.size - 1)
            val label = fmt.format(Date(seg[idx].epoch * 1000))
            val w = paint.measureText(label)
            val x = when (t) {
                0 -> leftInset
                ticks - 1 -> size.width - rightPad - w
                else -> px(idx) - w / 2f
            }.coerceIn(0f, size.width - w)
            drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 3f, paint)
        }
    }

    val fill = Path().apply {
        moveTo(px(0), plotBottom)
        seg.forEachIndexed { i, p -> lineTo(px(i), py(p.value)) }
        lineTo(px(seg.size - 1), plotBottom); close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0f)), startY = plotTop, endY = plotBottom))
    val line = Path().apply { seg.forEachIndexed { i, p -> if (i == 0) moveTo(px(i), py(p.value)) else lineTo(px(i), py(p.value)) } }
    drawPath(line, color, style = Stroke(width = if (main) 3f else 1.6f))
    if (main) {
        val lx = px(seg.size - 1); val ly = py(seg.last().value)
        drawCircle(color, 4.5f, Offset(lx, ly))
        drawCircle(color.copy(alpha = 0.4f), 9f, Offset(lx, ly), style = Stroke(2f))
    }
}

@Composable
fun MetricChart(
    points: List<Point>,
    color: Color,
    window: Pair<Float, Float>,
    onWindow: (Pair<Float, Float>) -> Unit,
) {
    if (points.size < 2) {
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            Text("No data yet — pull it in on the Data tab", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val axisArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val win = rememberUpdatedState(window)
    Column {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) { series(points, window.first, window.second, color, true, axisArgb) }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val wpx = constraints.maxWidth.toFloat()
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) { series(points, 0f, 1f, color.copy(alpha = 0.55f), false, axisArgb) }
            val a = window.first; val b = window.second
            Box(
                Modifier
                    .offset { IntOffset((a * wpx).roundToInt(), 0) }
                    .width(with(density) { ((b - a) * wpx).toDp() })
                    .fillMaxHeight()
                    .background(color.copy(alpha = 0.16f))
                    .border(1.dp, color, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            val (ca, cb) = win.value; val w = cb - ca; val d = drag.x / wpx
                            var na = ca + d; var nb = cb + d
                            if (na < 0f) { na = 0f; nb = w }; if (nb > 1f) { nb = 1f; na = 1f - w }
                            onWindow(na to nb)
                        }
                    }
            ) {
                Box(Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            val (ca, cb) = win.value; val na = (ca + drag.x / wpx).coerceIn(0f, cb - 0.05f)
                            onWindow(na to cb)
                        }
                    })
                Box(Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            val (ca, cb) = win.value; val nb = (cb + drag.x / wpx).coerceIn(ca + 0.05f, 1f)
                            onWindow(ca to nb)
                        }
                    })
            }
        }
    }
}
