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

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HR logging", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false); description = "Shown while continuously logging heart rate" },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL = "hr_logging"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.krejci.qringset.STOP_HR_LOGGING"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, HrLoggingService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, HrLoggingService::class.java))
    }
}
