package com.krejci.qringset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krejci.qringset.ui.screens.ActivityScreen
import com.krejci.qringset.ui.screens.ControlScreen
import com.krejci.qringset.ui.screens.DataScreen
import com.krejci.qringset.ui.screens.OverviewScreen
import com.krejci.qringset.ui.screens.ProfileScreen
import com.krejci.qringset.ui.screens.RingScreen
import com.krejci.qringset.ui.screens.SleepScreen
import com.krejci.qringset.ui.screens.StatsScreen

/** The app's top-level tabs. */
enum class Screen(val label: String, val icon: ImageVector) {
    OVERVIEW("Home", Icons.Rounded.Dashboard),
    STATS("Stats", Icons.Rounded.ShowChart),
    ACTIVITY("Activity", Icons.Rounded.FitnessCenter),
    SLEEP("Sleep", Icons.Rounded.Bedtime),
    DATA("Data", Icons.Rounded.Sync),
    RING("Ring", Icons.Rounded.Adjust),
    CONTROL("Control", Icons.Rounded.Tune),
    PROFILE("You", Icons.Rounded.Person),
}

@Composable
fun App(vm: RingViewModel, onExportShare: () -> Unit, onScan: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.OVERVIEW) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 104.dp),
        ) {
            when (screen) {
                Screen.OVERVIEW -> OverviewScreen(vm)
                Screen.STATS -> StatsScreen(vm)
                Screen.ACTIVITY -> ActivityScreen(vm)
                Screen.SLEEP -> SleepScreen(vm)
                Screen.DATA -> DataScreen(vm, onExportShare)
                Screen.RING -> RingScreen(vm, onScan)
                Screen.CONTROL -> ControlScreen(vm)
                Screen.PROFILE -> ProfileScreen(vm)
            }
        }
        FloatingNav(screen, { screen = it },
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 20.dp))
    }
}

@Composable
private fun FloatingNav(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier) {
    // Full-width bar with equal-width columns so it never changes size between tabs. The selected
    // item is tinted with a subtle highlight behind its icon; labels are always shown.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            for (s in Screen.entries) {
                val on = s == current
                val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).clickable { onSelect(s) }.padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.background(
                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                            CircleShape,
                        ).padding(horizontal = 14.dp, vertical = 5.dp),
                    ) { Icon(s.icon, s.label, tint = tint, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.height(2.dp))
                    Text(s.label, color = tint, fontSize = 9.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
