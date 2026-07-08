package com.krejci.qringset.data

/** The kind of activity a real-time session records. */
enum class ActivityType(val label: String) {
    WORKOUT("Workout"), LIFT("Lifting"), RUN("Run"), WALK("Walk"), CYCLE("Cycling"), OTHER("Other");

    companion object {
        fun from(label: String): ActivityType = entries.firstOrNull { it.label == label } ?: OTHER
    }
}

/** A finished real-time session, summarised from the live HR samples we collected. */
data class WorkoutSummary(
    val id: Long,
    val type: ActivityType,
    val startEpoch: Long,
    val endEpoch: Long,
    val avgHr: Int,
    val maxHr: Int,
    val minHr: Int,
    val samples: Int,
) {
    val durationMin: Int get() = ((endEpoch - startEpoch) / 60).toInt()
}
