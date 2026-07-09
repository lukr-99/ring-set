package com.krejci.qringset.domain

import com.krejci.qringset.data.UserProfile

enum class Severity { GOOD, INFO, WARN }

/** One human-readable, evidence-informed takeaway. [basis] is the time window it summarises. */
data class Insight(val title: String, val detail: String, val severity: Severity, val basis: String = "")

/**
 * Turns raw summaries + the user's profile into a few plain-language insights.
 *
 * Reference ranges are rough, population-level guidance from public health sources (AHA/ACSM
 * resting-HR bands, SpO₂ ≥ 95% typical at sea level, sleep 7–9h for adults, age-adjusted HRV).
 * This is informational, not medical advice — kept in one place so it can be refined later.
 */
object Interpretation {

    fun interpret(
        profile: UserProfile,
        restingHr: Int?,
        avgSpo2: Int?,
        avgHrv: Int?,
        sleep: SleepSummary,
        stepsToday: Int?,
        avgStress: Int? = null,
    ): List<Insight> {
        val out = mutableListOf<Insight>()

        restingHr?.let { rhr ->
            out += when {
                rhr < 60 -> Insight("Resting HR $rhr bpm", "In the athletic/low range — often a sign of good cardiovascular fitness.", Severity.GOOD, "7-day")
                rhr <= 80 -> Insight("Resting HR $rhr bpm", "Within the typical adult range (about 60–80 bpm).", Severity.GOOD, "7-day")
                rhr <= 100 -> Insight("Resting HR $rhr bpm", "On the higher side of normal. Sleep, caffeine, stress and hydration all nudge this.", Severity.INFO, "7-day")
                else -> Insight("Resting HR $rhr bpm", "Above the usual resting range (>100). If it stays there at rest, worth keeping an eye on.", Severity.WARN, "7-day")
            }
        }

        avgSpo2?.let { s ->
            out += when {
                s >= 95 -> Insight("Blood oxygen $s%", "Normal — 95% or above is typical at rest at sea level.", Severity.GOOD, "7-day avg")
                s >= 90 -> Insight("Blood oxygen $s%", "Slightly low on average. Wrist/ring SpO₂ is noisy; re-check when still.", Severity.INFO, "7-day avg")
                else -> Insight("Blood oxygen $s%", "Reading low on average. If consistent and you feel unwell, verify with a proper oximeter.", Severity.WARN, "7-day avg")
            }
        }

        avgHrv?.let { h ->
            val age = profile.age
            val healthy = when { age == null -> 30; age < 35 -> 40; age < 50 -> 30; else -> 20 }
            out += if (h >= healthy)
                Insight("HRV ${h}ms", "Healthy for your profile — higher HRV generally tracks with recovery and lower stress.", Severity.GOOD, "7-day avg")
            else
                Insight("HRV ${h}ms", "On the lower side. HRV drops with fatigue, poor sleep, alcohol and training load.", Severity.INFO, "7-day avg")
        }

        avgStress?.let { s ->
            out += when {
                s < 40 -> Insight("Stress $s", "Low — you've been fairly relaxed on average.", Severity.GOOD, "7-day avg")
                s < 66 -> Insight("Stress $s", "Moderate average stress — normal ups and downs through the day.", Severity.INFO, "7-day avg")
                else -> Insight("Stress $s", "Elevated on average. Recovery helps — breathing, a walk, better sleep.", Severity.WARN, "7-day avg")
            }
        }

        if (sleep.totalMin > 0) {
            val h = sleep.hours
            out += when {
                h in 7f..9f -> Insight("Slept ${fmtH(h)}", "In the recommended 7–9h window for adults. Deep ${sleep.deepMin}m · REM ${sleep.remMin}m.", Severity.GOOD, "last night")
                h < 7f -> Insight("Slept ${fmtH(h)}", "Below the recommended 7–9h. Short sleep raises resting HR and lowers HRV.", Severity.WARN, "last night")
                else -> Insight("Slept ${fmtH(h)}", "Longer than usual (>9h). Occasional catch-up sleep is normal.", Severity.INFO, "last night")
            }
        }

        stepsToday?.let { st ->
            val goal = profile.goalSteps.coerceAtLeast(1)
            out += if (st >= goal)
                Insight("$st steps", "You hit your ${goal}-step goal — nice.", Severity.GOOD, "today")
            else
                Insight("$st steps", "${goal - st} to go to your ${goal}-step goal.", Severity.INFO, "today")
        }

        if (out.isEmpty())
            out += Insight("Not enough data yet", "Sync the ring on the Data tab (and fill in your profile) to get personalised insights.", Severity.INFO)

        return out
    }

    private fun fmtH(h: Float): String {
        val hours = h.toInt()
        val mins = ((h - hours) * 60).toInt()
        return if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    }
}
