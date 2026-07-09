package com.krejci.qringset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.data.Point
import com.krejci.qringset.data.SleepSegment
import com.krejci.qringset.domain.NightSleep
import com.krejci.qringset.domain.SleepEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SleepScreen(vm: RingViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val segEntities by vm.sleep().collectAsStateWithLifecycle(emptyList())
    val hr by vm.samples(MetricType.HR).collectAsStateWithLifecycle(emptyList())
    val spo2 by vm.samples(MetricType.SPO2).collectAsStateWithLifecycle(emptyList())

    val night = remember(segEntities, profile.goalSleepHours) {
        SleepEngine.analyze(segEntities.map { SleepSegment(it.epoch, it.stage, it.durationMin) }, profile.goalSleepHours)
    }
    val sleepHr = remember(hr, night) {
        hr.filter { it.epoch in night.start..night.end }.map { Point(it.epoch, it.value.toFloat()) }
    }
    val avgHr = if (sleepHr.isEmpty()) null else sleepHr.map { it.value }.average().roundToInt()
    val spo2InWindow = remember(spo2, night) { spo2.filter { it.epoch in night.start..night.end } }
    val avgSpo2 = if (spo2InWindow.isEmpty()) null else spo2InWindow.map { it.value }.average().roundToInt()

    var showGoal by remember { mutableStateOf(false) }

    ScreenHeader(
        "Sleep",
        if (night.hasData) "Night of ${fmtDay(night.start)} · ${fmtHM(night.asleepMin)}" else "No sleep recorded yet",
        "Your most recent night from the ring. The hypnogram shows the stages through the night; the " +
            "score blends how long you slept vs your goal, your deep- and REM-sleep share, and how " +
            "efficiently you slept. Set your target with the goal card.",
    )

    if (!night.hasData) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Sync the ring on the Data tab after a night's sleep.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // ---- hero: duration + score ----
    Spacer(Modifier.height(12.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(fmtHM(night.asleepMin), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
                Text("${fmtTime(night.start)} – ${fmtTime(night.end)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(night.quality, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SleepColor)
                Text("${night.efficiencyPct}% efficient · ${night.awakenings} wake-ups", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ArcGauge(night.score / 100f, SleepColor, night.score.toString(), "score", diameter = 128)
        }
    }

    // ---- hypnogram ----
    SleepLabel("Sleep stages")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (stage in listOf(5, 4, 2, 3)) LegendDot(stage)
            }
            Spacer(Modifier.height(10.dp))
            Hypnogram(night.segments)
        }
    }

    // ---- stage breakdown ----
    SleepLabel("Breakdown")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (stage in listOf(3, 2, 4, 5)) {
                val mins = night.stageMin(stage)
                val pct = if (night.asleepMin > 0 && stage != 5) mins.toFloat() / night.asleepMin else 0f
                StageBar(stage, mins, pct)
            }
        }
    }

    // ---- sleep vitals ----
    SleepLabel("During sleep")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Heart rate", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(avgHr?.let { "$it bpm avg" } ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (sleepHr.size >= 2) { Spacer(Modifier.height(8.dp)); MiniLine(sleepHr, metricColor(MetricType.HR)) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Blood oxygen", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(avgSpo2?.let { "$it% avg" } ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }

    // ---- goal ----
    SleepLabel("Goal")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sleep goal · ${fmtHours(profile.goalSleepHours)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                val reached = (night.asleepMin / (profile.goalSleepHours * 60f)).coerceIn(0f, 1f)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(reached).height(8.dp).clip(CircleShape).background(SleepColor))
                }
                Text("${(reached * 100).roundToInt()}% of goal last night", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { showGoal = true }) { Text("Set goal") }
        }
    }
    Spacer(Modifier.height(6.dp))

    if (showGoal) GoalDialog(profile.goalSleepHours, onDismiss = { showGoal = false }) { h ->
        vm.saveProfile(profile.copy(goalSleepHours = h)); showGoal = false
    }
}

@Composable
private fun LegendDot(stage: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(sleepStageColor(stage)))
        Spacer(Modifier.width(5.dp))
        Text(sleepStageLabel(stage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StageBar(stage: Int, mins: Int, pct: Float) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(sleepStageColor(stage)))
            Spacer(Modifier.width(10.dp))
            Text(sleepStageLabel(stage), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(fmtHM(mins), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (stage != 5) Text("  ${(pct * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).height(7.dp).clip(CircleShape).background(sleepStageColor(stage)))
        }
    }
}

@Composable
private fun GoalDialog(current: Float, onDismiss: () -> Unit, onSave: (Float) -> Unit) {
    var h by remember { mutableStateOf(current.coerceIn(4f, 12f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(h) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Sleep goal") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Target hours of sleep per night", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Stepper("–") { h = (h - 0.5f).coerceAtLeast(4f) }
                    Text(fmtHours(h), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = SleepColor)
                    Stepper("+") { h = (h + 0.5f).coerceAtMost(12f) }
                }
            }
        },
    )
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun SleepLabel(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 2.dp))

private fun fmtHM(min: Int): String { val h = min / 60; val m = min % 60; return if (h > 0) "${h}h ${m}m" else "${m}m" }
private fun fmtHours(h: Float): String { val i = h.toInt(); val m = ((h - i) * 60).roundToInt(); return if (m > 0) "${i}h ${m}m" else "${i}h" }
private fun fmtTime(e: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(e * 1000))
private fun fmtDay(e: Long) = SimpleDateFormat("MMM d", Locale.US).format(Date(e * 1000))
