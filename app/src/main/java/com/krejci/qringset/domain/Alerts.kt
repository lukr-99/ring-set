package com.krejci.qringset.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HrAlert(val title: String, val text: String)

/**
 * Very basic heart-rate anomaly detection, run over freshly synced HR readings:
 *  - a single very-high reading (spike) outside any logged activity window, or
 *  - several consecutive elevated readings (prolonged) outside activity.
 * Activity windows (real or manually marked) suppress alerts so exercise doesn't trigger them.
 */
object AlertEngine {
    fun detect(
        points: List<Pair<Long, Int>>,      // (epochSeconds, bpm)
        windows: List<Pair<Long, Long>>,    // covered activity windows (start, end)
        sinceEpoch: Long,
        spike: Int,
        prolonged: Int = 100,
        prolongedCount: Int = 4,
    ): HrAlert? {
        val recent = points.filter { it.first >= sinceEpoch }.sortedBy { it.first }
        fun covered(t: Long) = windows.any { t >= it.first && t <= it.second }

        // Prolonged elevated run of uncovered readings.
        var run = 0
        for ((t, v) in recent) {
            if (v >= prolonged && !covered(t)) {
                run++
                if (run >= prolongedCount)
                    return HrAlert("Elevated heart rate", "Your heart rate stayed above $prolonged bpm for a while with no activity logged. If you're resting, take it easy.")
            } else run = 0
        }

        // Single high spike of an uncovered reading (most recent first).
        val sp = recent.sortedByDescending { it.first }.firstOrNull { it.second >= spike && !covered(it.first) }
        if (sp != null)
            return HrAlert("Heart-rate spike", "HR reached ${sp.second} bpm at ${hm(sp.first)} with no activity logged.")
        return null
    }

    private fun hm(e: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(e * 1000))
}
