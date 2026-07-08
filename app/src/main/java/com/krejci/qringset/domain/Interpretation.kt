package com.krejci.qringset.domain

import com.krejci.qringset.data.UserProfile

enum class Severity { GOOD, INFO, WARN }

/** One human-readable, evidence-informed takeaway shown on the Overview tab. */
data class Insight(val title: String, val detail: String, val severity: Severity)

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
    ): List<Insight> {
        val out = mutableListOf<Insight>()

        restingHr?.let { rhr ->
            out += when {
                rhr < 60 -> Insight("Resting HR $rhr bpm", "In the athletic/low range — often a sign of good cardiovascular fitness.", Severity.GOOD)
                rhr <= 80 -> Insight("Resting HR $rhr bpm", "Within the typical adult range (about 60–80 bpm).", Severity.GOOD)
                rhr <= 100 -> Insight("Resting HR $rhr bpm", "On the higher side of normal. Sleep, caffeine, stress and hydration all nudge this.", Severity.INFO)
                else -> Insight("Resting HR $rhr bpm", "Above the usual resting range (>100). If it stays there at rest, worth keeping an eye on.", Severity.WARN)
            }
        }

        avgSpo2?.let { s ->
            out += when {
                s >= 95 -> Insight("Blood oxygen $s%", "Normal — 95% or above is typical at rest at sea level.", Severity.GOOD)
                s >= 90 -> Insight("Blood oxygen $s%", "Slightly low on average. Wrist/ring SpO₂ is noisy; re-check when still.", Severity.INFO)
                else -> Insight("Blood oxygen $s%", "Reading low on average. If consistent and you feel unwell, verify with a proper oximeter.", Severity.WARN)
            }
        }

        avgHrv?.let { h ->
            val age = profile.age
            // HRV falls with age; these are loose RMSSD-style bands.
            val healthy = when { age == null -> 30; age < 35 -> 40; age < 50 -> 30; else -> 20 }
            out += if (h >= healthy)
                Insight("HRV ${h}ms", "Healthy for your profile — higher HRV generally tracks with recovery and lower stress.", Severity.GOOD)
            else
                Insight("HRV ${h}ms", "On the lower side. HRV drops with fatigue, poor sleep, alcohol and training load.", Severity.INFO)
        }

        if (sleep.totalMin > 0) {
            val h = sleep.hours
            out += when {
                h in 7f..9f -> Insight("Slept ${fmtH(h)}", "In the recommended 7–9h window for adults. Deep ${sleep.deepMin}m · REM ${sleep.remMin}m.", Severity.GOOD)
                h < 7f -> Insight("Slept ${fmtH(h)}", "Below the recommended 7–9h. Short sleep raises resting HR and lowers HRV.", Severity.WARN)
                else -> Insight("Slept ${fmtH(h)}", "Longer than usual (>9h). Occasional catch-up sleep is normal.", Severity.INFO)
            }
        }

        stepsToday?.let { st ->
            val goal = profile.goalSteps.coerceAtLeast(1)
            out += if (st >= goal)
                Insight("$st steps today", "You hit your ${goal}-step goal — nice.", Severity.GOOD)
            else
                Insight("$st steps today", "${goal - st} to go to your ${goal}-step goal.", Severity.INFO)
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
