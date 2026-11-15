package com.krejci.halo.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.halo.data.MetricType
import com.krejci.halo.data.Sex
import com.krejci.halo.domain.StatsEngine
import com.krejci.halo.ui.Accent
import com.krejci.halo.ui.RingViewModel
import com.krejci.halo.ui.ThemeMode
import com.krejci.halo.ui.components.ChoiceChip
import com.krejci.halo.ui.components.ScreenHeader
import com.krejci.halo.ui.components.SectionLabel
import com.krejci.halo.ui.metricColor
import com.krejci.halo.update.AppRelease
import com.krejci.halo.update.AppUpdater
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(vm: RingViewModel, onOpenAbout: () -> Unit) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    var name by remember(profile) { mutableStateOf(profile.name) }
    var birth by remember(profile) { mutableStateOf(profile.birthYear.takeIf { it > 0 }?.toString() ?: "") }
    var sex by remember(profile) { mutableStateOf(profile.sex) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.takeIf { it > 0 }?.toString() ?: "") }
    var weight by remember(profile) { mutableStateOf(profile.weightKg.takeIf { it > 0 }?.toString() ?: "") }
    var rhr by remember(profile) { mutableStateOf(profile.restingHr.takeIf { it > 0 }?.toString() ?: "") }
    var goalSteps by remember(profile) { mutableStateOf(profile.goalSteps.toString()) }
    var goalSleep by remember(profile) { mutableStateOf(fmtHours(profile.goalSleepHours).removeSuffix("h")) }

    val context = LocalContext.current
    val hr by vm.samples(MetricType.HR).collectAsStateWithLifecycle(emptyList())
    val calcRhr = remember(hr) { StatsEngine.restingHr(hr.map { it.value }) }
    var showMeasure by remember { mutableStateOf(false) }

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
                NumField("Height (cm)", height, Modifier.weight(1f)) { height = it.take(3) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField("Weight (kg)", weight, Modifier.weight(1f)) { weight = it.take(3) }
                NumField("Resting HR", rhr, Modifier.weight(1f)) { rhr = it.take(3) }
            }
            Text("Sex", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Sex.entries) ChoiceChip(s.label, s == sex, Modifier.weight(1f)) { sex = s }
            }
        }
    }

    SectionLabel("Resting heart rate")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Type it above, pull it from your synced data, or measure it now while you sit still.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { calcRhr?.let { rhr = it.toString() } }, enabled = calcRhr != null, modifier = Modifier.weight(1f)) {
                    Text(if (calcRhr != null) "Use calc ($calcRhr)" else "No data yet")
                }
                Button(onClick = { showMeasure = true }, modifier = Modifier.weight(1f)) { Text("Measure now") }
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
                profile.copy(
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
            Toast.makeText(context, "Profile saved ✓", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save profile") }
    Spacer(Modifier.height(6.dp))

    SectionLabel("Appearance")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Theme", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in ThemeMode.entries) ChoiceChip(m.label, m == vm.themeMode, Modifier.weight(1f)) { vm.updateThemeMode(m) }
            }
            Text("Accent", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in Accent.entries) AccentSwatch(a, a == vm.accent) { vm.updateAccent(a) }
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    SectionLabel("Updates")
    UpdatesCard()
    Spacer(Modifier.height(6.dp))

    SectionLabel("About")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAbout),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("About Halo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("Who made it, send feedback, what it keeps, credits", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(6.dp))

    if (showMeasure) MeasureRestingDialog(vm, onDismiss = { showMeasure = false }) { rhr = it.toString(); showMeasure = false }
}

@Composable
private fun UpdatesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { AppUpdater(context, owner = "lukr-99", repo = "ring-set") }

    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AppRelease?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Halo v${updater.currentVersion}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            if (status.isNotBlank()) {
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        status = "Checking for updates…"
                        scope.launch {
                            val release = runCatching { updater.check() }.getOrNull()
                            busy = false
                            when {
                                release != null -> { pending = release; status = "Update available: v${release.versionName}" }
                                else -> status = "You're on the latest version."
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Check for updates") }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }

    val release = pending
    if (release != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = "Downloading v${release.versionName}…"
                        scope.launch {
                            val apk = runCatching { updater.download(release) }.getOrNull()
                            busy = false
                            if (apk != null) {
                                pending = null
                                status = "Launching installer…"
                                runCatching { updater.install(apk) }
                                    .onFailure { status = "Couldn't start the installer." }
                            } else {
                                status = "Download failed — try again later."
                            }
                        }
                    },
                ) { Text("Download & install") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Later") } },
            title = { Text("Halo v${release.versionName}") },
            text = {
                Text(
                    release.notes.ifBlank { "A new version is available." }.take(600),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

@Composable
private fun AccentSwatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val ringColor = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(accent.swatch)
            .border(2.5.dp, ringColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, accent.label, tint = Color(0xFF06121F), modifier = Modifier.size(20.dp))
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

@Composable
private fun MeasureRestingDialog(vm: RingViewModel, onDismiss: () -> Unit, onResult: (Int) -> Unit) {
    val measureSeconds = 90
    val live by vm.liveHr.collectAsStateWithLifecycle()
    val status by vm.liveStatus.collectAsStateWithLifecycle()
    val readings = remember { mutableStateListOf<Int>() }
    var secondsLeft by remember { mutableStateOf(measureSeconds) }
    var done by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        vm.startLiveHr()
        while (secondsLeft > 0) { delayOneSecond(); secondsLeft-- }
        vm.stopLiveHr()
        if (readings.isNotEmpty()) {
            val sorted = readings.sorted()
            val n = (sorted.size / 3).coerceAtLeast(1)
            result = sorted.take(n).average().roundToInt()
        }
        done = true
    }
    LaunchedEffect(live) { live?.let { if (!done) readings.add(it) } }

    AlertDialog(
        onDismissRequest = { vm.stopLiveHr(); onDismiss() },
        confirmButton = {
            val r = result
            if (done && r != null) TextButton(onClick = { onResult(r) }) { Text("Use $r bpm") }
            else TextButton(onClick = { vm.stopLiveHr(); onDismiss() }) { Text("Cancel") }
        },
        dismissButton = {
            if (done) TextButton(onClick = { vm.stopLiveHr(); onDismiss() }) { Text("Close") }
        },
        title = { Text("Measure resting HR") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!done) {
                    Text("Sit still, relax, and breathe normally.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(live?.toString() ?: "—", fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = metricColor(MetricType.HR))
                    Text("bpm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("${secondsLeft}s left · ${readings.size} reads", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status.isNotBlank() && status != "Live")
                        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else {
                    Text(result?.let { "Resting HR ≈ $it bpm" } ?: "No reading captured — make sure the ring is worn and QRing is closed.",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                }
            }
        },
    )
}

private suspend fun delayOneSecond() = kotlinx.coroutines.delay(1000)

private fun fmtHours(h: Float): String {
    val hours = h.toInt(); val mins = ((h - hours) * 60).toInt()
    return if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
}
