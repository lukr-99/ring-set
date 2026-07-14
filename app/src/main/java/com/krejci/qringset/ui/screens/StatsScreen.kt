package com.krejci.qringset.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.krejci.qringset.domain.StatsEngine
import com.krejci.qringset.ui.RingViewModel
import com.krejci.qringset.ui.components.MetricChart
import com.krejci.qringset.ui.components.ScreenHeader
import com.krejci.qringset.ui.components.SegmentedTabs
import com.krejci.qringset.ui.metricColor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun StatsScreen(vm: RingViewModel) {
    var metric by remember { mutableStateOf(MetricType.HR) }
    var window by remember { mutableStateOf(0f to 1f) }
    val entities by vm.samples(metric).collectAsStateWithLifecycle(emptyList())
    val points = remember(entities) { entities.map { Point(it.epoch, it.value.toFloat()) } }
    val color = metricColor(metric)
    // 1h/12h are rolling windows ending now; "Today" is since local midnight; -1 marks it, MAX = all.
    val ranges = remember { listOf("1h" to 3600L, "12h" to 43_200L, "Today" to -1L, "Week" to 604_800L, "All" to Long.MAX_VALUE) }
    var rangeIdx by remember { mutableStateOf(2) }
    val ranged = remember(points, rangeIdx) {
        val secs = ranges[rangeIdx].second
        when (secs) {
            Long.MAX_VALUE -> points
            -1L -> { val cut = startOfTodayEpoch(); points.filter { it.epoch >= cut } }
            else -> { val cut = System.currentTimeMillis() / 1000 - secs; points.filter { it.epoch >= cut } }
        }
    }

    ScreenHeader("Insights", "${metric.label} · ${ranged.size} in ${ranges[rangeIdx].first}",
        "Charts of what the ring recorded. Tap a metric to switch and pick a time range. Tap or drag " +
            "on the graph to read a point; drag the window below it to scrub and pull its edges to " +
            "zoom. The tiles show the average, min, max and latest value for whatever slice is in view.")

    Spacer(Modifier.height(14.dp))
    MetricChips(metric) { metric = it; window = 0f to 1f }

    Spacer(Modifier.height(12.dp))
    LiveMeasureCard(vm, metric, color, points)

    Spacer(Modifier.height(12.dp))
    SegmentedTabs(ranges.map { it.first }, rangeIdx) { rangeIdx = it; window = 0f to 1f }

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            // Older data exists but none falls in the chosen window: explain instead of showing the
            // generic "no data" placeholder, which wrongly implies the ring never recorded anything.
            if (ranged.size < 2 && points.isNotEmpty()) {
                val scope = if (ranges[rangeIdx].second == -1L) "today" else "the last ${ranges[rangeIdx].first}"
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No ${metric.label.lowercase()} in $scope.\n" +
                            "Newest reading is ${ago(points.last().epoch)} — tap Measure above to refresh.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MetricChart(ranged, color, window, onWindow = { window = it }, unit = metric.unit)
            }
        }
    }

    val n = ranged.size
    val slice = if (n >= 2) {
        val i0 = floor(window.first * (n - 1)).toInt().coerceIn(0, n - 1)
        val i1 = ceil(window.second * (n - 1)).toInt().coerceIn(0, n - 1)
        ranged.subList(i0, (i1 + 1).coerceAtMost(n))
    } else ranged
    val vals = slice.map { it.value.roundToInt() }
    val summary = StatsEngine.summarize(vals)
    val median = StatsEngine.median(vals)
    val sd = StatsEngine.stdev(vals)
    val tr = StatsEngine.trend(vals)
    val has = summary.count > 0

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StatTile("Average", if (has) summary.avg.roundToInt().toString() else "—", metric.unit, color, Modifier.weight(1f))
        StatTile("Min", if (has) summary.min.toString() else "—", "in view", color, Modifier.weight(1f))
        StatTile("Max", if (has) summary.max.toString() else "—", "in view", color, Modifier.weight(1f))
        StatTile("Latest", summary.latest?.toString() ?: "—", metric.unit, color, Modifier.weight(1f))
    }
    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StatTile("Median", if (has) median?.toString() ?: "—" else "—", metric.unit, color, Modifier.weight(1f))
        StatTile("Spread", if (has) (summary.max - summary.min).toString() else "—", metric.unit, color, Modifier.weight(1f))
        StatTile("Std dev", if (summary.count > 1) "±${sd.roundToInt()}" else "—", metric.unit, color, Modifier.weight(1f))
        StatTile("Trend", if (tr == 0) "—" else if (tr > 0) "↑$tr" else "↓${-tr}", "vs earlier", color, Modifier.weight(1f))
    }
}

