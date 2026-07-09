package com.krejci.qringset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A 24-hour ring where you drag a bedtime handle and a wake-up handle to set the sleep-goal
 * window (like QRing's "Set a goal"). 0 is at the top, 6 right, 12 bottom, 18 left.
 */
@Composable
fun SleepGoalDial(
    bedMin: Int,
    wakeMin: Int,
    onChange: (bed: Int, wake: Int) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Int = 250,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val tickArgb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f).toArgb()
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val density = LocalDensity.current

    val state = rememberUpdatedState(bedMin to wakeMin)
    var dragging by remember { mutableStateOf(-1) } // 0 = bedtime, 1 = wake

    val d = diameter.dp
    val ringInsetDp = 28
    val ringRadiusDp = diameter / 2f - ringInsetDp

    Box(modifier.size(d), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                val cx = size.width / 2f; val cy = size.height / 2f
                fun minuteAt(pos: Offset): Int {
                    var deg = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat()
                    var top = (deg + 90f) % 360f; if (top < 0) top += 360f
                    return (Math.round(top / 360f * 1440f / 5f) * 5) % 1440
                }
                detectDragGestures(
                    onDragStart = { pos ->
                        val m = minuteAt(pos)
                        val (b, w) = state.value
                        dragging = if (circDist(m, b) <= circDist(m, w)) 0 else 1
                        if (dragging == 0) onChange(m, w) else onChange(b, m)
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                    onDrag = { change, _ ->
                        val m = minuteAt(change.position)
                        val (b, w) = state.value
                        if (dragging == 0) onChange(m, w) else onChange(b, m)
                    },
                )
            }
        ) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val r = size.minDimension / 2f - with(density) { ringInsetDp.dp.toPx() }
            val ringW = with(density) { 16.dp.toPx() }
            drawCircle(track, r, Offset(cx, cy), style = Stroke(ringW))
            // ticks every 15 min
            for (i in 0 until 96) {
                val a = Math.toRadians((i / 96f * 360f - 90f).toDouble())
                val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                val inner = r - ringW * 0.5f - 4f
                val len = if (i % 4 == 0) 12f else 6f
                drawContext.canvas.nativeCanvas.drawLine(
                    cx + inner * ca, cy + inner * sa, cx + (inner - len) * ca, cy + (inner - len) * sa,
                    android.graphics.Paint().apply { color = tickArgb; strokeWidth = 2f; isAntiAlias = true },
                )
            }
            // hour labels
            val lp = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
            val lr = r - ringW - 22f
            listOf(0 to "0", 6 to "6", 12 to "12", 18 to "18").forEach { (h, s) ->
                val a = Math.toRadians((h / 24f * 360f - 90f).toDouble())
                drawContext.canvas.nativeCanvas.drawText(s, cx + lr * cos(a).toFloat(), cy + lr * sin(a).toFloat() + 9f, lp)
            }
            // active window arc bed -> wake (clockwise)
            val sweep = ((wakeMin - bedMin + 1440) % 1440) / 1440f * 360f
            val startDeg = bedMin / 1440f * 360f - 90f
            drawArc(primary, startDeg, sweep, false, topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
                style = Stroke(ringW, cap = StrokeCap.Round))
        }

        // center readout
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(fmtMin(bedMin), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Alarm, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(fmtMin(wakeMin), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        // draggable handles (overlay icons; touch is handled by the Canvas beneath)
        HandleIcon(bedMin, ringRadiusDp, diameter, Icons.Rounded.Bedtime, primary, onPrimary)
        HandleIcon(wakeMin, ringRadiusDp, diameter, Icons.Rounded.Alarm, primary, onPrimary)
    }
}

@Composable
private fun HandleIcon(min: Int, ringRadiusDp: Float, diameter: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, fg: Color) {
    val a = Math.toRadians((min / 1440f * 360f - 90f).toDouble())
    val half = diameter / 2f
    val hx = (half + ringRadiusDp * cos(a) - 15).roundToInt()
    val hy = (half + ringRadiusDp * sin(a) - 15).roundToInt()
    Box(
        Modifier.offset(hx.dp, hy.dp).size(30.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp)) }
}

/** Circular distance in minutes between two times of day. */
private fun circDist(a: Int, b: Int): Int { val d = Math.abs(a - b) % 1440; return minOf(d, 1440 - d) }
private fun fmtMin(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
