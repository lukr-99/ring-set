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
/** One day's roll-up of a metric. */
data class DayStat(val dayEpoch: Long, val avg: Double, val min: Int, val max: Int, val count: Int)

/** Minutes spent in each sleep stage over the loaded sleep data. */
data class SleepSummary(val totalMin: Int, val deepMin: Int, val remMin: Int, val lightMin: Int, val awakeMin: Int) {
    val hours: Float get() = totalMin / 60f
    companion object { val EMPTY = SleepSummary(0, 0, 0, 0, 0) }
}

/** Overall daily wellness score (0–100) with a friendly band label. */
data class ActivityScore(val score: Int, val label: String)

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

    /**
     * Resting heart rate estimate: the average of the lowest ~10% of readings (a simple,
     * robust proxy for true resting HR without needing continuous overnight data).
     */
    fun restingHr(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = (sorted.size / 10).coerceAtLeast(1)
        return sorted.take(n).average().toInt()
    }

    /** Fraction of readings that fall within [lo, hi] inclusive (0f..1f). */
    fun timeInRange(values: List<Int>, lo: Int, hi: Int): Float {
        if (values.isEmpty()) return 0f
        return values.count { it in lo..hi }.toFloat() / values.size
    }

    /** Group (epochSeconds, value) points into per-calendar-day roll-ups, most recent last. */
    fun daily(points: List<Pair<Long, Int>>): List<DayStat> {
        if (points.isEmpty()) return emptyList()
        val byDay = points.groupBy { it.first / 86_400L }
        return byDay.toSortedMap().map { (day, pts) ->
            val vs = pts.map { it.second }
            DayStat(day * 86_400L, vs.average(), vs.min(), vs.max(), vs.size)
        }
    }

    /**
     * Sleep-stage totals. Stage codes: 2=light 3=deep 4=rem 5=awake. Pass (stage, minutes).
     * By default only counts the most recent night (segments within 24h of the latest one).
     */
    fun sleepSummary(segments: List<Triple<Long, Int, Int>>, lastNightOnly: Boolean = true): SleepSummary {
        if (segments.isEmpty()) return SleepSummary.EMPTY
        val use = if (lastNightOnly) {
            val latest = segments.maxOf { it.first }
            segments.filter { it.first >= latest - 20 * 3600 }
        } else segments
        var deep = 0; var rem = 0; var light = 0; var awake = 0
        for ((_, stage, mins) in use) when (stage) {
            2 -> light += mins; 3 -> deep += mins; 4 -> rem += mins; 5 -> awake += mins
        }
        return SleepSummary(deep + rem + light, deep, rem, light, awake)
    }

    /**
     * A single 0–100 "how's today going" score. Weighted blend of movement (steps vs goal),
     * sleep (closeness to goal), resting HR (lower is better) and HRV (age-adjusted). Parts are
     * skipped gracefully when data is missing, so it stays meaningful early on.
     */
    fun activityScore(
        stepsToday: Int?, goalSteps: Int,
        sleepHours: Float, goalSleepHours: Float,
        restingHr: Int?, avgHrv: Int?, age: Int?,
    ): ActivityScore {
        var weight = 0f; var got = 0f

        // Steps — 40%
        if (stepsToday != null && goalSteps > 0) {
            weight += 40f; got += 40f * (stepsToday.toFloat() / goalSteps).coerceIn(0f, 1f)
        }
        // Sleep — 30%, peaks at the goal
        if (sleepHours > 0f && goalSleepHours > 0f) {
            val closeness = (1f - (kotlin.math.abs(sleepHours - goalSleepHours) / goalSleepHours)).coerceIn(0f, 1f)
            weight += 30f; got += 30f * closeness
        }
        // Resting HR — 20%, lower is better
        restingHr?.let { rhr ->
            val part = when { rhr <= 55 -> 1f; rhr <= 65 -> 0.85f; rhr <= 75 -> 0.65f; rhr <= 85 -> 0.4f; else -> 0.15f }
            weight += 20f; got += 20f * part
        }
        // HRV — 10%, age-adjusted
        avgHrv?.let { h ->
            val healthy = when { age == null -> 30; age < 35 -> 40; age < 50 -> 30; else -> 20 }
            weight += 10f; got += 10f * (h.toFloat() / healthy).coerceIn(0f, 1f)
        }

        val score = if (weight == 0f) 0 else (got / weight * 100f).toInt().coerceIn(0, 100)
        val label = when { weight == 0f -> "No data"; score >= 80 -> "Excellent"; score >= 60 -> "Good"; score >= 40 -> "Fair"; else -> "Low" }
        return ActivityScore(score, label)
    }
}
