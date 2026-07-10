package com.krejci.qringset.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.krejci.qringset.data.MetricSample
import com.krejci.qringset.data.MetricType
import com.krejci.qringset.data.SleepSegment
import com.krejci.qringset.data.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TreeMap
import java.util.UUID

enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }
data class IntervalInfo(val enabled: Boolean, val minutes: Int)
data class BatteryInfo(val level: Int, val charging: Boolean)

/**
 * Talks to a Colmi R0x / QRing ring over BLE. All protocol logic lives here; the UI only
 * observes the StateFlows and calls the action methods. Caller must hold BLUETOOTH_CONNECT.
 */
class RingBle(private val context: Context, @Volatile var mac: String) {

    private val handler = Handler(Looper.getMainLooper())

    val conn = MutableStateFlow(Conn.DISCONNECTED)
    val status = MutableStateFlow("Ready")
    val battery = MutableStateFlow<BatteryInfo?>(null)
    val interval = MutableStateFlow<IntervalInfo?>(null)
    val syncingState = MutableStateFlow(false)
    val syncStatus = MutableStateFlow("")
    /** Live heart rate (bpm) while a real-time session runs, else null. */
    val liveHr = MutableStateFlow<Int?>(null)
    /** Human-readable state of the live session ("Connecting…", "Measuring…", errors). */
    val liveStatus = MutableStateFlow("")
    /** True while the ring is in camera-shutter mode (a shake fires [onCameraShutter]). */
    val cameraMode = MutableStateFlow(false)
    /** Invoked (on the main thread) each time the ring signals a "take photo" gesture. */
    var onCameraShutter: (() -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var v2WriteChar: BluetoothGattCharacteristic? = null
    private var connecting = false
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var connectTimeout: Runnable? = null
    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var liveKeepAlive: Runnable? = null
    private var liveOn = false
    private var liveRequested = false
    private var liveTick = 0
    private var cameraOn = false
    private var cameraKeepAlive: Runnable? = null

    // sync state
    private enum class Stage { HR, STEPS, SPO2, SLEEP, STRESS, HRV }
    private var stage = Stage.HR
    private var syncing = false
    private var syncTimeout: Runnable? = null
    private var onSyncDone: ((SyncResult) -> Unit)? = null
    private val bigData = ByteArrayOutputStream()
    private val col = mutableMapOf<MetricType, TreeMap<Long, Int>>()
    private val sleepCol = TreeMap<Long, IntArray>()
    private val syncOffsets = ArrayDeque<Int>()
    private val stepsOffsets = ArrayDeque<Int>()
    private var curHrvDay = 0
    private var curInterval = 5
    private var curSize = 0
    private var curCount = 0
    private var curDayStart = 0L
    private var curDayIdx = 0

    companion object {
        private val SVC = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val SVC_V2 = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        private val CMD_V2_UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        private val NOTIFY_V2_UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CMD_BATTERY = 0x03
        private const val CMD_HR_LOG = 0x16
        private const val CMD_HR_READ = 0x15
        private const val CMD_STEPS = 0x43
        private const val CMD_STRESS = 0x37
        private const val CMD_HRV = 0x39
        private const val CMD_BIG_DATA = 0xBC
        private const val BD_SPO2 = 0x2A
        private const val BD_SLEEP = 0x27
        // Real-time HR — matches the QRing/Oudmon SDK exactly:
        //   start:  StartHeartRateReq.getSimpleReq(1) -> [0x69, type=1, sub=0]
        //   poll:   RealTimeHeartRate(3)              -> [0x1e, 3]  (cmd 30, ~1s keepalive)
        //   stop:   StopHeartRateReq.stopHeartRate(0) -> [0x6a, 1, 0, 0]
        // Value: 0x69 response is StartHeartRateRsp (type=[1] err=[2] hr=[3]); a 0x1e response
        // is RealTimeHeartRateRsp (hr=[1]).
        private const val CMD_HR_START = 0x69
        private const val CMD_HR_POLL = 0x1E
        private const val CMD_HR_POLL_RSP = 0x9E // 30 | 0x80 — the ring's reply to the poll
        private const val CMD_HR_STOP = 0x6A
        private const val HR_TYPE = 0x01
        // Camera shutter (Oudmon CameraReq/CameraNotifyRsp, cmd 0x02). We send:
        //   enter camera UI = [0x02, 4] · keep-screen-on keep-alive = [0x02, 5] · finish = [0x02, 6]
        // The ring notifies back on cmd 0x02 with action at byte[1]:
        //   1 = entered UI · 2 = TAKE PHOTO (the shake gesture) · 3 = finished.
        private const val CMD_CAMERA = 0x02
        private const val CAM_ENTER = 0x04
        private const val CAM_KEEP = 0x05
        private const val CAM_FINISH = 0x06
        private const val CAM_TAKE_PHOTO = 0x02
        private const val CAM_KEEPALIVE_MS = 2_000L
        private val HR_VALID = 30..220
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val RECONNECT_DELAY_MS = 1_600L
        private const val SYNC_STALL_MS = 8_000L
        private const val LIVE_KEEPALIVE_MS = 1_000L
        private const val LIVE_RENEW_TICKS = 33   // the ring measures in ~35s bursts, so re-trigger just before it idles
        private const val LIVE_TAG = "RingLive"
    }

    // ---------- public actions ----------

    fun setInterval(min: Int, reconnectAfter: Boolean) {
        status.value = "Setting $min min…"
        withRing {
            doWrite(buildSetPacket(min))
            if (reconnectAfter) handler.postDelayed({ reconnect(min) }, 700)
            else handler.postDelayed({ status.value = "✓ Interval set to $min min" }, 350)
        }
    }

    fun readInterval() { status.value = "Reading…"; withRing { doWrite(buildReadPacket()) } }
    fun readBattery() { withRing { doWrite(packet(byteArrayOf(CMD_BATTERY.toByte()))) } }

    fun reconnect(applyMin: Int?) {
        val a = adapter() ?: return
        if (!a.isEnabled) { status.value = "Bluetooth is off"; return }
        status.value = "Reconnecting…"
        closeGatt()
        handler.postDelayed({
            val ad = adapter() ?: return@postDelayed
            pending = {
                if (applyMin != null) { doWrite(buildSetPacket(applyMin)); handler.postDelayed({ status.value = "✓ Set to $applyMin min (reconnected)" }, 350) }
                else doWrite(buildReadPacket())
            }
            connect(ad)
        }, RECONNECT_DELAY_MS)
    }

    fun sync(onDone: (SyncResult) -> Unit) {
        if (syncing) return
        onSyncDone = onDone
        syncStatus.value = "Syncing…"
        withRing {
            syncing = true; syncingState.value = true
            col.clear(); sleepCol.clear()
            for (m in MetricType.entries) col[m] = TreeMap()
            stage = Stage.HR
            syncOffsets.clear(); syncOffsets.addAll(listOf(0, -1, -2))
            requestNextDay()
        }
    }

    /**
     * Start a real-time heart-rate stream. The ring needs a periodic "continue" nudge or it
     * stops sending, so we re-arm a keep-alive until [stopLiveHr]. Values arrive on [liveHr].
     * NOTE: exact real-time opcodes vary a little across firmware — verify on-device.
     */
    fun startLiveHr() {
        liveRequested = true
        liveStatus.value = "Connecting…"
        withRing {
            liveOn = true
            liveRequested = false
            liveHr.value = null
            liveTick = 0
            liveStatus.value = "Measuring — hold still, ~30s to lock"
            doWrite(packet(byteArrayOf(CMD_HR_START.toByte(), HR_TYPE.toByte(), 0x00)))
            scheduleLiveKeepAlive()
        }
    }

    fun stopLiveHr() {
        liveOn = false; liveRequested = false
        liveKeepAlive?.let { handler.removeCallbacks(it) }; liveKeepAlive = null
        if (ready && writeChar != null) doWrite(packet(byteArrayOf(CMD_HR_STOP.toByte(), HR_TYPE.toByte(), 0, 0)))
        liveHr.value = null
        liveStatus.value = ""
    }

    private fun scheduleLiveKeepAlive() {
        liveKeepAlive?.let { handler.removeCallbacks(it) }
        liveKeepAlive = Runnable {
            if (liveOn && ready) {
                doWrite(packet(byteArrayOf(CMD_HR_POLL.toByte(), 0x03)))
                // The ring only measures in short bursts then idles, so cleanly restart the
                // measurement (stop then start) just before each burst ends to keep it going.
                if (++liveTick % LIVE_RENEW_TICKS == 0) {
                    doWrite(packet(byteArrayOf(CMD_HR_STOP.toByte(), HR_TYPE.toByte(), 0, 0)))
                    handler.postDelayed({ if (liveOn && ready) doWrite(packet(byteArrayOf(CMD_HR_START.toByte(), HR_TYPE.toByte(), 0x00))) }, 200)
                }
                scheduleLiveKeepAlive()
            }
        }
        handler.postDelayed(liveKeepAlive!!, LIVE_KEEPALIVE_MS)
    }

    // ---------- camera shutter ----------

    /**
     * Put the ring into camera mode: it shows a shutter icon and a shake now emits a "take photo"
     * gesture that fires [onCameraShutter]. A periodic keep-alive matches QRing (every 2 s).
     */
    fun enterCameraMode() {
        cameraOn = true
        cameraMode.value = true
        withRing {
            doWrite(packet(byteArrayOf(CMD_CAMERA.toByte(), CAM_ENTER.toByte())))
            scheduleCameraKeepAlive()
        }
    }

    fun exitCameraMode() {
        cameraOn = false; cameraMode.value = false
        cameraKeepAlive?.let { handler.removeCallbacks(it) }; cameraKeepAlive = null
        if (ready && writeChar != null) doWrite(packet(byteArrayOf(CMD_CAMERA.toByte(), CAM_FINISH.toByte())))
    }

    private fun scheduleCameraKeepAlive() {
        cameraKeepAlive?.let { handler.removeCallbacks(it) }
        cameraKeepAlive = Runnable {
            if (cameraOn && ready) { doWrite(packet(byteArrayOf(CMD_CAMERA.toByte(), CAM_KEEP.toByte()))); scheduleCameraKeepAlive() }
        }
        handler.postDelayed(cameraKeepAlive!!, CAM_KEEPALIVE_MS)
    }

    // StartHeartRateRsp shares no opcode with this; camera notifies come back on cmd 0x02.
    private fun handleCameraNotify(r: ByteArray) {
        if (r.size < 2) return
        Log.d(LIVE_TAG, "camera <- ${hex(r)}")
        if ((r[1].toInt() and 0xFF) == CAM_TAKE_PHOTO) { status.value = "📷 Shutter"; onCameraShutter?.invoke() }
    }

    fun disconnect() = closeGatt()

    // ---------- connection ----------

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun withRing(action: () -> Unit) {
        pending = action
        val a = adapter()
        if (a == null || !a.isEnabled) { status.value = "Bluetooth is off — turn it on"; return }
        if (ready && writeChar != null) runPending() else connect(a)
    }

    private fun runPending() { val p = pending; pending = null; p?.invoke() }

    @SuppressLint("MissingPermission")
    private fun connect(a: BluetoothAdapter) {
        if (connecting) return
        connecting = true; conn.value = Conn.CONNECTING; status.value = "Connecting to ring…"
        try {
            gatt = a.getRemoteDevice(mac).connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) { connecting = false; status.value = "Connect error"; return }
        cancelConnectTimeout()
        connectTimeout = Runnable {
            if (connecting && !ready) {
                connecting = false; closeGatt()
                status.value = "Couldn't reach the ring — wake it & close the QRing app."
                if (liveRequested) { liveRequested = false; liveStatus.value = "Couldn't reach the ring — wear it & close QRing" }
            }
        }
        handler.postDelayed(connectTimeout!!, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() { connectTimeout?.let { handler.removeCallbacks(it) }; connectTimeout = null }

    private fun onReady() {
        cancelConnectTimeout(); ready = true; connecting = false; conn.value = Conn.CONNECTED
        status.value = "Connected ✓"
        // Run on the main handler so the sync accumulator and the (also main-posted) BLE
        // responses share one thread. Battery only when idle, to not collide with the sync's
        // first request.
        handler.post { if (pending != null) runPending() else readBatteryNow() }
        // If the user left camera mode on, re-arm it after a reconnect.
        if (cameraOn) handler.post { doWrite(packet(byteArrayOf(CMD_CAMERA.toByte(), CAM_ENTER.toByte()))); scheduleCameraKeepAlive() }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryNow() { doWrite(packet(byteArrayOf(CMD_BATTERY.toByte()))) }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
            when (ns) {
                BluetoothProfile.STATE_CONNECTED -> { status.value = "Discovering…"; g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting = false; ready = false; writeChar = null; v2WriteChar = null
                    conn.value = Conn.DISCONNECTED
                    if (gatt === g) gatt = null
                    try { g.close() } catch (_: Exception) {}
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val svc1 = g.getService(SVC)
            writeChar = svc1?.getCharacteristic(WRITE_UUID)
            val nc1 = svc1?.getCharacteristic(NOTIFY_UUID)
            if (writeChar == null || nc1 == null) { connecting = false; status.value = "Ring service not found"; return }
            cccdQueue.clear()
            g.setCharacteristicNotification(nc1, true)
            nc1.getDescriptor(CCCD)?.let { cccdQueue.addLast(it) }
            val svc2 = g.getService(SVC_V2)
            v2WriteChar = svc2?.getCharacteristic(CMD_V2_UUID)
            val nc2 = svc2?.getCharacteristic(NOTIFY_V2_UUID)
            if (nc2 != null) { g.setCharacteristicNotification(nc2, true); nc2.getDescriptor(CCCD)?.let { cccdQueue.addLast(it) } }
            writeNextCccd(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) = writeNextCccd(g)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray) = route(ch.uuid, v.copyOf())
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { route(ch.uuid, (ch.value ?: return).copyOf()) }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextCccd(g: BluetoothGatt) {
        val d = cccdQueue.removeFirstOrNull()
        if (d == null) { onReady(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        else { @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
    }

    // Post to the main handler so all response parsing runs on one thread (see onReady).
    private fun route(uuid: UUID, v: ByteArray) { handler.post { if (uuid == NOTIFY_V2_UUID) onBigData(v) else handleResponse(v) } }

    // ---------- V1 responses ----------

    private fun handleResponse(r: ByteArray) {
        if (r.isEmpty()) return
        when (r[0].toInt() and 0xFF) {
            CMD_CAMERA -> handleCameraNotify(r)
            CMD_BATTERY -> if (r.size >= 3) battery.value = BatteryInfo(r[1].toInt() and 0xFF, (r[2].toInt() and 0xFF) == 1)
            CMD_HR_LOG -> if (r.size >= 4) interval.value = IntervalInfo((r[2].toInt() and 0xFF) == 1, r[3].toInt() and 0xFF).also {
                status.value = "Ring is set to ${it.minutes} min (${if (it.enabled) "ON" else "OFF"})"
            }
            CMD_HR_READ -> handleHrPacket(r)
            CMD_STEPS -> handleStepsPacket(r)
            CMD_STRESS -> handleStressPacket(r)
            CMD_HRV -> handleHrvPacket(r)
            CMD_HR_START -> handleLivePacket(r)
            CMD_HR_POLL, CMD_HR_POLL_RSP -> handleLivePoll(r)
            else -> if (liveOn) Log.d(LIVE_TAG, "other <- ${hex(r)}")
        }
    }

    // StartHeartRateRsp: [0x69, type, err, hr, …]
    private fun handleLivePacket(r: ByteArray) {
        if (!liveOn) return
        Log.d(LIVE_TAG, "recv <- ${hex(r)}")
        if (r.size < 4) return
        val bpm = r[3].toInt() and 0xFF
        if (bpm in HR_VALID) { liveHr.value = bpm; liveStatus.value = "Live" }
        else if (liveHr.value == null) liveStatus.value = "Measuring — hold still, ~30s to lock"
    }

    // RealTimeHeartRateRsp (cmd 30, replied as 0x9e): [0x9e, hr, …]. 0xEE = warming up.
    private fun handleLivePoll(r: ByteArray) {
        if (!liveOn || r.size < 2) return
        Log.d(LIVE_TAG, "poll <- ${hex(r)}")
        val bpm = r[1].toInt() and 0xFF
        if (bpm in HR_VALID) { liveHr.value = bpm; liveStatus.value = "Live" }
        else if (liveHr.value == null) liveStatus.value = "Measuring — hold still, ~30s to lock"
    }

    private fun hex(b: ByteArray) = RingProtocol.hex(b)

    private fun addSample(m: MetricType, epoch: Long, value: Int) { col[m]?.put(epoch, value) }

    // ---------- sync stages ----------

    private fun requestNextDay() {
        val off = syncOffsets.removeFirstOrNull()
        if (off == null) { startSteps(); return }
        curSize = 0; curCount = 0; curDayIdx = 0; curDayStart = midnight(off)
        scheduleStall(); doWrite(packet(hrReadPayload(curDayStart)))
    }

    private fun handleHrPacket(r: ByteArray) {
        if (!syncing || r.size < 15) return
        scheduleStall()
        when (r[1].toInt() and 0xFF) {
            0xFF -> requestNextDay()
            0 -> { curSize = r[2].toInt() and 0xFF; curInterval = (r[3].toInt() and 0xFF).coerceAtLeast(1); curCount = 0; curDayIdx = 0; if (curSize == 0) requestNextDay() }
            1 -> { curDayStart = le32(r, 2); for (i in 6..14) addHr(r[i]); curCount++; if (curCount >= curSize - 1) requestNextDay() }
            else -> { for (i in 2..14) addHr(r[i]); curCount++; if (curCount >= curSize - 1) requestNextDay() }
        }
    }

    private fun addHr(byte: Byte) {
        val bpm = byte.toInt() and 0xFF
        val ts = curDayStart + curDayIdx.toLong() * curInterval * 60
        curDayIdx++
        if (bpm in 1..250 && ts <= System.currentTimeMillis() / 1000 + 60) addSample(MetricType.HR, ts, bpm)
    }

    private fun startSteps() { stage = Stage.STEPS; syncStatus.value = "Syncing steps…"; stepsOffsets.clear(); stepsOffsets.addAll(listOf(0, 1, 2)); requestNextStepsDay() }

    private fun requestNextStepsDay() {
        val d = stepsOffsets.removeFirstOrNull()
        if (d == null) { startSpo2(); return }
        scheduleStall(); doWrite(packet(byteArrayOf(CMD_STEPS.toByte(), d.toByte(), 0x0f, 0x00, 0x5f, 0x01)))
    }

    private fun handleStepsPacket(r: ByteArray) {
        if (!syncing || stage != Stage.STEPS || r.size < 13) return
        scheduleStall()
        when (r[1].toInt() and 0xFF) { 0xFF -> { requestNextStepsDay(); return }; 0xF0 -> return }
        val year = 2000 + bcd(r[1])
        val q = r[4].toInt() and 0xFF
        val ts = ymd(year, bcd(r[2]) - 1, bcd(r[3]), q / 4, (q % 4) * 15)
        val steps = u16(r, 9)
        if (steps in 0..100_000 && year in 2020..2100) addSample(MetricType.STEPS, ts, steps)
        if ((r[5].toInt() and 0xFF) >= (r[6].toInt() and 0xFF) - 1) requestNextStepsDay()
    }

    private fun startSpo2() {
        stage = Stage.SPO2; syncStatus.value = "Syncing SpO2…"
        if (v2WriteChar == null) { startStress(); return }
        bigData.reset(); scheduleStall()
        writeV2(byteArrayOf(CMD_BIG_DATA.toByte(), BD_SPO2.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte()))
    }

    private fun startSleep() {
        stage = Stage.SLEEP; syncStatus.value = "Syncing sleep…"
        if (v2WriteChar == null) { startStress(); return }
        bigData.reset(); scheduleStall()
        writeV2(byteArrayOf(CMD_BIG_DATA.toByte(), BD_SLEEP.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte()))
    }

    private fun onBigData(v: ByteArray) {
        if (liveOn) Log.d(LIVE_TAG, "v2 <- ${hex(v)}")
        if (!syncing) return
        scheduleStall()
        bigData.write(v)
        val buf = bigData.toByteArray()
        if (buf.isNotEmpty() && (buf[0].toInt() and 0xFF) != CMD_BIG_DATA) { bigData.reset(); return }
        if (buf.size < 6) return
        val len = u16(buf, 2)
        if (buf.size < len + 6) return
        bigData.reset()
        when (buf[1].toInt() and 0xFF) {
            BD_SPO2 -> { parseSpo2(buf, len); startSleep() }
            BD_SLEEP -> { parseSleep(buf, len); startStress() }
        }
    }

    private fun parseSpo2(v: ByteArray, len: Int) {
        var i = 6; var daysAgo = -1
        while (daysAgo != 0 && i - 6 < len && i < v.size) {
            daysAgo = v[i].toInt() and 0xFF; i++
            for (hour in 0..23) {
                if (i + 1 >= v.size) break
                val mn = v[i].toInt() and 0xFF; i++
                val mx = v[i].toInt() and 0xFF; i++
                if (mn > 0 && mx > 0) addSample(MetricType.SPO2, midnight(-daysAgo) + hour * 3600L, Math.round((mn + mx) / 2.0f))
                if (i - 6 >= len) break
            }
        }
    }

    private fun parseSleep(v: ByteArray, len: Int) {
        if (len < 2 || v.size < 7) return
        val days = v[6].toInt() and 0xFF
        var i = 7
        for (d in 0 until days) {
            if (i + 5 >= v.size) break
            val daysAgo = v[i].toInt() and 0xFF; i++
            val dayBytes = v[i].toInt() and 0xFF; i++
            val sStart = u16(v, i); i += 2
            val sEnd = u16(v, i); i += 2
            var stageStart = midnight(-daysAgo) + (if (sStart > sEnd) sStart - 1440 else sStart) * 60L
            var j = 4
            while (j < dayBytes && i + 1 < v.size) {
                val type = v[i].toInt() and 0xFF
                val mins = v[i + 1].toInt() and 0xFF
                i += 2; j += 2
                if (mins > 0) { sleepCol[stageStart] = intArrayOf(type, mins); stageStart += mins * 60L }
            }
        }
    }

    private fun startStress() { stage = Stage.STRESS; syncStatus.value = "Syncing stress…"; scheduleStall(); doWrite(packet(byteArrayOf(CMD_STRESS.toByte()))) }

    private fun handleStressPacket(r: ByteArray) {
        if (!syncing || stage != Stage.STRESS) return
        scheduleStall()
        val nr = r[1].toInt() and 0xFF
        if (nr == 0xFF) { startHrv(); return }
        if (nr == 0) return
        val start = if (nr == 1) 3 else 2
        val prev = if (nr > 1) 12 * 30 + (nr - 2) * 13 * 30 else 0
        for (i in start until r.size - 1) { val vv = r[i].toInt() and 0xFF; if (vv != 0) addSample(MetricType.STRESS, midnight(0) + (prev + (i - start) * 30) * 60L, vv) }
        if (nr >= 4) startHrv()
    }

    private fun startHrv() { stage = Stage.HRV; curHrvDay = 0; syncStatus.value = "Syncing HRV…"; requestHrvDay() }
    private fun requestHrvDay() { scheduleStall(); doWrite(packet(byteArrayOf(CMD_HRV.toByte(), curHrvDay.toByte(), 0x00, 0x00, 0x00))) }
    private fun nextHrvDay() { curHrvDay++; if (curHrvDay <= 2) requestHrvDay() else finishSync() }

    private fun handleHrvPacket(r: ByteArray) {
        if (!syncing || stage != Stage.HRV) return
        scheduleStall()
        val nr = r[1].toInt() and 0xFF
        if (nr == 0xFF) { nextHrvDay(); return }
        if (nr == 0) return
        val start = if (nr == 1) 3 else 2
        val prev = if (nr > 1) 12 * 30 + (nr - 2) * 13 * 30 else 0
        for (i in start until r.size - 1) { val vv = r[i].toInt() and 0xFF; if (vv != 0) addSample(MetricType.HRV, midnight(-curHrvDay) + (prev + (i - start) * 30) * 60L, vv) }
        if (nr >= 4) nextHrvDay()
    }

    private fun finishSync() {
        cancelStall()
        if (!syncing) return
        syncing = false; syncingState.value = false
        val samples = mutableListOf<MetricSample>()
        val counts = mutableMapOf<MetricType, Int>()
        for (m in MetricType.entries) {
            val map = col[m] ?: TreeMap()
            counts[m] = map.size
            for ((e, vv) in map) samples += MetricSample(m, e, vv)
        }
        val sleep = sleepCol.map { SleepSegment(it.key, it.value[0], it.value[1]) }
        val result = SyncResult(samples, sleep, counts, sleep.size)
        syncStatus.value = "Synced ✓  " + MetricType.entries.joinToString("·") { "${it.short} ${counts[it] ?: 0}" } + " · Sleep ${sleep.size}"
        handler.post { onSyncDone?.invoke(result) }
        handler.postDelayed({ if (conn.value == Conn.CONNECTED) readBatteryNow() }, 500)
    }

    private fun scheduleStall() { cancelStall(); syncTimeout = Runnable { if (syncing) stageTimedOut() }; handler.postDelayed(syncTimeout!!, SYNC_STALL_MS) }
    private fun cancelStall() { syncTimeout?.let { handler.removeCallbacks(it) }; syncTimeout = null }
    private fun stageTimedOut() {
        when (stage) {
            Stage.HR -> startSteps(); Stage.STEPS -> startSpo2(); Stage.SPO2 -> startSleep()
            Stage.SLEEP -> startStress(); Stage.STRESS -> startHrv(); Stage.HRV -> finishSync()
        }
    }

    // ---------- writes / helpers ----------

    @SuppressLint("MissingPermission")
    private fun doWrite(pkt: ByteArray) {
        val g = gatt ?: return; val ch = writeChar ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeCharacteristic(ch, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        else { @Suppress("DEPRECATION") run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = pkt; g.writeCharacteristic(ch) } }
    }

    @SuppressLint("MissingPermission")
    private fun writeV2(pkt: ByteArray) {
        val g = gatt ?: return; val ch = v2WriteChar ?: return
        val noResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeCharacteristic(ch, pkt, type)
        else { @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = pkt; g.writeCharacteristic(ch) } }
    }

    private fun buildSetPacket(min: Int) = packet(byteArrayOf(0x16, 0x02, 0x01, (min and 0xFF).toByte()))
    private fun buildReadPacket() = packet(byteArrayOf(0x16, 0x01))
    private fun hrReadPayload(epoch: Long): ByteArray {
        val ts = epoch.toInt()
        return byteArrayOf(CMD_HR_READ.toByte(), (ts and 0xFF).toByte(), ((ts ushr 8) and 0xFF).toByte(), ((ts ushr 16) and 0xFF).toByte(), ((ts ushr 24) and 0xFF).toByte())
    }

    // Wire-format helpers live in RingProtocol; these thin delegates keep the call sites tidy.
    private fun packet(head: ByteArray) = RingProtocol.packet(head)
    private fun u16(r: ByteArray, off: Int) = RingProtocol.u16(r, off)
    private fun le32(r: ByteArray, off: Int) = RingProtocol.le32(r, off)
    private fun bcd(b: Byte) = RingProtocol.bcd(b)
    private fun midnight(dayOffset: Int) = RingProtocol.midnight(dayOffset)
    private fun ymd(y: Int, mo0: Int, d: Int, h: Int, mi: Int) = RingProtocol.ymd(y, mo0, d, h, mi)

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        cancelConnectTimeout()
        liveOn = false; liveKeepAlive?.let { handler.removeCallbacks(it) }; liveKeepAlive = null; liveHr.value = null
        cameraKeepAlive?.let { handler.removeCallbacks(it) }; cameraKeepAlive = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; writeChar = null; v2WriteChar = null; ready = false; connecting = false; conn.value = Conn.DISCONNECTED
    }
}
