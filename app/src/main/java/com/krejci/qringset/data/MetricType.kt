package com.krejci.qringset.data

/** The time-series metrics we read from the ring and chart. */
enum class MetricType(val key: String, val unit: String, val label: String) {
    HR("hr", "bpm", "Heart rate"),
    SPO2("spo2", "%", "Blood oxygen"),
    HRV("hrv", "ms", "HRV"),
    STRESS("stress", "", "Stress"),
    STEPS("steps", "/hr", "Steps");

    companion object {
        fun from(key: String): MetricType = entries.first { it.key == key }
    }
}
