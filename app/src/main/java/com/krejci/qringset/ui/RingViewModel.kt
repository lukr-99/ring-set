package com.krejci.qringset.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.krejci.qringset.BuildConfig
import com.krejci.qringset.CameraShutterService
import com.krejci.qringset.Notifier
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

    // ---- auto-sync (while the app is open) ----
    var autoSyncEnabled by mutableStateOf(prefs.getBoolean("auto_sync", false))
        private set
    private var autoSyncJob: Job? = null
    fun setAutoSync(b: Boolean) {
        autoSyncEnabled = b; prefs.edit().putBoolean("auto_sync", b).apply()
        if (b) startAutoSync() else { autoSyncJob?.cancel(); autoSyncJob = null }
    }
    /** Roughly one HR-logging interval (+30s for the ring to finish measuring) between syncs. */
    private fun startAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(lastInterval.coerceIn(1, 255) * 60_000L + 30_000L)
                if (autoSyncEnabled && !workoutActive.value && !syncing.value) sync()
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
        // When the ring reports a shake in camera mode, tap the foreground camera's shutter.
        ble.onCameraShutter = { CameraShutterService.instance?.triggerShutter() }
        viewModelScope.launch { repo.rememberRing(ble.mac, "R04") }
        // Append live HR into the current workout while one is running.
        viewModelScope.launch {
            ble.liveHr.collect { v -> if (workoutActive.value && v != null) workoutSamples.value = workoutSamples.value + v }
        }
        if (autoSyncEnabled) startAutoSync()
    }

    fun activeMac(): String = ble.mac

    fun updateAuto(b: Boolean) { autoReconnect = b; prefs.edit().putBoolean("auto_reconnect", b).apply() }

    fun setInterval(min: Int) {
        lastInterval = min; prefs.edit().putInt("last_interval", min).apply()
        ble.setInterval(min, autoReconnect)
    }

    /** Reconnect, then immediately pull the latest data (a reconnect almost always means "catch me up"). */
    fun reconnect() = ble.reconnect(lastInterval.takeIf { it in 1..255 }) { sync() }
    fun readInterval() = ble.readInterval()
    fun readBattery() = ble.readBattery()

    fun sync() = ble.sync { result ->
        viewModelScope.launch {
            repo.persist(result); repo.exportCsvs()
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
    fun startLiveHr() = ble.startLiveHr()
    fun stopLiveHr() = ble.stopLiveHr()

    // ---- real-time workout ----
    fun startWorkout(type: ActivityType) {
        workoutType = type
        workoutSamples.value = emptyList()
        workoutStart.value = System.currentTimeMillis() / 1000
        workoutActive.value = true
        ble.startLiveHr()
    }

    fun stopWorkout() {
        ble.stopLiveHr()
        if (!workoutActive.value) return
        workoutActive.value = false
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

    override fun onCleared() { ble.stopLiveHr() }

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
