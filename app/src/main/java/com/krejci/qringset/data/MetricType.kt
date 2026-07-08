package com.krejci.qringset.data

/** The time-series metrics we read from the ring and chart. */
enum class MetricType(val key: String, val unit: String, val label: String, val short: String) {
    HR("hr", "bpm", "Heart rate", "HR"),
    SPO2("spo2", "%", "Blood oxygen", "SpO₂"),
    HRV("hrv", "ms", "HRV", "HRV"),
    STRESS("stress", "", "Stress", "Stress"),
    STEPS("steps", "/hr", "Steps", "Steps");

    companion object {
        fun from(key: String): MetricType = entries.first { it.key == key }
    }
}
