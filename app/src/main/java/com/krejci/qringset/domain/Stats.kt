package com.krejci.qringset.domain

/** Summary numbers a stat tile or insight can show for a metric over some window. */
data class MetricSummary(
    val count: Int,
    val avg: Double,
    val min: Int,
    val max: Int,
    val latest: Int?,
) {
    companion object {
        val EMPTY = MetricSummary(0, 0.0, 0, 0, null)
    }
}

enum class TimeRange(val label: String, val seconds: Long) {
    DAY("Day", 86_400),
    WEEK("Week", 604_800),
    MONTH("Month", 2_592_000),
}

/**
 * Statistics over a metric's values. New statistics (resting HR, time-in-range, trend, …)
 * become new functions here — the UI reads whatever this exposes without changing.
 */
object StatsEngine {
    fun summarize(values: List<Int>): MetricSummary {
        if (values.isEmpty()) return MetricSummary.EMPTY
        return MetricSummary(
            count = values.size,
            avg = values.average(),
            min = values.min(),
            max = values.max(),
            latest = values.last(),
        )
    }
}
