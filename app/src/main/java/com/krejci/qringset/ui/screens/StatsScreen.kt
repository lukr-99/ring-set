package com.krejci.qringset.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
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
    val ranges = remember { listOf("1h" to 3600L, "12h" to 43_200L, "24h" to 86_400L, "Week" to 604_800L, "All" to Long.MAX_VALUE) }
    var rangeIdx by remember { mutableStateOf(2) }
    val ranged = remember(points, rangeIdx) {
        val secs = ranges[rangeIdx].second
        if (secs == Long.MAX_VALUE) points
        else { val cut = System.currentTimeMillis() / 1000 - secs; points.filter { it.epoch >= cut } }
    }

    ScreenHeader("Insights", "${metric.label} · ${ranged.size} in ${ranges[rangeIdx].first}",
        "Charts of what the ring recorded. Tap a metric to switch, pick a time range, then drag the " +
            "window under the graph to scrub and pull its edges to zoom. The tiles show the average, " +
            "min, max and latest value for whatever slice is in view.")

    Spacer(Modifier.height(14.dp))
    MetricChips(metric) { metric = it; window = 0f to 1f }

    Spacer(Modifier.height(10.dp))
    SegmentedTabs(ranges.map { it.first }, rangeIdx) { rangeIdx = it; window = 0f to 1f }

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            MetricChart(ranged, color, window, onWindow = { window = it }, unit = metric.unit)
            Spacer(Modifier.height(4.dp))
            Text("Tap the chart to read a point · drag the window below to scrub · pull the edges to zoom",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
