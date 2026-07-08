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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.krejci.qringset.data.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private fun DrawScope.series(points: List<Point>, a: Float, b: Float, color: Color, main: Boolean) {
    val n = points.size
    val i0 = floor(a * (n - 1)).toInt().coerceIn(0, n - 1)
    val i1 = ceil(b * (n - 1)).toInt().coerceIn(0, n - 1)
    val seg = points.subList(i0, (i1 + 1).coerceAtMost(n))
    if (seg.size < 2) return
    var lo = seg.minOf { it.value }; var hi = seg.maxOf { it.value }
    val pad = ((hi - lo) * 0.22f).coerceAtLeast(1f); lo -= pad; hi += pad
    val padL = if (main) 6f else 1f
    val topPad = if (main) 8f else 5f
    fun px(i: Int) = padL + i.toFloat() / (seg.size - 1) * (size.width - padL * 2)
    fun py(v: Float) = size.height - topPad - (v - lo) / (hi - lo) * (size.height - topPad * 2)

    if (main) {
        val grid = Color.White.copy(alpha = 0.06f)
        for (g in 0..3) {
            val y = topPad + g * (size.height - topPad * 2) / 3
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
    val fill = Path().apply {
        moveTo(px(0), size.height)
        seg.forEachIndexed { i, p -> lineTo(px(i), py(p.value)) }
        lineTo(px(seg.size - 1), size.height); close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0f))))
    val line = Path().apply {
        seg.forEachIndexed { i, p -> if (i == 0) moveTo(px(i), py(p.value)) else lineTo(px(i), py(p.value)) }
    }
    drawPath(line, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (main) 3f else 1.6f))
    if (main) {
        val lx = px(seg.size - 1); val ly = py(seg.last().value)
        drawCircle(color, 4.5f, Offset(lx, ly))
        drawCircle(color.copy(alpha = 0.4f), 9f, Offset(lx, ly), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
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
    val win = rememberUpdatedState(window)
    Column {
        Canvas(Modifier.fillMaxWidth().height(190.dp)) { series(points, window.first, window.second, color, true) }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val wpx = constraints.maxWidth.toFloat()
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) { series(points, 0f, 1f, color.copy(alpha = 0.55f), false) }
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
