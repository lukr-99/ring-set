package com.krejci.qringset.ui.components

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A dual-axis overnight chart: heart rate (left axis) and blood oxygen (right axis) plotted over the
 * same time span, each on its own scale with min/max value labels so both lines are readable.
 */
@Composable
fun SleepVitals(hr: List<Point>, spo2: List<Point>, hrColor: Color, spo2Color: Color, modifier: Modifier = Modifier) {
    if (hr.size < 2 && spo2.size < 2) return
    val axisArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val hrArgb = hrColor.toArgb()
    val spo2Argb = spo2Color.toArgb()
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val epochs = (hr + spo2).map { it.epoch }
        val t0 = epochs.min(); val t1 = epochs.max().coerceAtLeast(t0 + 1)
        val leftInset = 42f; val rightInset = 46f; val topPad = 12f; val bottomAxis = 24f
        val plotTop = topPad; val plotBottom = size.height - bottomAxis
        fun px(e: Long) = leftInset + (e - t0).toFloat() / (t1 - t0) * (size.width - leftInset - rightInset)

        fun drawSeries(pts: List<Point>, color: Color, fill: Boolean) {
            if (pts.size < 2) return
            val lo = pts.minOf { it.value }; val hi = pts.maxOf { it.value }
            val span = (hi - lo).coerceAtLeast(1f)
            fun py(v: Float) = plotBottom - (v - lo) / span * (plotBottom - plotTop)
            if (fill) {
                val fp = Path().apply {
                    moveTo(px(pts.first().epoch), plotBottom)
                    pts.forEach { lineTo(px(it.epoch), py(it.value)) }
                    lineTo(px(pts.last().epoch), plotBottom); close()
                }
                drawPath(fp, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), startY = plotTop, endY = plotBottom))
            }
            val lp = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(px(p.epoch), py(p.value)) else lineTo(px(p.epoch), py(p.value)) } }
            drawPath(lp, color, style = Stroke(width = 2.4f))
        }

        // gridlines
        val grid = Color.White.copy(alpha = 0.05f)
        drawLine(grid, Offset(leftInset, plotTop), Offset(size.width - rightInset, plotTop), 1f)
        drawLine(grid, Offset(leftInset, plotBottom), Offset(size.width - rightInset, plotBottom), 1f)

        drawSeries(hr, hrColor, fill = true)
        drawSeries(spo2, spo2Color, fill = false)

        // axis labels — HR on the left (its own scale), SpO2 on the right
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 22f }
        if (hr.size >= 2) {
            paint.color = hrArgb
            drawContext.canvas.nativeCanvas.drawText(hr.maxOf { it.value }.roundToInt().toString(), 2f, plotTop + 16f, paint)
            drawContext.canvas.nativeCanvas.drawText(hr.minOf { it.value }.roundToInt().toString(), 2f, plotBottom, paint)
        }
        if (spo2.size >= 2) {
            paint.color = spo2Argb
            val rx = size.width - rightInset + 4f
            drawContext.canvas.nativeCanvas.drawText(spo2.maxOf { it.value }.roundToInt().toString(), rx, plotTop + 16f, paint)
            drawContext.canvas.nativeCanvas.drawText(spo2.minOf { it.value }.roundToInt().toString(), rx, plotBottom, paint)
        }
        // x time labels
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        paint.color = axisArgb
        drawContext.canvas.nativeCanvas.drawText(fmt.format(Date(t0 * 1000)), leftInset, size.height - 4f, paint)
        val endStr = fmt.format(Date(t1 * 1000))
        drawContext.canvas.nativeCanvas.drawText(endStr, size.width - rightInset - paint.measureText(endStr), size.height - 4f, paint)
    }
}
