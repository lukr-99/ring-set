package com.krejci.qringset.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.domain.Insight
import com.krejci.qringset.domain.Interpretation
import com.krejci.qringset.domain.Severity
import com.krejci.qringset.domain.StatsEngine
import com.krejci.qringset.ui.RingViewModel
import com.krejci.qringset.ui.SleepColor
import com.krejci.qringset.ui.components.ArcGauge
import com.krejci.qringset.ui.components.ScreenHeader
import com.krejci.qringset.ui.components.SectionLabel
import com.krejci.qringset.ui.metricColor
import java.util.Calendar

@Composable
fun OverviewScreen(vm: RingViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val hr by vm.samples(MetricType.HR).collectAsStateWithLifecycle(emptyList())
    val spo2 by vm.samples(MetricType.SPO2).collectAsStateWithLifecycle(emptyList())
    val hrv by vm.samples(MetricType.HRV).collectAsStateWithLifecycle(emptyList())
    val steps by vm.samples(MetricType.STEPS).collectAsStateWithLifecycle(emptyList())
    val sleepSegs by vm.sleep().collectAsStateWithLifecycle(emptyList())

    val computedRhr = remember(hr) { StatsEngine.restingHr(hr.map { it.value }) }
    val restingHr = profile.restingHr.takeIf { it > 0 } ?: computedRhr
    val avgSpo2 = remember(spo2) { if (spo2.isEmpty()) null else spo2.map { it.value }.average().toInt() }
    val avgHrv = remember(hrv) { if (hrv.isEmpty()) null else hrv.map { it.value }.average().toInt() }
    val sleep = remember(sleepSegs) { StatsEngine.sleepSummary(sleepSegs.map { Triple(it.epoch, it.stage, it.durationMin) }) }
    val todayStart = remember { localMidnight() }
    val stepsToday = remember(steps) { if (steps.isEmpty()) null else steps.filter { it.epoch >= todayStart }.sumOf { it.value } }

    val score = remember(profile, restingHr, avgHrv, sleep, stepsToday) {
        StatsEngine.activityScore(stepsToday, profile.goalSteps, sleep.hours, profile.goalSleepHours, restingHr, avgHrv, profile.age)
    }

    val hi = if (profile.name.isNotBlank()) "Hi, ${profile.name}" else "Your day"
    ScreenHeader("Overview", hi,
        "A plain-language snapshot of your recent readings, combined with your profile to flag " +
            "what's typical and what's worth a look. The score blends steps, sleep, resting HR and " +
            "HRV. Reference ranges are general population guidance — informational, not medical advice.")

    Spacer(Modifier.height(10.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArcGauge(score.score / 100f, MaterialTheme.colorScheme.primary, score.score.toString(), score.label)
                Text("Activity score", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val goal = profile.goalSteps.coerceAtLeast(1)
                ArcGauge((stepsToday ?: 0).toFloat() / goal, metricColor(MetricType.STEPS),
                    stepsToday?.toString() ?: "—", "of $goal")
                Text("Steps today", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MiniTile("Resting HR", restingHr?.toString() ?: "—", "bpm", metricColor(MetricType.HR), Modifier.weight(1f))
        MiniTile("Sleep", if (sleep.totalMin > 0) fmtHours(sleep.hours) else "—", "last night", SleepColor, Modifier.weight(1f))
        MiniTile("SpO₂", avgSpo2?.toString() ?: "—", "%", metricColor(MetricType.SPO2), Modifier.weight(1f))
    }

    val insights = remember(profile, restingHr, avgSpo2, avgHrv, sleep, stepsToday) {
        Interpretation.interpret(profile, restingHr, avgSpo2, avgHrv, sleep, stepsToday)
    }

    SectionLabel("Insights")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (ins in insights) InsightCard(ins)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun InsightCard(ins: Insight) {
    val c = when (ins.severity) {
        Severity.GOOD -> Color(0xFF34D399)
        Severity.INFO -> MaterialTheme.colorScheme.primary
        Severity.WARN -> Color(0xFFFB7185)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(c))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ins.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(ins.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MiniTile(k: String, v: String, u: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 12.dp)) {
            Text(k.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
            Text(u, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun localMidnight(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}

private fun fmtHours(h: Float): String {
    val hours = h.toInt(); val mins = ((h - hours) * 60).toInt()
    return if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
}
