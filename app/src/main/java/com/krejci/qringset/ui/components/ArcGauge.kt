package com.krejci.qringset.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlin.math.cos
import kotlin.math.sin

/**
 * A tachometer-style arc gauge (like a car RPM clock, in the app's style): a 270° track with
 * tick marks, a coloured progress sweep, a redline near the top, and a value in the centre.
 */
@Composable
fun ArcGauge(
    progress: Float,          // 0f..1f (values >1 clamp the arc but the centre text can show more)
    color: Color,
    centerValue: String,
    centerLabel: String,
    modifier: Modifier = Modifier,
    diameter: Int = 150,
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val redline = Color(0xFFFB7185)
    val onSurf = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant

    val startDeg = 135f
    val sweepDeg = 270f

    Box(modifier.size(diameter.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.10f
            val pad = stroke / 2f + size.minDimension * 0.10f
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            val topLeft = Offset(pad, pad)
            val cx = size.width / 2f; val cy = size.height / 2f
            val rOuter = arcSize.minDimension / 2f + stroke * 0.35f
            val rInner = arcSize.minDimension / 2f - stroke * 0.75f

            // track
            drawArc(track, startDeg, sweepDeg, false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round))
            // redline (last 15%)
            drawArc(redline.copy(alpha = 0.35f), startDeg + sweepDeg * 0.85f, sweepDeg * 0.15f, false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Butt))
            // progress
            if (p > 0f) drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color)),
                startDeg, sweepDeg * p, false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // ticks
            val ticks = 10
            for (i in 0..ticks) {
                val ang = Math.toRadians((startDeg + sweepDeg * i / ticks).toDouble())
                val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                drawLine(tickColor, Offset(cx + rInner * ca, cy + rInner * sa),
                    Offset(cx + rOuter * ca, cy + rOuter * sa), strokeWidth = 2.5f)
            }
            // endpoint glow
            val endAng = Math.toRadians((startDeg + sweepDeg * p).toDouble())
            val rMid = arcSize.minDimension / 2f
            val ex = cx + rMid * cos(endAng).toFloat(); val ey = cy + rMid * sin(endAng).toFloat()
            drawCircle(color, stroke * 0.5f, Offset(ex, ey))
            drawCircle(color.copy(alpha = 0.35f), stroke * 0.9f, Offset(ex, ey), style = Stroke(2f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerValue, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = onSurf)
            Text(centerLabel, fontSize = 11.sp, color = onVar)
        }
    }
}
