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
import com.krejci.qringset.ble.RingBle
import com.krejci.qringset.data.ActivityType
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.data.RingRepository
import com.krejci.qringset.data.UserProfile
import com.krejci.qringset.data.UserProfileStore
import com.krejci.qringset.data.WorkoutEntity
import kotlinx.coroutines.flow.MutableStateFlow
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

    val scanResults = MutableStateFlow<List<ScannedRing>>(emptyList())
    val scanning = MutableStateFlow(false)

    init {
        viewModelScope.launch { repo.rememberRing(ble.mac, "R04") }
        // Append live HR into the current workout while one is running.
        viewModelScope.launch {
            ble.liveHr.collect { v -> if (workoutActive.value && v != null) workoutSamples.value = workoutSamples.value + v }
        }
    }

    fun activeMac(): String = ble.mac

    fun updateAuto(b: Boolean) { autoReconnect = b; prefs.edit().putBoolean("auto_reconnect", b).apply() }

    fun setInterval(min: Int) {
        lastInterval = min; prefs.edit().putInt("last_interval", min).apply()
        ble.setInterval(min, autoReconnect)
    }

    fun reconnect() = ble.reconnect(lastInterval.takeIf { it in 1..255 })
    fun readInterval() = ble.readInterval()
    fun readBattery() = ble.readBattery()

    fun sync() = ble.sync { result ->
        viewModelScope.launch { repo.persist(result); repo.exportCsvs() }
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
