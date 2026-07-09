package com.krejci.qringset

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Accessibility service that taps the shutter of whatever camera app is in the foreground. It runs
 * in Ring Set's own process, so while it is enabled the process (and the ring's BLE connection)
 * stays alive in the background — that is what lets a ring gesture reach here while you're in the
 * system camera. [RingBle] calls [triggerShutter] when the ring reports a "take photo" gesture.
 *
 * We don't rely on a localized shutter label or a vendor resource id: we find the clickable node
 * nearest the bottom-centre (where the shutter sits in portrait) and click it, falling back to a
 * synthesized tap at that point.
 */
class CameraShutterService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        connected.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        connected.value = false
        super.onDestroy()
    }

    // We only act on demand (from the ring), so we don't need to react to events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Snap a photo in the foreground camera app. Safe to call from any thread. */
    fun triggerShutter() = main.post {
        val root = rootInActiveWindow
        val node = root?.let { nearestBottomCentreClickable(it) }
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "shutter: clicked node ${node.viewIdResourceName}")
        } else {
            val (x, y) = shutterPoint()
            tapAt(x, y)
            Log.d(TAG, "shutter: fallback tap at $x,$y")
        }
    }

    /** All clickable descendants, picking the one whose centre is closest to the shutter point. */
    private fun nearestBottomCentreClickable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val (tx, ty) = shutterPoint()
        var best: AccessibilityNodeInfo? = null
        var bestD = Float.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isClickable && n.isVisibleToUser) {
                val b = Rect(); n.getBoundsInScreen(b)
                val cx = b.exactCenterX(); val cy = b.exactCenterY()
                // only consider the lower part of the screen so we don't grab a top toolbar button
                if (cy > ty - b.height()) {
                    val d = (cx - tx) * (cx - tx) + (cy - ty) * (cy - ty)
                    if (d < bestD) { bestD = d; best = n }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    /** Bottom-centre of the screen, where a portrait camera's shutter lives. */
    private fun shutterPoint(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return dm.widthPixels / 2f to dm.heightPixels * 0.86f
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "RingCamera"

        /** The connected instance (non-null only while the service is enabled). */
        @Volatile
        var instance: CameraShutterService? = null
            private set

        /** Observable "is the accessibility service enabled" flag for the UI. */
        val connected = MutableStateFlow(false)
    }
}