@Composable
private fun MetricChips(selected: MetricType, onSelect: (MetricType) -> Unit) {
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

/**
 * A per-metric "measure right now" panel. Heart rate is the only value the ring streams in real
 * time, so its Measure button opens a true live stream; the other metrics are sampled by the ring
 * on its own cycle, so their Measure button pulls the freshest stored reading via a sync.
 */
@Composable
private fun LiveMeasureCard(vm: RingViewModel, metric: MetricType, color: Color, points: List<Point>) {
    val isHr = metric == MetricType.HR
    val live by vm.liveHr.collectAsStateWithLifecycle()
    val liveStatus by vm.liveStatus.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val workoutActive by vm.workoutActive.collectAsStateWithLifecycle()
    // Live streaming only applies to HR; reset the toggle whenever the selected metric changes.
    var measuring by remember(metric) { mutableStateOf(false) }

    DisposableEffect(metric, measuring) {
        if (isHr && measuring) vm.startLiveHr()
        onDispose { if (isHr) vm.stopLiveHr() }
    }

    val latest = points.lastOrNull()
    val bigValue = if (isHr && measuring) (live?.toString() ?: "…")
        else latest?.value?.roundToInt()?.toString() ?: "—"
    val streaming = (isHr && measuring) || (!isHr && syncing)

    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (streaming) color else color.copy(alpha = 0.35f)))
                Spacer(Modifier.width(7.dp))
                Text("LIVE ${metric.short}", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.weight(1f))
                LiveActionButton(isHr, measuring, syncing, workoutActive, color, onToggleHr = { measuring = !measuring }, onSync = { vm.sync() })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(bigValue, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = color)
                if (metric.unit.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(metric.unit, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            val footer = when {
                isHr && measuring -> liveStatus.ifBlank { "Measuring…" }
                isHr && workoutActive -> "A live session is running on the Activity tab."
                !isHr && syncing -> "Reading the ring's latest ${metric.label.lowercase()}…"
                latest != null -> "Latest reading ${ago(latest.epoch)}"
                else -> "No readings yet — tap Measure."
            }
            Text(footer, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!isHr) {
                Text("Sampled by the ring on its own cycle; Measure pulls the freshest value.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LiveActionButton(
    isHr: Boolean, measuring: Boolean, syncing: Boolean, workoutActive: Boolean, color: Color,
    onToggleHr: () -> Unit, onSync: () -> Unit,
) {
    val pad = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
    if (isHr) {
        Button(
            onClick = onToggleHr,
            enabled = measuring || !workoutActive,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (measuring) MaterialTheme.colorScheme.surfaceVariant else color,
                contentColor = if (measuring) MaterialTheme.colorScheme.onSurface else Color.White,
            ),
        ) { Text(if (measuring) "Stop" else "Measure", fontWeight = FontWeight.SemiBold) }
    } else {
        Button(
            onClick = onSync,
            enabled = !syncing,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        ) {
            if (syncing) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Reading…", fontWeight = FontWeight.SemiBold)
            } else Text("Measure", fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun ago(epochSec: Long): String {
    val d = System.currentTimeMillis() / 1000 - epochSec
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86_400 -> "${d / 3600}h ago"
        else -> "${d / 86_400}d ago"
    }
}

/** Unix seconds at local midnight today. */
private fun startOfTodayEpoch(): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}
