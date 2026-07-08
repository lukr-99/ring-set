package com.krejci.qringset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.ble.Conn
import com.krejci.qringset.data.ActivityType
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.data.Sex
import com.krejci.qringset.data.UserProfile
import com.krejci.qringset.domain.Insight
import com.krejci.qringset.domain.Interpretation
import com.krejci.qringset.domain.Severity
import com.krejci.qringset.domain.StatsEngine
import kotlinx.coroutines.delay
import java.util.Calendar

// =====================================================================================
// OVERVIEW  (health summary + evidence-informed insights)
// =====================================================================================

@Composable
fun OverviewScreen(vm: RingViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val hr by vm.samples(MetricType.HR).collectAsStateWithLifecycle(emptyList())
    val spo2 by vm.samples(MetricType.SPO2).collectAsStateWithLifecycle(emptyList())
    val hrv by vm.samples(MetricType.HRV).collectAsStateWithLifecycle(emptyList())
    val steps by vm.samples(MetricType.STEPS).collectAsStateWithLifecycle(emptyList())
    val sleepSegs by vm.sleep().collectAsStateWithLifecycle(emptyList())

    val restingHr = remember(hr) { StatsEngine.restingHr(hr.map { it.value }) }
    val avgSpo2 = remember(spo2) { if (spo2.isEmpty()) null else spo2.map { it.value }.average().toInt() }
    val avgHrv = remember(hrv) { if (hrv.isEmpty()) null else hrv.map { it.value }.average().toInt() }
    val sleep = remember(sleepSegs) { StatsEngine.sleepSummary(sleepSegs.map { Triple(it.epoch, it.stage, it.durationMin) }) }
    val todayStart = remember { localMidnight() }
    val stepsToday = remember(steps) { if (steps.isEmpty()) null else steps.filter { it.epoch >= todayStart }.sumOf { it.value } }

    val insights = remember(profile, restingHr, avgSpo2, avgHrv, sleep, stepsToday) {
        Interpretation.interpret(profile, restingHr, avgSpo2, avgHrv, sleep, stepsToday)
    }

    val hi = if (profile.name.isNotBlank()) "Hi, ${profile.name}" else "Your day"
    ScreenHeader("Overview", hi,
        "A plain-language snapshot of your recent readings, combined with your profile to flag " +
            "what's typical and what's worth a look. Reference ranges are general population " +
            "guidance — informational, not medical advice.")

    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MiniTile("Resting HR", restingHr?.toString() ?: "—", "bpm", metricColor(MetricType.HR), Modifier.weight(1f))
        MiniTile("Sleep", if (sleep.totalMin > 0) fmtHours(sleep.hours) else "—", "last night", SleepColor, Modifier.weight(1f))
    }
    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MiniTile("Steps today", stepsToday?.toString() ?: "—", "steps", metricColor(MetricType.STEPS), Modifier.weight(1f))
        MiniTile("Avg SpO₂", avgSpo2?.toString() ?: "—", "%", metricColor(MetricType.SPO2), Modifier.weight(1f))
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

// =====================================================================================
// ACTIVITY  (real-time HR / workout recording)
// =====================================================================================

@Composable
fun ActivityScreen(vm: RingViewModel) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val active by vm.workoutActive.collectAsStateWithLifecycle()
    val live by vm.liveHr.collectAsStateWithLifecycle()
    val samples by vm.workoutSamples.collectAsStateWithLifecycle()
    val start by vm.workoutStart.collectAsStateWithLifecycle()
    val history by vm.workouts().collectAsStateWithLifecycle(emptyList())
    var type by remember { mutableStateOf(ActivityType.WORKOUT) }

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
            } else if (conn != Conn.CONNECTED) {
                Spacer(Modifier.height(6.dp))
                Text("Ring will connect when you start", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    } else {
        Button(
            onClick = { vm.stopWorkout() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFB7185), contentColor = Color.White),
        ) { Text("Stop & save") }
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
                        Text("${w.avgHr} avg · ${w.maxHr} max", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text("${(w.endEpoch - w.startEpoch) / 60}m", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = metricColor(MetricType.HR))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

// =====================================================================================
// PROFILE  (user-entered values that drive interpretation)
// =====================================================================================

@Composable
fun ProfileScreen(vm: RingViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    var name by remember(profile) { mutableStateOf(profile.name) }
    var birth by remember(profile) { mutableStateOf(profile.birthYear.takeIf { it > 0 }?.toString() ?: "") }
    var sex by remember(profile) { mutableStateOf(profile.sex) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.takeIf { it > 0 }?.toString() ?: "") }
    var weight by remember(profile) { mutableStateOf(profile.weightKg.takeIf { it > 0 }?.toString() ?: "") }
    var rhr by remember(profile) { mutableStateOf(profile.restingHr.takeIf { it > 0 }?.toString() ?: "") }
    var goalSteps by remember(profile) { mutableStateOf(profile.goalSteps.toString()) }
    var goalSleep by remember(profile) { mutableStateOf(fmtHours(profile.goalSleepHours).removeSuffix("h")) }

    val age = birth.toIntOrNull()?.let { if (it in 1900..2100) Calendar.getInstance().get(Calendar.YEAR) - it else null }

    ScreenHeader("You", if (age != null) "Age $age" else "Your profile",
        "These values personalise your insights — age adjusts the HRV bands, resting HR and your " +
            "goals set the targets on the Overview tab. Stored only on your phone.")

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField("Birth year", birth, Modifier.weight(1f)) { birth = it.take(4) }
                NumField("Resting HR", rhr, Modifier.weight(1f)) { rhr = it.take(3) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField("Height (cm)", height, Modifier.weight(1f)) { height = it.take(3) }
                NumField("Weight (kg)", weight, Modifier.weight(1f)) { weight = it.take(3) }
            }
            Text("Sex", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Sex.entries) ChoiceChip(s.label, s == sex, Modifier.weight(1f)) { sex = s }
            }
        }
    }

    SectionLabel("Goals")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumField("Daily steps", goalSteps, Modifier.weight(1f)) { goalSteps = it.take(6) }
            NumField("Sleep (h)", goalSleep, Modifier.weight(1f)) { goalSleep = it.take(2) }
        }
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            vm.saveProfile(
                UserProfile(
                    name = name.trim(),
                    birthYear = birth.toIntOrNull() ?: 0,
                    sex = sex,
                    heightCm = height.toIntOrNull() ?: 0,
                    weightKg = weight.toIntOrNull() ?: 0,
                    restingHr = rhr.toIntOrNull() ?: 0,
                    goalSteps = goalSteps.toIntOrNull() ?: 8000,
                    goalSleepHours = goalSleep.toFloatOrNull() ?: 8f,
                )
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save profile") }
    Spacer(Modifier.height(6.dp))
}

// =====================================================================================
// shared bits
// =====================================================================================

@Composable
private fun SectionLabel(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 2.dp))

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

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme.primary
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (selected) c else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) c else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
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

private fun fmtElapsed(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

private fun fmtDate(epoch: Long): String =
    java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.US).format(java.util.Date(epoch * 1000))
