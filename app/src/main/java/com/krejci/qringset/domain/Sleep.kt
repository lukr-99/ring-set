package com.krejci.qringset.domain

import com.krejci.qringset.data.SleepSegment
import kotlin.math.roundToInt

/**
 * A fully analysed night of sleep, built from the ring's stage segments.
 * Stage codes: 2 = light, 3 = deep, 4 = rem, 5 = awake.
 */
data class NightSleep(
    val start: Long,
    val end: Long,
    val asleepMin: Int,           // deep + light + rem
    val deepMin: Int,
    val remMin: Int,
    val lightMin: Int,
    val awakeMin: Int,
    val segments: List<SleepSegment>,
    val awakenings: Int,
    val efficiencyPct: Int,
    val score: Int,
    val quality: String,
) {
    val hasData: Boolean get() = asleepMin > 0
    fun stageMin(stage: Int) = when (stage) { 2 -> lightMin; 3 -> deepMin; 4 -> remMin; 5 -> awakeMin; else -> 0 }

    companion object { val EMPTY = NightSleep(0, 0, 0, 0, 0, 0, 0, emptyList(), 0, 0, 0, "—") }
}

object SleepEngine {

    /** Isolate the most recent night from all stored segments and analyse it. */
    fun analyze(raw: List<SleepSegment>, goalHours: Float): NightSleep {
        if (raw.isEmpty()) return NightSleep.EMPTY
        val latest = raw.maxOf { it.epoch }
        val night = raw.filter { it.epoch >= latest - 20 * 3600 }.sortedBy { it.epoch }
        if (night.isEmpty()) return NightSleep.EMPTY

        var deep = 0; var light = 0; var rem = 0; var awake = 0
        for (s in night) when (s.stage) {
            2 -> light += s.durationMin; 3 -> deep += s.durationMin; 4 -> rem += s.durationMin; 5 -> awake += s.durationMin
        }
        val asleep = deep + light + rem
        if (asleep == 0) return NightSleep.EMPTY
        val start = night.first().epoch
        val end = night.last().epoch + night.last().durationMin * 60L
        val efficiency = (asleep.toFloat() / (asleep + awake).coerceAtLeast(1) * 100f).roundToInt()
        val awakenings = night.count { it.stage == 5 }
        val score = score(asleep, deep, rem, efficiency, goalHours)
        return NightSleep(start, end, asleep, deep, rem, light, awake, night, awakenings, efficiency, score, quality(score))
    }

    /**
     * 0–100 sleep score: how much you slept vs your goal, plus deep- and REM-sleep proportions and
     * efficiency. Rough, evidence-informed weights (deep ~13–23%, REM ~20–25% of a night).
     */
    private fun score(asleep: Int, deep: Int, rem: Int, efficiency: Int, goalHours: Float): Int {
        val goalMin = (goalHours.coerceAtLeast(1f)) * 60f
        val dur = (asleep / goalMin).coerceIn(0f, 1f) * 45f
        val deepPart = ((deep.toFloat() / asleep) / 0.18f).coerceIn(0f, 1f) * 25f
        val remPart = ((rem.toFloat() / asleep) / 0.22f).coerceIn(0f, 1f) * 20f
        val effPart = (efficiency / 100f).coerceIn(0f, 1f) * 10f
        return (dur + deepPart + remPart + effPart).roundToInt().coerceIn(0, 100)
    }

    private fun quality(s: Int) = when { s >= 85 -> "Excellent"; s >= 70 -> "Good"; s >= 50 -> "Fair"; else -> "Poor" }
}
