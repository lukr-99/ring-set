package com.krejci.qringset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.krejci.qringset.data.Point
import com.krejci.qringset.data.SleepSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sleep-stage accent colors. Stage codes: 2 light, 3 deep, 4 rem, 5 awake. */
fun sleepStageColor(stage: Int): Color = when (stage) {
    5 -> Color(0xFFFBBF24) // awake — amber
    4 -> Color(0xFFC4B5FD) // rem — light violet
    2 -> Color(0xFF818CF8) // light — violet
    3 -> Color(0xFF5B54E0) // deep — deep indigo
    else -> Color(0xFF64748B)
}

fun sleepStageLabel(stage: Int) = when (stage) { 5 -> "Awake"; 4 -> "REM"; 2 -> "Light"; 3 -> "Deep"; else -> "?" }

// Row order top→bottom: Awake, REM, Light, Deep.
private val STAGE_ROW = mapOf(5 to 0, 4 to 1, 2 to 2, 3 to 3)

/** Stepped hypnogram: colored blocks per stage across the night. */
@Composable
fun Hypnogram(segments: List<SleepSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    val axisArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val total = segments.sumOf { it.durationMin }.coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val bottomAxis = 22f
        val plotH = size.height - bottomAxis
        val rows = 4
        val rowH = plotH / rows
        var acc = 0
        for (s in segments) {
            val row = STAGE_ROW[s.stage] ?: continue
            val x0 = acc.toFloat() / total * size.width
            val x1 = (acc + s.durationMin).toFloat() / total * size.width
            acc += s.durationMin
            val top = row * rowH + rowH * 0.18f
            val h = rowH * 0.64f
            val w = (x1 - x0 - 1.5f).coerceAtLeast(1.5f)
            drawRoundRect(
                color = sleepStageColor(s.stage),
                topLeft = Offset(x0, top),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }
        // start/end time labels
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val paint = android.graphics.Paint().apply { isAntiAlias = true; color = axisArgb; textSize = 24f }
        val startStr = fmt.format(Date(segments.first().epoch * 1000))
        val end = segments.last().epoch + segments.last().durationMin * 60L
        val endStr = fmt.format(Date(end * 1000))
        drawContext.canvas.nativeCanvas.drawText(startStr, 0f, size.height - 2f, paint)
        drawContext.canvas.nativeCanvas.drawText(endStr, size.width - paint.measureText(endStr), size.height - 2f, paint)
    }
}

/** Compact read-only line chart (no axes/scrubbing) for the sleep HR trace. */
@Composable
fun MiniLine(points: List<Point>, color: Color, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        val lo = points.minOf { it.value }; val hi = points.maxOf { it.value }
        val span = (hi - lo).coerceAtLeast(1f)
        fun px(i: Int) = i.toFloat() / (points.size - 1) * size.width
        fun py(v: Float) = size.height - (v - lo) / span * (size.height * 0.82f) - size.height * 0.09f
        val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(px(i), py(p.value)) else lineTo(px(i), py(p.value)) } }
        val fill = Path().apply {
            moveTo(px(0), size.height); points.forEachIndexed { i, p -> lineTo(px(i), py(p.value)) }
            lineTo(px(points.size - 1), size.height); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.4f))
    }
}
