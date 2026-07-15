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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.ui.RingViewModel
import com.krejci.qringset.ui.SleepColor
import com.krejci.qringset.ui.components.ScreenHeader
import com.krejci.qringset.ui.components.SectionLabel
import com.krejci.qringset.ui.metricColor

@Composable
fun DataScreen(vm: RingViewModel, onExportShare: () -> Unit) {
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    ScreenHeader("Data", if (syncStatus.isBlank()) "Sync pulls the ring's logs into the app" else syncStatus,
        "The ring stores several days of readings on-device. \"Sync now\" pulls heart rate, steps, " +
            "SpO₂, sleep, stress and HRV into the app and keeps CSV copies you can share or pull " +
            "off with pull-data.ps1.")

    SectionLabel("Stored")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            for (m in MetricType.entries) {
                val c by vm.count(m).collectAsStateWithLifecycle(0)
                DataRow(m.label, "$c pts", metricColor(m))
            }
            val sc by vm.sleepCount().collectAsStateWithLifecycle(0)
            DataRow("Sleep", "$sc segs", SleepColor)
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { vm.sync() }, enabled = !syncing, modifier = Modifier.weight(1f)) {
            Text(if (syncing) "Syncing…" else "Sync now")
        }
        OutlinedButton(onClick = onExportShare, modifier = Modifier.weight(1f)) { Text("Export & share") }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        if (vm.lastSync > 0L) "Last synced ${fmtSync(vm.lastSync)}" else "Not synced yet",
        Modifier.fillMaxWidth(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )

    SectionLabel("Options")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-sync", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Pulls new data about every ${vm.lastInterval} min while the app is open", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.autoSyncEnabled, onCheckedChange = { vm.setAutoSync(it) })
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Continuous HR logging", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Now in the Control tab under \"Background logging\", with keep-alive and battery controls.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DataRow(name: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private fun fmtSync(ms: Long) = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(ms))
