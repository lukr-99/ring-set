package com.krejci.qringset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Stepped hypnogram: colored blocks per stage across the night. Tap or drag across it to select a
 * segment; a readout shows the stage, its length, and the from–to times.
 */
@Composable
fun Hypnogram(segments: List<SleepSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    val axisArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val scrubColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.onSurface
    val total = segments.sumOf { it.durationMin }.coerceAtLeast(1)
    var selected by remember(segments) { mutableStateOf(-1) }

    fun indexAt(xFrac: Float): Int {
        val target = xFrac.coerceIn(0f, 1f) * total
        var acc = 0
        segments.forEachIndexed { i, s -> acc += s.durationMin; if (target <= acc) return i }
        return segments.size - 1
    }

    Column(modifier) {
        // ---- detail readout ----
        Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.CenterStart) {
            val sel = segments.getOrNull(selected)
            if (sel != null) {
                val endEpoch = sel.epoch + sel.durationMin * 60L
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(sleepStageColor(sel.stage)))
                    Spacer(Modifier.width(8.dp))
                    Text(sleepStageLabel(sel.stage), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${fmtDur(sel.durationMin)} · ${fmtClk(sel.epoch)}–${fmtClk(endEpoch)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            } else {
                Text("Tap or drag across the chart to inspect a stage",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))

        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .pointerInput(segments) { detectTapGestures { pos -> selected = indexAt(pos.x / size.width) } }
                .pointerInput(segments) {
                    detectDragGestures(
                        onDragStart = { pos -> selected = indexAt(pos.x / size.width) },
                        onDrag = { change, _ -> selected = indexAt(change.position.x / size.width) },
                    )
                },
        ) {
            val bottomAxis = 22f
            val plotH = size.height - bottomAxis
            val rowH = plotH / 4
            var acc = 0
            var selX0 = -1f; var selX1 = -1f
            for ((i, s) in segments.withIndex()) {
                val row = STAGE_ROW[s.stage]
                if (row == null) { acc += s.durationMin; continue }
                val x0 = acc.toFloat() / total * size.width
                val x1 = (acc + s.durationMin).toFloat() / total * size.width
                acc += s.durationMin
                val top = row * rowH + rowH * 0.18f
                val h = rowH * 0.64f
                val w = (x1 - x0 - 1.5f).coerceAtLeast(1.5f)
                val isSel = i == selected
                drawRoundRect(
                    color = sleepStageColor(s.stage).copy(alpha = if (selected < 0 || isSel) 1f else 0.55f),
                    topLeft = Offset(x0, top), size = Size(w, h), cornerRadius = CornerRadius(4f, 4f),
                )
                if (isSel) { selX0 = x0; selX1 = x0 + w }
            }
            // scrubber highlight over the selected segment
            if (selX0 >= 0f) {
                drawRoundRect(
                    color = outlineColor.copy(alpha = 0.9f),
                    topLeft = Offset(selX0 - 1.5f, 0f), size = Size(selX1 - selX0 + 3f, plotH),
                    cornerRadius = CornerRadius(5f, 5f), style = Stroke(width = 2f),
                )
                val cx = (selX0 + selX1) / 2f
                drawLine(scrubColor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, plotH), strokeWidth = 1.5f)
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

private fun fmtDur(min: Int): String { val h = min / 60; val m = min % 60; return if (h > 0) "${h}h ${m}m" else "${m}m" }
private fun fmtClk(e: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(e * 1000))
