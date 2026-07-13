package com.krejci.qringset.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.krejci.qringset.ui.RingViewModel
import com.krejci.qringset.ui.SleepColor
import com.krejci.qringset.ui.components.ArcGauge
import com.krejci.qringset.ui.components.Hypnogram
import com.krejci.qringset.ui.components.ScreenHeader
import com.krejci.qringset.ui.components.SleepVitals
import com.krejci.qringset.ui.components.SleepGoalDial
import com.krejci.qringset.ui.components.sleepStageColor
import com.krejci.qringset.ui.components.sleepStageLabel
import com.krejci.qringset.ui.metricColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SleepScreen(vm: RingViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val segEntities by vm.sleep().collectAsStateWithLifecycle(emptyList())
    val hr by vm.samples(MetricType.HR).collectAsStateWithLifecycle(emptyList())
    val spo2 by vm.samples(MetricType.SPO2).collectAsStateWithLifecycle(emptyList())

    val nights = remember(segEntities, profile.goalSleepHours) {
        SleepEngine.nights(segEntities.map { SleepSegment(it.epoch, it.stage, it.durationMin) }, profile.goalSleepHours)
    }
    // The chooser is a fixed week of day-slots so a day with no data still shows (an empty slot).
    val week = remember(nights) { buildWeek(nights) }
    var selected by remember(week) {
        mutableStateOf(week.indexOfLast { it.night != null }.let { if (it >= 0) it else week.lastIndex })
    }
    val slot = week.getOrNull(selected)
    val night = slot?.night ?: NightSleep.EMPTY
    val sleepHr = remember(hr, night) {
        hr.filter { it.epoch in night.start..night.end }.map { Point(it.epoch, it.value.toFloat()) }
    }
    val avgHr = if (sleepHr.isEmpty()) null else sleepHr.map { it.value }.average().roundToInt()
    val spo2InWindow = remember(spo2, night) { spo2.filter { it.epoch in night.start..night.end } }
    val sleepSpo2 = remember(spo2InWindow) { spo2InWindow.map { Point(it.epoch, it.value.toFloat()) } }
    val avgSpo2 = if (spo2InWindow.isEmpty()) null else spo2InWindow.map { it.value }.average().roundToInt()

    var showGoal by remember { mutableStateOf(false) }

    ScreenHeader(
        "Sleep",
        when {
            night.hasData -> "Night of ${fmtDay(night.start)} · ${fmtHM(night.asleepMin)}"
            nights.isEmpty() -> "No sleep recorded yet"
            slot != null -> "No sleep recorded for ${fmtDayMid(slot.dayMidnight)}"
            else -> "No sleep recorded yet"
        },
        "Your recent nights from the ring. Pick a day in the strip; the hypnogram shows the stages " +
            "through that night, and the score blends how long you slept vs your goal, your deep- and " +
            "REM-sleep share, and how efficiently you slept.",
    )

    if (nights.isEmpty()) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Sync the ring on the Data tab after a night's sleep.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // ---- day chooser (a fixed week; empty days still show) ----
    SleepLabel("Recent nights")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        WeekStrip(week, selected, Modifier.padding(12.dp)) { selected = it }
    }

    if (!night.hasData) {
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No sleep recorded", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("The ring logs sleep automatically while you wear it — nothing was recorded for this night.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        // ---- hero: duration + score ----
        Spacer(Modifier.height(14.dp))
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

        // ---- sleep vitals: HR + SpO2 overlaid on their own axes ----
        SleepLabel("During sleep")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    LegendVital(metricColor(MetricType.HR), "Heart rate", avgHr?.let { "$it bpm" } ?: "—")
                    LegendVital(metricColor(MetricType.SPO2), "Blood oxygen", avgSpo2?.let { "$it%" } ?: "—")
                }
                Spacer(Modifier.height(10.dp))
                if (sleepHr.size >= 2 || sleepSpo2.size >= 2) {
                    SleepVitals(sleepHr, sleepSpo2, metricColor(MetricType.HR), metricColor(MetricType.SPO2))
                } else {
                    Text("Not enough overnight HR/SpO₂ to chart.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // ---- goal ----
    SleepLabel("Goal")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sleep goal · ${fmtHours(profile.goalSleepHours)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Bedtime ${fmtClock(profile.bedtimeMin)} · Wake ${fmtClock(profile.wakeMin)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    if (showGoal) SleepGoalSheet(profile.bedtimeMin, profile.wakeMin, onDismiss = { showGoal = false }) { b, w ->
        vm.saveProfile(profile.copy(bedtimeMin = b, wakeMin = w, goalSleepHours = ((w - b + 1440) % 1440) / 60f))
        showGoal = false
    }
}

private data class DaySlot(val dayMidnight: Long, val night: NightSleep?)

/** A fixed week of day-slots (sleep-duration bars); a day with no data still shows, dimmed with "–". */
@Composable
private fun WeekStrip(week: List<DaySlot>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val maxMin = (week.mapNotNull { it.night?.asleepMin }.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        week.forEachIndexed { i, s ->
            val n = s.night
            val on = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) SleepColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp, horizontal = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(fmtDowMid(s.dayMidnight), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = if (on) SleepColor else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.height(42.dp).width(11.dp), contentAlignment = Alignment.BottomCenter) {
                    if (n != null) {
                        Box(
                            Modifier.width(11.dp)
                                .fillMaxHeight((n.asleepMin.toFloat() / maxMin).coerceIn(0.08f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (on) SleepColor else SleepColor.copy(alpha = 0.4f)),
                        )
                    } else {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(if (n != null) fmtHShort(n.asleepMin) else "–", fontSize = 10.sp,
                    color = if (n != null && on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Local-midnight epoch of the sleep-day (noon cutoff) that a night starting at [startEpoch] belongs to. */
private fun localMidnight(epoch: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = epoch * 1000
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}

/** A fixed [days]-day window ending at the current sleep-day; each slot holds its night, or null. */
private fun buildWeek(nights: List<NightSleep>, days: Int = 7): List<DaySlot> {
    val byDay = nights.associateBy { localMidnight(it.start - 12 * 3600) }
    val todayMid = localMidnight(System.currentTimeMillis() / 1000 - 12 * 3600)
    val out = ArrayList<DaySlot>(days)
    val c = Calendar.getInstance()
    for (i in days - 1 downTo 0) {
        c.timeInMillis = todayMid * 1000; c.add(Calendar.DAY_OF_MONTH, -i)
        val dm = c.timeInMillis / 1000
        out.add(DaySlot(dm, byDay[dm]))
    }
    return out
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
private fun LegendVital(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
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
private fun SleepGoalSheet(bed: Int, wake: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var b by remember { mutableStateOf(bed) }
    var w by remember { mutableStateOf(wake) }
    val windowMin = (w - b + 1440) % 1440
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(b, w) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Sleep goal") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Drag the bedtime and wake-up handles.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SleepGoalDial(b, w, onChange = { nb, nw -> b = nb; w = nw })
                Spacer(Modifier.height(10.dp))
                Text("Sleep time: ${fmtHM(windowMin)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SleepColor)
            }
        },
    )
}

@Composable
private fun SleepLabel(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 2.dp))

private fun fmtHM(min: Int): String { val h = min / 60; val m = min % 60; return if (h > 0) "${h}h ${m}m" else "${m}m" }
private fun fmtHShort(min: Int): String { val h = min / 60; val m = min % 60; return if (m >= 30) "${h + 1}h" else "${h}h" }
// Formatters for a sleep-day midnight (already resolved by the noon cutoff, so no shift here).
private fun fmtDowMid(e: Long) = SimpleDateFormat("EEE", Locale.US).format(Date(e * 1000))
private fun fmtDayMid(e: Long) = SimpleDateFormat("MMM d", Locale.US).format(Date(e * 1000))
// The "night of" a session is the day it began (noon cutoff): a bedtime after midnight still
// belongs to the previous calendar day, not the morning you woke on.
private fun sleepDay(startEpoch: Long) = startEpoch - 12 * 3600
private fun fmtHours(h: Float): String { val i = h.toInt(); val m = ((h - i) * 60).roundToInt(); return if (m > 0) "${i}h ${m}m" else "${i}h" }
private fun fmtTime(e: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(e * 1000))
private fun fmtDay(e: Long) = SimpleDateFormat("MMM d", Locale.US).format(Date(sleepDay(e) * 1000))
private fun fmtClock(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
