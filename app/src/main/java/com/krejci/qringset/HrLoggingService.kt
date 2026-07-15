package com.krejci.qringset

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps the app process alive (and Doze-exempt) while continuous HR logging is on, so the live
 * stream and the once-a-minute sampler in [com.krejci.qringset.ui.RingViewModel] keep running with
 * the screen off. The connectedDevice type matches what we're doing — holding a BLE link to the ring.
 * The service itself does no BLE work; it exists to hold the process foreground.
 */
class HrLoggingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Stop" from the notification: turn logging off (the app observes this pref) and exit.
            getSharedPreferences("ringset", Context.MODE_PRIVATE).edit().putBoolean("passive_hr", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // Not sticky: the logging loop lives in the app's ViewModel, so a bare restart with no UI
        // would just be a zombie notification. The app restarts the service when it's next opened.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.value = false
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HR logging", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false); description = "Shown while continuously logging heart rate" },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HrLoggingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Logging heart rate")
            .setContentText("Recording HR from your ring")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        // Starting/refreshing a foreground service can throw (e.g. ForegroundServiceStartNotAllowed
        // if we were invoked from the background on Android 12+). Fail soft rather than crash — the
        // user can retry from the Control tab's "Re-show notification" button once the app is open.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            running.value = true
        } catch (_: Exception) {
            running.value = false
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL = "hr_logging"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.krejci.qringset.STOP_HR_LOGGING"

        /** True while the keep-alive notification/service is live. Observed by the UI. */
        val running = MutableStateFlow(false)

        fun start(ctx: Context) {
            // From the foreground (a button tap or a visible screen) this is always allowed; guard
            // anyway so a background caller can't crash the app.
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, HrLoggingService::class.java)) }
            catch (_: Exception) { running.value = false }
        }

        fun stop(ctx: Context) {
            running.value = false
            ctx.stopService(Intent(ctx, HrLoggingService::class.java))
        }
    }
}
