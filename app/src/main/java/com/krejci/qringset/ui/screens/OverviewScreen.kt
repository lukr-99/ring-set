package com.krejci.qringset.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.krejci.qringset.domain.ActivityScore
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
import kotlin.math.roundToInt

@Composable
fun OverviewScreen(vm: RingViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val hr by vm.samples(MetricType.HR).collectAsStateWithLifecycle(emptyList())
    val spo2 by vm.samples(MetricType.SPO2).collectAsStateWithLifecycle(emptyList())
    val hrv by vm.samples(MetricType.HRV).collectAsStateWithLifecycle(emptyList())
    val stress by vm.samples(MetricType.STRESS).collectAsStateWithLifecycle(emptyList())
    val steps by vm.samples(MetricType.STEPS).collectAsStateWithLifecycle(emptyList())
    val sleepSegs by vm.sleep().collectAsStateWithLifecycle(emptyList())

    // Insight inputs share a consistent 7-day window (except sleep = last night, steps = today).
    val since7 = remember { System.currentTimeMillis() / 1000 - 7 * 86_400 }
    fun avg7(list: List<com.krejci.qringset.data.SampleEntity>): Int? {
        val v = list.filter { it.epoch >= since7 }.map { it.value }
        return if (v.isEmpty()) null else v.average().roundToInt()
    }

    val computedRhr = remember(hr, since7) { StatsEngine.restingHr(hr.filter { it.epoch >= since7 }.map { it.value }) }
    val restingHr = profile.restingHr.takeIf { it > 0 } ?: computedRhr
    val avgSpo2 = remember(spo2) { avg7(spo2) }
    val avgHrv = remember(hrv) { avg7(hrv) }
    val avgStress = remember(stress) { avg7(stress) }
    val sleep = remember(sleepSegs) { StatsEngine.sleepSummary(sleepSegs.map { Triple(it.epoch, it.stage, it.durationMin) }) }
    val todayStart = remember { localMidnight() }
    val stepsToday = remember(steps) { if (steps.isEmpty()) null else steps.filter { it.epoch >= todayStart }.sumOf { it.value } }

    val score = remember(profile, restingHr, avgHrv, sleep, stepsToday) {
        StatsEngine.activityScore(stepsToday, profile.goalSteps, sleep.hours, profile.goalSleepHours, restingHr, avgHrv, profile.age)
    }
    var showBreakdown by remember { mutableStateOf(false) }

    val hi = if (profile.name.isNotBlank()) "Hi, ${profile.name}" else "Your day"
    ScreenHeader("Overview", hi,
        "A snapshot combining recent readings with your profile. The Wellness score (tap it for the " +
            "breakdown) blends steps (today), sleep (last night), resting HR and HRV (7-day). Each " +
            "insight is tagged with the window it covers. General guidance — not medical advice.")

    Spacer(Modifier.height(10.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable { showBreakdown = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArcGauge(score.score / 100f, MaterialTheme.colorScheme.primary, score.score.toString(), score.label)
                Text("Wellness ⓘ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        MiniTile("Resting HR", restingHr?.toString() ?: "—", "7-day", metricColor(MetricType.HR), Modifier.weight(1f))
        MiniTile("Sleep", if (sleep.totalMin > 0) fmtHours(sleep.hours) else "—", "last night", SleepColor, Modifier.weight(1f))
        MiniTile("SpO₂", avgSpo2?.toString() ?: "—", "7-day", metricColor(MetricType.SPO2), Modifier.weight(1f))
    }

    val insights = remember(profile, restingHr, avgSpo2, avgHrv, avgStress, sleep, stepsToday) {
        Interpretation.interpret(profile, restingHr, avgSpo2, avgHrv, sleep, stepsToday, avgStress)
    }

    SectionLabel("Insights")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (ins in insights) InsightCard(ins)
    }
    Spacer(Modifier.height(6.dp))

    if (showBreakdown) WellnessDialog(score) { showBreakdown = false }
}

@Composable
private fun WellnessDialog(score: ActivityScore, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Wellness ${score.score} · ${score.label}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("A weighted blend — each part contributes up to its share of 100. Missing data is left out and the rest re-weighted.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (score.parts.isEmpty()) {
                    Text("No data yet — sync the ring and fill in your profile.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for (p in score.parts) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                Text(p.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    Box(Modifier.fillMaxWidth((p.got / p.max).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("${p.got.roundToInt()}/${p.max.roundToInt()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ins.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (ins.basis.isNotBlank()) {
                        Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(ins.basis, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
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
