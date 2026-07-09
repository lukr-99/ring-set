package com.krejci.qringset.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.ble.Conn
import com.krejci.qringset.data.ActivityType
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.ui.RingViewModel
import com.krejci.qringset.ui.components.ChoiceChip
import com.krejci.qringset.ui.components.ScreenHeader
import com.krejci.qringset.ui.components.SectionLabel
import com.krejci.qringset.ui.metricColor
import kotlinx.coroutines.delay

@Composable
fun ActivityScreen(vm: RingViewModel) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val active by vm.workoutActive.collectAsStateWithLifecycle()
    val live by vm.liveHr.collectAsStateWithLifecycle()
    val liveStatus by vm.liveStatus.collectAsStateWithLifecycle()
    val samples by vm.workoutSamples.collectAsStateWithLifecycle()
    val start by vm.workoutStart.collectAsStateWithLifecycle()
    val history by vm.workouts().collectAsStateWithLifecycle(emptyList())
    var type by remember { mutableStateOf(ActivityType.WORKOUT) }
    var showMark by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(active) { while (active) { now = System.currentTimeMillis() / 1000; delay(1000) } }

    ScreenHeader("Activity", if (active) "Recording…" else "Real-time heart rate",
        "Measure your heart rate live — e.g. during a lift or a run. Start a session and the ring " +
            "streams your pulse; stop it to save a summary (avg / max / duration). Live readings " +
            "aren't part of the background logs, so a session is the way to catch a workout.")

    Spacer(Modifier.height(16.dp))
    Card(colors = CardDefaults.cardColors(containerColor = metricColor(MetricType.HR).copy(alpha = 0.14f)), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(live?.toString() ?: "—", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = metricColor(MetricType.HR))
            Text("bpm", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (active) {
                Spacer(Modifier.height(10.dp))
                Text(fmtElapsed((now - start).coerceAtLeast(0)), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                val avg = if (samples.isNotEmpty()) samples.average().toInt() else 0
                val mx = samples.maxOrNull() ?: 0
                Text("avg $avg · max $mx · ${samples.size} reads", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (liveStatus.isNotBlank() && liveStatus != "Live") {
                    Spacer(Modifier.height(4.dp))
                    Text(liveStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (conn != Conn.CONNECTED) {
                Spacer(Modifier.height(6.dp))
                Text("Ring will connect when you start · close the QRing app first", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    if (!active) {
        SectionLabel("Activity type")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in ActivityType.entries.take(3)) ChoiceChip(t.label, t == type, Modifier.weight(1f)) { type = t }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in ActivityType.entries.drop(3)) ChoiceChip(t.label, t == type, Modifier.weight(1f)) { type = t }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.startWorkout(type) }, modifier = Modifier.fillMaxWidth()) { Text("Start ${type.label}") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { showMark = true }, modifier = Modifier.fillMaxWidth()) { Text("Mark activity (no live HR)") }
    } else {
        Button(
            onClick = { vm.stopWorkout() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFB7185), contentColor = Color.White),
        ) { Text("Stop & save") }
    }

    if (showMark) MarkActivityDialog(onDismiss = { showMark = false }) { mins ->
        vm.markActivity(type, mins); showMark = false
        Toast.makeText(context, "Activity logged — alerts muted for ${mins}m", Toast.LENGTH_SHORT).show()
    }

    if (history.isNotEmpty()) {
        SectionLabel("Recent sessions")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                for (w in history.take(8)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.type, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(fmtDate(w.startEpoch), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (w.samples > 0) "${w.avgHr} avg · ${w.maxHr} max" else "no HR", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text("${(w.endEpoch - w.startEpoch) / 60}m", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = metricColor(MetricType.HR))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MarkActivityDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var mins by remember { mutableStateOf(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(mins) }) { Text("Log ${mins}m") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Mark activity") },
        text = {
            Column {
                Text("Log an activity window without live monitoring. HR alerts stay quiet during it.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(30, 60, 120)) ChoiceChip("${m}m", m == mins, Modifier.weight(1f)) { mins = m }
                }
            }
        },
    )
}

private fun fmtElapsed(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

private fun fmtDate(epoch: Long): String =
    java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.US).format(java.util.Date(epoch * 1000))
