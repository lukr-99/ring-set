package com.krejci.qringset.ui.components

import androidx.compose.ui.graphics.PathEffect

/** Dashed stroke used to bridge missing-data gaps in the charts (data the ring didn't record). */
val GapDash: PathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 9f), 0f)

/**
 * Smallest time gap (seconds) between consecutive samples that counts as "missing data" rather than
 * normal sampling: 4× the data's own median spacing, but at least 15 minutes. A [Long.MAX_VALUE]
 * result means "never treat anything as a gap" (too few points to tell).
 */
fun gapThresholdSeconds(epochs: List<Long>): Long {
    if (epochs.size < 3) return Long.MAX_VALUE
    val dts = ArrayList<Long>(epochs.size)
    for (i in 1 until epochs.size) { val d = epochs[i] - epochs[i - 1]; if (d > 0) dts.add(d) }
    if (dts.isEmpty()) return Long.MAX_VALUE
    dts.sort()
    return maxOf(dts[dts.size / 2] * 4, 15 * 60L)
}
