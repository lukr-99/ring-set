package com.krejci.qringset.data

/** One (time, value) point for a metric. */
data class MetricSample(val metric: MetricType, val epoch: Long, val value: Int)

/** One sleep-stage segment. stage: 2=light 3=deep 4=rem 5=awake. */
data class SleepSegment(val epoch: Long, val stage: Int, val durationMin: Int)

/** Everything one full ring sync produced, before it is persisted. */
data class SyncResult(
    val samples: List<MetricSample>,
    val sleep: List<SleepSegment>,
    val newCounts: Map<MetricType, Int>,
    val newSleep: Int,
)

/** A chartable point (x = epoch seconds, y = value). */
data class Point(val epoch: Long, val value: Float)
