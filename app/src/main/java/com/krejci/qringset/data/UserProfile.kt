package com.krejci.qringset.data

import android.content.SharedPreferences
import java.util.Calendar

enum class Sex(val label: String) { UNSPECIFIED("—"), MALE("Male"), FEMALE("Female"), OTHER("Other") }

/**
 * User-entered profile. Drives personalised interpretation of the metrics (age-adjusted HRV,
 * resting-HR bands, goals). Persisted in SharedPreferences — one profile per install.
 */
data class UserProfile(
    val name: String = "",
    val birthYear: Int = 0,
    val sex: Sex = Sex.UNSPECIFIED,
    val heightCm: Int = 0,
    val weightKg: Int = 0,
    val restingHr: Int = 0,
    val goalSteps: Int = 8000,
    val goalSleepHours: Float = 8f,
    val bedtimeMin: Int = 1380,   // minutes from midnight — default 23:00
    val wakeMin: Int = 420,       // default 07:00
) {
    val age: Int?
        get() = if (birthYear in 1900..2100)
            Calendar.getInstance().get(Calendar.YEAR) - birthYear else null

    val hasData: Boolean
        get() = name.isNotBlank() || birthYear > 0 || heightCm > 0 || weightKg > 0 || restingHr > 0

    /** Goal sleep window length in minutes (bedtime → wake, wrapping midnight). */
    val goalWindowMin: Int get() = ((wakeMin - bedtimeMin + 1440) % 1440)
}

/** Loads/saves a [UserProfile] from SharedPreferences. */
class UserProfileStore(private val p: SharedPreferences) {
    fun load() = UserProfile(
        name = p.getString("u_name", "") ?: "",
        birthYear = p.getInt("u_birth", 0),
        sex = Sex.entries.getOrElse(p.getInt("u_sex", 0)) { Sex.UNSPECIFIED },
        heightCm = p.getInt("u_h", 0),
        weightKg = p.getInt("u_w", 0),
        restingHr = p.getInt("u_rhr", 0),
        goalSteps = p.getInt("u_gsteps", 8000),
        goalSleepHours = p.getFloat("u_gsleep", 8f),
        bedtimeMin = p.getInt("u_bed", 1380),
        wakeMin = p.getInt("u_wake", 420),
    )

    fun save(u: UserProfile) {
        p.edit()
            .putString("u_name", u.name)
            .putInt("u_birth", u.birthYear)
            .putInt("u_sex", u.sex.ordinal)
            .putInt("u_h", u.heightCm)
            .putInt("u_w", u.weightKg)
            .putInt("u_rhr", u.restingHr)
            .putInt("u_gsteps", u.goalSteps)
            .putFloat("u_gsleep", u.goalSleepHours)
            .putInt("u_bed", u.bedtimeMin)
            .putInt("u_wake", u.wakeMin)
            .apply()
    }
}
