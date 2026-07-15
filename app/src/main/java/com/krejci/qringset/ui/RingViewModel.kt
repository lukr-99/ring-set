package com.krejci.qringset.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.krejci.qringset.BuildConfig
import com.krejci.qringset.CameraShutterService
import com.krejci.qringset.HrLoggingService
import com.krejci.qringset.Notifier
import com.krejci.qringset.ble.Conn
import com.krejci.qringset.ble.RingBle
import com.krejci.qringset.data.ActivityType
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.data.RingRepository
import com.krejci.qringset.data.SyncResult
import com.krejci.qringset.data.UserProfile
import com.krejci.qringset.data.UserProfileStore
import com.krejci.qringset.data.WorkoutEntity
import com.krejci.qringset.domain.AlertEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ScannedRing(val mac: String, val name: String, val rssi: Int)

class RingViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ringset", Context.MODE_PRIVATE)
    private val repo = RingRepository(app)

    val ble = RingBle(app, prefs.getString("active_mac", null) ?: BuildConfig.RING_MAC)

    val conn = ble.conn
    val status = ble.status
    val battery = ble.battery
    val interval = ble.interval
    val syncing = ble.syncingState
    val syncStatus = ble.syncStatus
    val liveHr = ble.liveHr
    val liveStatus = ble.liveStatus
    val cameraMode = ble.cameraMode
    /** Whether the accessibility service that taps the shutter is enabled. */
    val a11yConnected = CameraShutterService.connected

    // ---- user profile ----
    private val profileStore = UserProfileStore(prefs)
    val profile = MutableStateFlow(profileStore.load())
    fun saveProfile(p: UserProfile) { profileStore.save(p); profile.value = p }

    // ---- real-time workout session ----
    val workoutActive = MutableStateFlow(false)
    val workoutSamples = MutableStateFlow<List<Int>>(emptyList())
    val workoutStart = MutableStateFlow(0L)
    private var workoutType = ActivityType.WORKOUT

    var autoReconnect by mutableStateOf(prefs.getBoolean("auto_reconnect", false))
        private set
    var lastInterval by mutableStateOf(prefs.getInt("last_interval", 5))
        private set
    var lastSync by mutableStateOf(prefs.getLong("last_sync", 0L))
        private set

    // ---- HR alert settings ----
    var hrAlertsEnabled by mutableStateOf(prefs.getBoolean("hr_alerts", true))
        private set
    var hrSpike by mutableStateOf(prefs.getInt("hr_spike", 120))
        private set
    fun setHrAlerts(b: Boolean) { hrAlertsEnabled = b; prefs.edit().putBoolean("hr_alerts", b).apply() }
    fun updateHrSpike(v: Int) { hrSpike = v.coerceIn(90, 220); prefs.edit().putInt("hr_spike", hrSpike).apply() }

    // ---- continuous live-HR logging ----
    // This ring's on-device HR history is unreliable (stalls / overwrites / mis-slots today's
    // readings), so we log HR ourselves from the live sensor and store it under the phone's clock.
    // When on, a single continuous stream stays running while the app is open (shared with the
    // Activity workout and the Stats "Measure" toggle) and a sample is recorded every minute.
    var passiveHrEnabled by mutableStateOf(prefs.getBoolean("passive_hr", true))
        private set
    fun setPassiveHr(b: Boolean) {
        if (passiveHrEnabled == b) return
        passiveHrEnabled = b; prefs.edit().putBoolean("passive_hr", b).apply()
        reconcileStream(); updateLoggingService()
    }
    /** Whether the keep-alive foreground service (and its notification) is currently live. */
    val loggingServiceRunning = HrLoggingService.running
    /** Run the foreground service (process-keep-alive) exactly while continuous logging is on. */
    private fun updateLoggingService() {
        val app = getApplication<Application>()
        if (passiveHrEnabled) HrLoggingService.start(app) else HrLoggingService.stop(app)
    }
    /**
     * Bring the keep-alive notification back if it was dismissed or its service was killed. Turns
     * continuous logging on if it was off, otherwise just re-posts the notification. Safe to call
     * from the UI (foreground), where starting a foreground service is always allowed.
     */
    fun restartLoggingService() {
        if (!passiveHrEnabled) setPassiveHr(true) else HrLoggingService.start(getApplication())
    }
    // Keeps the toggle in sync when the notification's "Stop" turns logging off from outside the UI.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "passive_hr") {
            val v = prefs.getBoolean("passive_hr", true)
            if (v != passiveHrEnabled) { passiveHrEnabled = v; reconcileStream(); updateLoggingService() }
        }
    }
    private var manualHr = false          // Stats "Measure" toggle wants the stream
    @Volatile private var streamOn = false // whether the shared live stream is currently running
    private var samplerJob: Job? = null

    /** Whoever needs the sensor: a workout, a manual measure, or continuous logging — but never mid-sync. */
    private fun wantStream() = !syncing.value && (workoutActive.value || manualHr || passiveHrEnabled)

    /** Start or stop the one shared live-HR stream to match [wantStream]. Idempotent. */
    private fun reconcileStream() {
        val want = wantStream()
        if (want && !streamOn) { streamOn = true; ble.startLiveHr() }
        else if (!want && streamOn) { streamOn = false; ble.stopLiveHr() }
    }

    /** While the stream runs and logging is on, store one HR reading a minute under the phone clock. */
    private fun startHrSampler() {
        samplerJob?.cancel()
        samplerJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                // Recover the shared stream if the link dropped, then log the current reading.
                if (conn.value == Conn.DISCONNECTED) streamOn = false
                reconcileStream()
                if (passiveHrEnabled && streamOn) ble.liveHr.value?.let { bpm ->
                    if (bpm in 30..220) {
                        repo.insertSample(MetricType.HR, System.currentTimeMillis() / 1000, bpm)
                        repo.exportCsvs()
                    }
                }
            }
        }
    }

    // ---- auto-sync (while the app is open) ----
    var autoSyncEnabled by mutableStateOf(prefs.getBoolean("auto_sync", false))
        private set
    private var autoSyncJob: Job? = null
    fun setAutoSync(b: Boolean) {
        autoSyncEnabled = b; prefs.edit().putBoolean("auto_sync", b).apply()
        if (b) startAutoSync() else { autoSyncJob?.cancel(); autoSyncJob = null }
    }
    /**
     * Pull the ring's stored metrics periodically. HR is handled continuously by the live stream when
     * that's on, so we sync the slower metrics every ~10 min then (each sync briefly pauses the
     * stream); otherwise we track the HR-logging interval.
     */
    private fun startAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            while (isActive) {
                val mins = if (passiveHrEnabled) 10 else lastInterval.coerceIn(1, 255)
                delay(mins * 60_000L + 30_000L)
                if (autoSyncEnabled && !workoutActive.value && !syncing.value && !manualHr) sync()
            }
        }
    }

    val scanResults = MutableStateFlow<List<ScannedRing>>(emptyList())
    val scanning = MutableStateFlow(false)

    // ---- camera shutter (ring gesture → tap the system camera's shutter) ----
    // In camera mode the ring's motion sensor fires "take photo" on a shake OR a firm tap. This is a
    // momentary "about to shoot" mode (keeps the ring busy), so it isn't persisted across launches.
    var cameraGestureOn by mutableStateOf(false); private set

    fun setCameraGesture(on: Boolean) {
        cameraGestureOn = on
        if (on) ble.enterCameraMode() else ble.exitCameraMode()
    }

    /** Fire a shutter tap after a short delay — used by the "Test" button once the camera is open. */
    fun testCameraShutter() {
        viewModelScope.launch { delay(2500); CameraShutterService.instance?.triggerShutter() }
    }

    init {
        // Seed the HR-log interval the ring is re-armed with on connect from the saved setting.
        ble.logIntervalMin = lastInterval
        // When the ring reports a shake in camera mode, tap the foreground camera's shutter.
        ble.onCameraShutter = { CameraShutterService.instance?.triggerShutter() }
        viewModelScope.launch { repo.rememberRing(ble.mac, "R04") }
        // Append live HR into the current workout while one is running.
        viewModelScope.launch {
            ble.liveHr.collect { v -> if (workoutActive.value && v != null) workoutSamples.value = workoutSamples.value + v }
        }
        // Keep our interval (auto-sync + passive-HR cadence) in step with what the ring actually reports.
        viewModelScope.launch {
            ble.interval.collect { info ->
                info?.takeIf { it.minutes in 1..255 && it.minutes != lastInterval }?.let {
                    lastInterval = it.minutes; ble.logIntervalMin = it.minutes
                    prefs.edit().putInt("last_interval", it.minutes).apply()
                }
            }
        }
        // A sync needs the connection to itself, so pause the shared stream while one runs and
        // resume it right after.
        viewModelScope.launch { syncing.collect { reconcileStream() } }
        if (autoSyncEnabled) startAutoSync()
        startHrSampler()
        reconcileStream()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (passiveHrEnabled) updateLoggingService()
    }

    fun activeMac(): String = ble.mac

    fun updateAuto(b: Boolean) { autoReconnect = b; prefs.edit().putBoolean("auto_reconnect", b).apply() }

    fun setInterval(min: Int) {
        lastInterval = min; prefs.edit().putInt("last_interval", min).apply()
        ble.setInterval(min, autoReconnect)
    }

    /** Reconnect, then immediately pull the latest data (a reconnect almost always means "catch me up"). */
    fun reconnect() = ble.reconnect(lastInterval.takeIf { it in 1..255 }) { sync() }

    /** Escape hatch for a ring stuck at 0 min / OFF: reconnect, re-arm the interval, read it back. */
    fun resetMeasurement() = ble.resetInterval(lastInterval.takeIf { it in 1..255 } ?: 5)
    fun readInterval() = ble.readInterval()
    fun readBattery() = ble.readBattery()

    fun sync() = ble.sync { result ->
        viewModelScope.launch {
            repo.persist(result)
            repo.exportCsvs()
            lastSync = System.currentTimeMillis(); prefs.edit().putLong("last_sync", lastSync).apply()
            if (hrAlertsEnabled) runHrAlerts(result)
        }
    }

    private suspend fun runHrAlerts(result: SyncResult) {
        val windows = repo.workoutsNow().map { it.startEpoch to it.endEpoch }
        val hr = result.samples.filter { it.metric == MetricType.HR }.map { it.epoch to it.value }
        val since = System.currentTimeMillis() / 1000 - 3 * 3600
        AlertEngine.detect(hr, windows, since, hrSpike)?.let { Notifier.show(getApplication(), it.title, it.text) }
    }

    /** Log an activity window with no live monitoring — shows in history and mutes HR alerts for it. */
    fun markActivity(type: ActivityType, minutes: Int) {
        val start = System.currentTimeMillis() / 1000
        viewModelScope.launch {
            repo.saveWorkout(WorkoutEntity(type = "${type.label} (manual)", startEpoch = start,
                endEpoch = start + minutes * 60L, avgHr = 0, maxHr = 0, minHr = 0, samples = 0))
        }
    }

    suspend fun exportCsvs() = repo.exportCsvs()

    fun samples(m: MetricType) = repo.samples(m)
    fun count(m: MetricType) = repo.count(m)
    fun sleep() = repo.sleep()
    fun sleepCount() = repo.sleepCount()
    fun rings() = repo.rings()
    fun workouts() = repo.workouts()

    fun setActiveRing(mac: String, name: String) {
        prefs.edit().putString("active_mac", mac).apply()
        ble.disconnect(); ble.mac = mac
        viewModelScope.launch { repo.rememberRing(mac, name) }
    }

    fun renameRing(mac: String, name: String) {
        viewModelScope.launch { repo.rememberRing(mac, name) }
    }

    // ---- live HR (also used by resting-HR measurement, no workout) ----
    fun startLiveHr() { manualHr = true; reconcileStream() }
    fun stopLiveHr() { manualHr = false; reconcileStream() }

    // ---- real-time workout ----
    fun startWorkout(type: ActivityType) {
        workoutType = type
        workoutSamples.value = emptyList()
        workoutStart.value = System.currentTimeMillis() / 1000
        workoutActive.value = true
        reconcileStream()
    }

    fun stopWorkout() {
        if (!workoutActive.value) { reconcileStream(); return }
        workoutActive.value = false
        reconcileStream()
        val s = workoutSamples.value
        if (s.isNotEmpty()) {
            val w = WorkoutEntity(
                type = workoutType.label,
                startEpoch = workoutStart.value,
                endEpoch = System.currentTimeMillis() / 1000,
                avgHr = s.average().toInt(), maxHr = s.max(), minHr = s.min(), samples = s.size,
                samplesCsv = s.joinToString(","),
            )
            viewModelScope.launch { repo.saveWorkout(w) }
        }
    }

    override fun onCleared() {
        // Reached only when the Activity is finishing (not on screen-off), so tear logging down.
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        ble.stopLiveHr()
        HrLoggingService.stop(getApplication())
    }

    // ---- scanning for other rings ----
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val mac = result.device.address
            val cur = scanResults.value
            if (cur.none { it.mac == mac }) scanResults.value = cur + ScannedRing(mac, name, result.rssi)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanResults.value = emptyList(); scanning.value = true
        try { scanner.startScan(scanCb) } catch (_: Exception) {}
        viewModelScope.launch {
            kotlinx.coroutines.delay(9000)
            try { scanner.stopScan(scanCb) } catch (_: Exception) {}
            scanning.value = false
        }
    }
}
