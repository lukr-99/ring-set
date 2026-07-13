package com.krejci.qringset.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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

private fun DrawScope.series(
    points: List<Point>, a: Float, b: Float, color: Color, main: Boolean, axisArgb: Int,
    markerCanvasFrac: Float? = null, unit: String = "", bubbleBgArgb: Int = 0,
) {
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

    // Draw the trace in continuous runs; bridge missing-data gaps with a dashed line and no fill.
    val strokeW = if (main) 3f else 1.6f
    val brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0f)), startY = plotTop, endY = plotBottom)
    val thr = gapThresholdSeconds(seg.map { it.epoch })
    var s0 = 0
    while (s0 < seg.size) {
        var s1 = s0
        while (s1 + 1 < seg.size && seg[s1 + 1].epoch - seg[s1].epoch <= thr) s1++
        if (s1 > s0) {
            val fp = Path().apply {
                moveTo(px(s0), plotBottom)
                for (k in s0..s1) lineTo(px(k), py(seg[k].value))
                lineTo(px(s1), plotBottom); close()
            }
            drawPath(fp, brush)
            val lp = Path().apply { for (k in s0..s1) if (k == s0) moveTo(px(k), py(seg[k].value)) else lineTo(px(k), py(seg[k].value)) }
            drawPath(lp, color, style = Stroke(width = strokeW))
        } else {
            drawCircle(color, strokeW * 0.9f, Offset(px(s0), py(seg[s0].value)))
        }
        if (s1 + 1 < seg.size) {
            drawLine(color.copy(alpha = 0.5f), Offset(px(s1), py(seg[s1].value)), Offset(px(s1 + 1), py(seg[s1 + 1].value)),
                strokeWidth = strokeW * 0.7f, pathEffect = GapDash)
        }
        s0 = s1 + 1
    }
    if (main) {
        val lx = px(seg.size - 1); val ly = py(seg.last().value)
        drawCircle(color, 4.5f, Offset(lx, ly))
        drawCircle(color.copy(alpha = 0.4f), 9f, Offset(lx, ly), style = Stroke(2f))
    }

    // ---- tap-to-read marker: crosshair + value/time bubble at the nearest point ----
    if (main && markerCanvasFrac != null) {
        val localX = ((markerCanvasFrac * size.width - leftInset) / (size.width - leftInset - rightPad)).coerceIn(0f, 1f)
        val idx = (localX * (seg.size - 1)).roundToInt().coerceIn(0, seg.size - 1)
        val mx = px(idx); val my = py(seg[idx].value)
        drawLine(Color(axisArgb).copy(alpha = 0.45f), Offset(mx, plotTop), Offset(mx, plotBottom), 1.5f)
        drawCircle(color, 5f, Offset(mx, my))
        drawCircle(Color.White, 2f, Offset(mx, my))

        val vStr = seg[idx].value.roundToInt().toString() + if (unit.isNotEmpty()) " $unit" else ""
        val tStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(seg[idx].epoch * 1000))
        val tp = android.graphics.Paint().apply { isAntiAlias = true; textSize = 24f }
        val bw = maxOf(tp.measureText(vStr), tp.measureText(tStr)) + 18f
        val bh = 60f
        val bx = (mx - bw / 2f).coerceIn(leftInset, (size.width - rightPad - bw).coerceAtLeast(leftInset))
        val by = plotTop + 2f
        drawContext.canvas.nativeCanvas.drawRoundRect(bx, by, bx + bw, by + bh, 9f, 9f,
            android.graphics.Paint().apply { isAntiAlias = true; this.color = bubbleBgArgb })
        drawContext.canvas.nativeCanvas.drawRoundRect(bx, by, bx + bw, by + bh, 9f, 9f,
            android.graphics.Paint().apply {
                isAntiAlias = true; this.color = color.copy(alpha = 0.6f).toArgb()
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f
            })
        tp.color = color.toArgb()
        drawContext.canvas.nativeCanvas.drawText(vStr, bx + 9f, by + 25f, tp)
        tp.color = axisArgb
        drawContext.canvas.nativeCanvas.drawText(tStr, bx + 9f, by + 49f, tp)
    }
}

@Composable
fun MetricChart(
    points: List<Point>,
    color: Color,
    window: Pair<Float, Float>,
    onWindow: (Pair<Float, Float>) -> Unit,
    unit: String = "",
) {
    if (points.size < 2) {
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            Text("No data yet — pull it in on the Data tab", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val axisArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val bubbleBgArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val win = rememberUpdatedState(window)
    // Tap/drag a point on the main chart to read it; cleared when the visible window changes.
    var marker by remember(points, window) { mutableStateOf<Float?>(null) }
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(points) { detectTapGestures { pos -> marker = pos.x / size.width } }
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { pos -> marker = pos.x / size.width },
                        onDrag = { change, _ -> marker = change.position.x / size.width },
                    )
                },
        ) { series(points, window.first, window.second, color, true, axisArgb, marker, unit, bubbleBgArgb) }
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
