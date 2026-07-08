package com.krejci.qringset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.data.Point
import com.krejci.qringset.ble.Conn
import com.krejci.qringset.domain.StatsEngine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Screen(val label: String, val icon: ImageVector) {
    STATS("Stats", Icons.Rounded.ShowChart),
    RING("Ring", Icons.Rounded.Adjust),
    CONTROL("Control", Icons.Rounded.Tune),
    DATA("Data", Icons.Rounded.Sync),
}

@Composable
fun App(vm: RingViewModel, onExportShare: () -> Unit, onScan: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.STATS) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 104.dp)
        ) {
            when (screen) {
                Screen.STATS -> StatsScreen(vm)
                Screen.RING -> RingScreen(vm, onScan)
                Screen.CONTROL -> ControlScreen(vm)
                Screen.DATA -> DataScreen(vm, onExportShare)
            }
        }
        FloatingNav(screen, { screen = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }
}

@Composable
private fun FloatingNav(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in Screen.entries) {
                val on = s == current
                Column(
                    Modifier.clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(s) }
                        .padding(horizontal = 15.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(s.icon, s.label, tint = tint, modifier = Modifier.size(22.dp))
                    Text(s.label, color = tint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable private fun Label(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 2.dp))

@Composable
private fun MetricChips(selected: MetricType, onSelect: (MetricType) -> Unit) {
    // Fitted tab row — all metrics share the width, no sideways scrolling.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in MetricType.entries) {
            val on = m == selected
            val c = metricColor(m)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, if (on) c else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(m) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.short, color = if (on) c else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// ---------------- STATS ----------------

@Composable
fun StatsScreen(vm: RingViewModel) {
    var metric by remember { mutableStateOf(MetricType.HR) }
    var window by remember { mutableStateOf(0f to 1f) }
    val entities by vm.samples(metric).collectAsStateWithLifecycle(emptyList())
    val points = remember(entities) { entities.map { Point(it.epoch, it.value.toFloat()) } }
    val color = metricColor(metric)

    Text("Insights", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
    Text("${metric.label} · ${points.size} readings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)

    Spacer(Modifier.height(14.dp))
    MetricChips(metric) { metric = it; window = 0f to 1f }

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            MetricChart(points, color, window) { window = it }
            Spacer(Modifier.height(4.dp))
            Text("Drag the window to scrub · pull the edges to zoom",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }

    // stats over the visible window
    val n = points.size
    val slice = if (n >= 2) {
        val i0 = floor(window.first * (n - 1)).toInt().coerceIn(0, n - 1)
        val i1 = ceil(window.second * (n - 1)).toInt().coerceIn(0, n - 1)
        points.subList(i0, (i1 + 1).coerceAtMost(n))
    } else points
    val summary = StatsEngine.summarize(slice.map { it.value.roundToInt() })

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StatTile("Average", if (summary.count > 0) summary.avg.roundToInt().toString() else "—", metric.unit, color, Modifier.weight(1f))
        StatTile("Min", if (summary.count > 0) summary.min.toString() else "—", "in view", color, Modifier.weight(1f))
        StatTile("Max", if (summary.count > 0) summary.max.toString() else "—", "in view", color, Modifier.weight(1f))
        StatTile("Latest", summary.latest?.toString() ?: "—", metric.unit, color, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(k: String, v: String, u: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(vertical = 11.dp, horizontal = 10.dp)) {
            Text(k.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(u, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- CONTROL ----------------

@Composable
fun ControlScreen(vm: RingViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    Text("Control", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
    Text("Heart-rate logging interval", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }

    Label("Quick presets")
    val presets = listOf(1, 3, 5, 10, 30, 60)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in presets.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in row) Preset(p, p == vm.lastInterval, Modifier.weight(1f)) { vm.setInterval(p) }
            }
        }
    }

    Label("Options")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reconnect after setting", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Makes the new interval stick", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.autoReconnect, onCheckedChange = { vm.updateAuto(it) })
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.readInterval() }, modifier = Modifier.weight(1f)) { Text("Check ring") }
        Button(onClick = { vm.reconnect() }, modifier = Modifier.weight(1f)) { Text("Reconnect") }
    }
}

@Composable
private fun Preset(min: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$min min", fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------- DATA ----------------

@Composable
fun DataScreen(vm: RingViewModel, onExportShare: () -> Unit) {
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    Text("Data", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
    Text(if (syncStatus.isBlank()) "Sync pulls the ring's logs into the app" else syncStatus,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)

    Label("Stored")
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

// ---------------- RING ----------------

@Composable
fun RingScreen(vm: RingViewModel, onScan: () -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val rings by vm.rings().collectAsStateWithLifecycle(emptyList())
    val scanResults by vm.scanResults.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    Text("My ring", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
    Text(vm.activeMac(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val statusText = when (conn) { Conn.CONNECTED -> "Connected"; Conn.CONNECTING -> "Connecting…"; else -> "Not connected" }
                val statusColor = if (conn == Conn.CONNECTED) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Colmi R04", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (conn != Conn.CONNECTED) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.readBattery() }) { Text("Connect") }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val b = battery
                Text(if (b != null) "${b.level}%" else "—", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = if (b != null && b.level < 20) Color(0xFFFB7185) else MaterialTheme.colorScheme.primary)
                Text(if (battery?.charging == true) "charging" else "battery", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Label("Your rings")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            for (r in rings) {
                val active = r.mac == vm.activeMac()
                Row(Modifier.fillMaxWidth().clickable { vm.setActiveRing(r.mac, r.name) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(r.mac, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active) Text("active", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    else Text("use", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }

    if (scanResults.isNotEmpty()) {
        Label("Found nearby")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                for (s in scanResults) {
                    Row(Modifier.fillMaxWidth().clickable { vm.setActiveRing(s.mac, s.name) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(s.mac, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${s.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
        Text(if (scanning) "Scanning…" else "+ Add a ring nearby")
    }
}
