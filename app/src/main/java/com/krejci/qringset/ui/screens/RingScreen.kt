package com.krejci.qringset.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krejci.qringset.ble.Conn
import com.krejci.qringset.data.KnownRingEntity
import com.krejci.qringset.ui.RingViewModel
import com.krejci.qringset.ui.components.ScreenHeader
import com.krejci.qringset.ui.components.SectionLabel

@Composable
fun RingScreen(vm: RingViewModel, onScan: () -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val rings by vm.rings().collectAsStateWithLifecycle(emptyList())
    val scanResults by vm.scanResults.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<KnownRingEntity?>(null) }

    ScreenHeader("My ring", vm.activeMac(),
        "Your ring and its battery. Tap a ring in the list to make it active, use the pencil to " +
            "give it a name, or scan to add another ring nearby. Only one ring can be connected " +
            "at a time.")

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

    SectionLabel("Your rings")
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
                    IconButton(onClick = { renaming = r }) {
                        Icon(Icons.Rounded.Edit, "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (scanResults.isNotEmpty()) {
        SectionLabel("Found nearby")
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

    renaming?.let { r ->
        RenameDialog(r.name, onDismiss = { renaming = null }) { newName ->
            vm.renameRing(r.mac, newName); renaming = null
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename ring") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                singleLine = true,
                label = { Text("Name") },
            )
        },
    )
}
