package com.krejci.qringset

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
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.krejci.qringset.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null      // V1 write (Nordic-UART RX)
    private var v2WriteChar: BluetoothGattCharacteristic? = null    // V2 big-data command
    private var connecting = false
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var connectTimeout: Runnable? = null
    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()

    // ---- sync state ----
    private enum class Stage { HR, STEPS, SPO2, SLEEP, STRESS, HRV }
    private var stage = Stage.HR
    private var syncing = false
    private var syncTimeout: Runnable? = null
    private val bigData = ByteArrayOutputStream()

    private val hrHistory = TreeMap<Long, Int>()
    private val spo2History = TreeMap<Long, Int>()
    private val stressHistory = TreeMap<Long, Int>()
    private val hrvHistory = TreeMap<Long, Int>()
    private val stepsHistory = TreeMap<Long, IntArray>()            // epoch -> [steps, calories, distance_m]
    private val sleepStages = TreeMap<Long, IntArray>()             // stageStart epoch -> [stageType, durationMin]

    private var newHr = 0; private var newSteps = 0; private var newSpo2 = 0
    private var newSleep = 0; private var newStress = 0; private var newHrv = 0

    private val syncOffsets = ArrayDeque<Int>()   // HR day offsets (0,-1,-2)
    private val stepsOffsets = ArrayDeque<Int>()  // steps daysAgo (0,1,2)
    private var curHrvDay = 0

    // HR-packet accumulation
    private var curInterval = 5
    private var curSize = 0
    private var curCount = 0
    private var curDayStart = 0L
    private var curDayIdx = 0

    companion object {
        private val MAC = BuildConfig.RING_MAC
        private val SVC: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val SVC_V2: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        private val CMD_V2_UUID: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        private val NOTIFY_V2_UUID: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CMD_HR_LOG = 0x16
        private const val CMD_HR_READ = 0x15
        private const val CMD_STEPS = 0x43
        private const val CMD_STRESS = 0x37
        private const val CMD_HRV = 0x39
        private const val CMD_BIG_DATA = 0xBC
        private const val BD_SPO2 = 0x2A
        private const val BD_SLEEP = 0x27

        private const val HR_CSV = "ring_hr.csv"
        private const val STEPS_CSV = "ring_steps.csv"
        private const val SPO2_CSV = "ring_spo2.csv"
        private const val SLEEP_CSV = "ring_sleep.csv"
        private const val STRESS_CSV = "ring_stress.csv"
        private const val HRV_CSV = "ring_hrv.csv"

        private const val SYNC_STALL_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val RECONNECT_DELAY_MS = 1_600L
        private const val REQ_PERM = 42
        private const val KEY_LAST = "last_interval"
        private const val KEY_AUTO = "auto_reconnect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = getSharedPreferences("ringset", Context.MODE_PRIVATE)

        val presets = listOf(b.p1 to 1, b.p3 to 3, b.p5 to 5, b.p10 to 10, b.p30 to 30, b.p60 to 60)
        for ((btn, min) in presets) btn.setOnClickListener { setInterval(min) }

        b.setCustom.setOnClickListener {
            val v = b.customValue.text?.toString()?.toIntOrNull()
            if (v == null || v < 1 || v > 255) toast("Enter a number 1–255") else setInterval(v)
        }
        b.readBtn.setOnClickListener { readInterval() }
        b.autoReconnect.isChecked = prefs.getBoolean(KEY_AUTO, false)
        b.autoReconnect.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(KEY_AUTO, c).apply() }
        b.reconnectBtn.setOnClickListener {
            val last = prefs.getInt(KEY_LAST, -1)
            reconnectCycle(if (last in 1..255) last else null)
        }
        b.syncBtn.setOnClickListener { startSync() }
        b.shareBtn.setOnClickListener { shareCsv() }

        b.footer.text = "Ring ${BuildConfig.RING_MAC}\nWake the ring & close the QRing app before setting."
    }

    // ---------- interval set / read ----------

    private fun setInterval(min: Int) {
        prefs.edit().putInt(KEY_LAST, min).apply()
        setStatus("Setting $min min…")
        val auto = b.autoReconnect.isChecked
        withRing {
            doWrite(buildSetPacket(min), confirm = if (auto) null else "✓ Interval set to $min min")
            if (auto) handler.postDelayed({ reconnectCycle(min) }, 700)
        }
    }

    private fun readInterval() {
        setStatus("Reading…")
        withRing { doWrite(buildReadPacket(), confirm = null) }
    }

    private fun reconnectCycle(applyMin: Int?) {
        if (!hasPerm()) { askPerm(); return }
        val adapter = btAdapter()
        if (adapter == null || !adapter.isEnabled) { setStatus("Bluetooth is off — turn it on"); return }
        setStatus("Reconnecting…")
        closeGatt()
        handler.postDelayed({
            val a = btAdapter() ?: return@postDelayed
            pending = {
                if (applyMin != null) doWrite(buildSetPacket(applyMin), "✓ Set to $applyMin min (reconnected)")
                else doWrite(buildReadPacket(), null)
            }
            connect(a)
        }, RECONNECT_DELAY_MS)
    }

    // ---------- connection ----------

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun withRing(action: () -> Unit) {
        pending = action
        if (!hasPerm()) { askPerm(); return }
        val adapter = btAdapter()
        if (adapter == null || !adapter.isEnabled) { setStatus("Bluetooth is off — turn it on"); toast("Turn on Bluetooth"); return }
        if (ready && writeChar != null) runPending() else connect(adapter)
    }

    private fun runPending() { val p = pending; pending = null; p?.invoke() }

    @SuppressLint("MissingPermission")
    private fun connect(adapter: BluetoothAdapter) {
        if (connecting) return
        connecting = true
        setStatus("Connecting to ring…")
        gatt = adapter.getRemoteDevice(MAC).connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
        cancelConnectTimeout()
        connectTimeout = Runnable {
            if (connecting && !ready) {
                connecting = false; closeGatt()
                setStatus("Couldn't reach the ring.\nWake it (off charger / move it) and close the QRing app, then tap again.")
            }
        }
        handler.postDelayed(connectTimeout!!, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() { connectTimeout?.let { handler.removeCallbacks(it) }; connectTimeout = null }

    private fun onReady() {
        cancelConnectTimeout(); ready = true; connecting = false
        setStatus("Connected ✓"); runPending()
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { setStatus("Connected — discovering…"); g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting = false; ready = false; writeChar = null; v2WriteChar = null
                    if (gatt === g) gatt = null
                    try { g.close() } catch (_: Exception) {}
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc1 = g.getService(SVC)
            writeChar = svc1?.getCharacteristic(WRITE_UUID)
            val nc1 = svc1?.getCharacteristic(NOTIFY_UUID)
            if (writeChar == null || nc1 == null) { connecting = false; setStatus("Ring BLE service not found"); return }
            cccdQueue.clear()
            g.setCharacteristicNotification(nc1, true)
            nc1.getDescriptor(CCCD)?.let { cccdQueue.addLast(it) }

            val svc2 = g.getService(SVC_V2)
            v2WriteChar = svc2?.getCharacteristic(CMD_V2_UUID)
            val nc2 = svc2?.getCharacteristic(NOTIFY_V2_UUID)
            if (nc2 != null) {
                g.setCharacteristicNotification(nc2, true)
                nc2.getDescriptor(CCCD)?.let { cccdQueue.addLast(it) }
            }
            writeNextCccd(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            writeNextCccd(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            route(ch.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            route(ch.uuid, ch.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextCccd(g: BluetoothGatt) {
        val d = cccdQueue.removeFirstOrNull()
        if (d == null) { onReady(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }

    private fun route(uuid: UUID, value: ByteArray) {
        if (uuid == NOTIFY_V2_UUID) onBigData(value) else handleResponse(value)
    }

    // ---------- V1 tagged responses ----------

    private fun handleResponse(r: ByteArray) {
        if (r.isEmpty()) return
        when (r[0].toInt() and 0xFF) {
            CMD_HR_LOG -> {
                if (r.size < 4) return
                setStatus("Ring is set to ${r[3].toInt() and 0xFF} min  (${if ((r[2].toInt() and 0xFF) == 1) "ON" else "OFF"})")
            }
            CMD_HR_READ -> handleHrPacket(r)
            CMD_STEPS -> handleStepsPacket(r)
            CMD_STRESS -> handleStressPacket(r)
            CMD_HRV -> handleHrvPacket(r)
        }
    }

    // ---------- sync orchestration ----------

    private fun startSync() {
        if (syncing) { toast("Already syncing…"); return }
        setDataStatus("Syncing…")
        withRing {
            syncing = true
            newHr = 0; newSteps = 0; newSpo2 = 0; newSleep = 0; newStress = 0; newHrv = 0
            loadIntCsv(File(filesDir, HR_CSV), hrHistory)
            loadSteps()
            loadIntCsv(File(filesDir, SPO2_CSV), spo2History)
            loadSleep()
            loadIntCsv(File(filesDir, STRESS_CSV), stressHistory)
            loadIntCsv(File(filesDir, HRV_CSV), hrvHistory)
            stage = Stage.HR
            syncOffsets.clear(); syncOffsets.addAll(listOf(0, -1, -2))
            requestNextDay()
        }
    }

    // HR log (0x15)
    private fun requestNextDay() {
        val off = syncOffsets.removeFirstOrNull()
        if (off == null) { startStepsStage(); return }
        curSize = 0; curCount = 0; curDayIdx = 0
        curDayStart = midnightEpoch(off)
        scheduleSyncStall()
        doWrite(packet(hrReadPayload(curDayStart)), confirm = null)
    }

    private fun handleHrPacket(r: ByteArray) {
        if (!syncing || r.size < 15) return
        scheduleSyncStall()
        when (val sub = r[1].toInt() and 0xFF) {
            0xFF -> requestNextDay()
            0 -> {
                curSize = r[2].toInt() and 0xFF
                curInterval = (r[3].toInt() and 0xFF).coerceAtLeast(1)
                curCount = 0; curDayIdx = 0
                if (curSize == 0) requestNextDay()
            }
            1 -> { curDayStart = le32(r, 2); for (i in 6..14) addHr(r[i]); curCount++; if (curCount >= curSize - 1) requestNextDay() }
            else -> { for (i in 2..14) addHr(r[i]); curCount++; if (curCount >= curSize - 1) requestNextDay() }
        }
    }

    private fun addHr(byte: Byte) {
        val bpm = byte.toInt() and 0xFF
        val ts = curDayStart + curDayIdx.toLong() * curInterval * 60
        curDayIdx++
        if (bpm in 1..250 && ts <= System.currentTimeMillis() / 1000 + 60) {
            if (hrHistory.put(ts, bpm) == null) newHr++
        }
    }

    // Steps / activity (0x43)
    private fun startStepsStage() {
        stage = Stage.STEPS
        setDataStatus("Syncing steps…")
        stepsOffsets.clear(); stepsOffsets.addAll(listOf(0, 1, 2))
        requestNextStepsDay()
    }

    private fun requestNextStepsDay() {
        val d = stepsOffsets.removeFirstOrNull()
        if (d == null) { startSpo2Stage(); return }
        scheduleSyncStall()
        doWrite(packet(byteArrayOf(CMD_STEPS.toByte(), d.toByte(), 0x0f, 0x00, 0x5f, 0x01)), confirm = null)
    }

    private fun handleStepsPacket(r: ByteArray) {
        if (!syncing || stage != Stage.STEPS || r.size < 13) return
        scheduleSyncStall()
        when (r[1].toInt() and 0xFF) {
            0xFF -> { requestNextStepsDay(); return }
            0xF0 -> return
        }
        val year = 2000 + bcd(r[1])
        val q = r[4].toInt() and 0xFF
        val ts = ymdEpoch(year, bcd(r[2]) - 1, bcd(r[3]), q / 4, (q % 4) * 15)
        val steps = u16(r, 9)
        if (steps in 0..100_000 && year in 2020..2100) {
            if (stepsHistory.put(ts, intArrayOf(steps, u16(r, 7), u16(r, 11))) == null) newSteps++
        }
        if ((r[5].toInt() and 0xFF) >= (r[6].toInt() and 0xFF) - 1) requestNextStepsDay()
    }

    // SpO2 + sleep via big-data (0xbc) on the V2 channel
    private fun startSpo2Stage() {
        stage = Stage.SPO2
        setDataStatus("Syncing SpO2…")
        if (v2WriteChar == null) { startStressStage(); return }
        bigData.reset(); scheduleSyncStall()
        writeV2(byteArrayOf(CMD_BIG_DATA.toByte(), BD_SPO2.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte()))
    }

    private fun startSleepStage() {
        stage = Stage.SLEEP
        setDataStatus("Syncing sleep…")
        if (v2WriteChar == null) { startStressStage(); return }
        bigData.reset(); scheduleSyncStall()
        writeV2(byteArrayOf(CMD_BIG_DATA.toByte(), BD_SLEEP.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte()))
    }

    private fun onBigData(value: ByteArray) {
        if (!syncing) return
        scheduleSyncStall()
        bigData.write(value)
        val buf = bigData.toByteArray()
        if (buf.isNotEmpty() && (buf[0].toInt() and 0xFF) != CMD_BIG_DATA) { bigData.reset(); return }
        if (buf.size < 6) return
        val len = u16(buf, 2)
        if (buf.size < len + 6) return
        bigData.reset()
        when (buf[1].toInt() and 0xFF) {
            BD_SPO2 -> { parseSpo2(buf, len); startSleepStage() }
            BD_SLEEP -> { parseSleep(buf, len); startStressStage() }
        }
    }

    private fun parseSpo2(v: ByteArray, len: Int) {
        var index = 6
        var daysAgo = -1
        while (daysAgo != 0 && index - 6 < len && index < v.size) {
            daysAgo = v[index].toInt() and 0xFF; index++
            for (hour in 0..23) {
                if (index + 1 >= v.size) break
                val mn = v[index].toInt() and 0xFF; index++
                val mx = v[index].toInt() and 0xFF; index++
                if (mn > 0 && mx > 0) {
                    val ts = midnightEpoch(-daysAgo) + hour * 3600L
                    if (spo2History.put(ts, Math.round((mn + mx) / 2.0f)) == null) newSpo2++
                }
                if (index - 6 >= len) break
            }
        }
    }

    private fun parseSleep(v: ByteArray, len: Int) {
        if (len < 2 || v.size < 7) return
        val days = v[6].toInt() and 0xFF
        var index = 7
        for (d in 0 until days) {
            if (index + 5 >= v.size) break
            val daysAgo = v[index].toInt() and 0xFF; index++
            val dayBytes = v[index].toInt() and 0xFF; index++
            val sleepStart = u16(v, index); index += 2
            val sleepEnd = u16(v, index); index += 2
            val base = midnightEpoch(-daysAgo)
            var stageStart = base + (if (sleepStart > sleepEnd) sleepStart - 1440 else sleepStart) * 60L
            var j = 4
            while (j < dayBytes && index + 1 < v.size) {
                val type = v[index].toInt() and 0xFF
                val mins = v[index + 1].toInt() and 0xFF
                index += 2; j += 2
                if (mins > 0) {
                    if (sleepStages.put(stageStart, intArrayOf(type, mins)) == null) newSleep++
                    stageStart += mins * 60L
                }
            }
        }
    }

    // Stress (0x37) — today, 30-min values
    private fun startStressStage() {
        stage = Stage.STRESS
        setDataStatus("Syncing stress…")
        scheduleSyncStall()
        doWrite(packet(byteArrayOf(CMD_STRESS.toByte())), confirm = null)
    }

    private fun handleStressPacket(r: ByteArray) {
        if (!syncing || stage != Stage.STRESS) return
        scheduleSyncStall()
        val nr = r[1].toInt() and 0xFF
        if (nr == 0xFF) { startHrvStage(); return }
        if (nr == 0) return
        val start = if (nr == 1) 3 else 2
        val minsPrev = if (nr > 1) 12 * 30 + (nr - 2) * 13 * 30 else 0
        for (i in start until r.size - 1) {
            val vv = r[i].toInt() and 0xFF
            if (vv != 0) {
                val ts = midnightEpoch(0) + (minsPrev + (i - start) * 30) * 60L
                if (stressHistory.put(ts, vv) == null) newStress++
            }
        }
        if (nr >= 4) startHrvStage()
    }

    // HRV (0x39) — per day, 30-min values (often unanswered on this hardware)
    private fun startHrvStage() { stage = Stage.HRV; curHrvDay = 0; setDataStatus("Syncing HRV…"); requestHrvDay() }

    private fun requestHrvDay() {
        scheduleSyncStall()
        doWrite(packet(byteArrayOf(CMD_HRV.toByte(), curHrvDay.toByte(), 0x00, 0x00, 0x00)), confirm = null)
    }

    private fun nextHrvDay() { curHrvDay++; if (curHrvDay <= 2) requestHrvDay() else finishSync() }

    private fun handleHrvPacket(r: ByteArray) {
        if (!syncing || stage != Stage.HRV) return
        scheduleSyncStall()
        val nr = r[1].toInt() and 0xFF
        if (nr == 0xFF) { nextHrvDay(); return }
        if (nr == 0) return
        val start = if (nr == 1) 3 else 2
        val minsPrev = if (nr > 1) 12 * 30 + (nr - 2) * 13 * 30 else 0
        for (i in start until r.size - 1) {
            val vv = r[i].toInt() and 0xFF
            if (vv != 0) {
                val ts = midnightEpoch(-curHrvDay) + (minsPrev + (i - start) * 30) * 60L
                if (hrvHistory.put(ts, vv) == null) newHrv++
            }
        }
        if (nr >= 4) nextHrvDay()
    }

    private fun finishSync() {
        cancelSyncStall()
        if (!syncing) return
        syncing = false
        saveIntCsv(File(filesDir, HR_CSV), hrHistory, "bpm")
        saveSteps()
        saveIntCsv(File(filesDir, SPO2_CSV), spo2History, "spo2")
        saveSleep()
        saveIntCsv(File(filesDir, STRESS_CSV), stressHistory, "stress")
        saveIntCsv(File(filesDir, HRV_CSV), hrvHistory, "hrv_ms")
        setDataStatus(
            "Synced ✓\n" +
                "HR ${hrHistory.size}(+$newHr) · Steps ${stepsHistory.size}(+$newSteps) · SpO2 ${spo2History.size}(+$newSpo2)\n" +
                "Sleep ${sleepStages.size}(+$newSleep) · Stress ${stressHistory.size}(+$newStress) · HRV ${hrvHistory.size}(+$newHrv)"
        )
        toast("Sync complete")
    }

    private fun scheduleSyncStall() {
        cancelSyncStall()
        syncTimeout = Runnable { if (syncing) stageTimedOut() }
        handler.postDelayed(syncTimeout!!, SYNC_STALL_MS)
    }

    private fun cancelSyncStall() { syncTimeout?.let { handler.removeCallbacks(it) }; syncTimeout = null }

    /** A stage that goes quiet for too long is skipped so one data type can't stall the rest. */
    private fun stageTimedOut() {
        when (stage) {
            Stage.HR -> startStepsStage()
            Stage.STEPS -> startSpo2Stage()
            Stage.SPO2 -> startSleepStage()
            Stage.SLEEP -> startStressStage()
            Stage.STRESS -> startHrvStage()
            Stage.HRV -> finishSync()
        }
    }

    // ---------- writes ----------

    @SuppressLint("MissingPermission")
    private fun doWrite(pkt: ByteArray, confirm: String?) {
        val g = gatt; val ch = writeChar
        if (g == null || ch == null) { setStatus("Not connected"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = pkt; g.writeCharacteristic(ch) }
        }
        if (confirm != null) handler.postDelayed({ setStatus(confirm) }, 350)
    }

    @SuppressLint("MissingPermission")
    private fun writeV2(pkt: ByteArray) {
        val g = gatt ?: return; val ch = v2WriteChar ?: return
        val noResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, pkt, type)
        } else {
            @Suppress("DEPRECATION")
            run { ch.writeType = type; ch.value = pkt; g.writeCharacteristic(ch) }
        }
    }

    private fun buildSetPacket(min: Int) = packet(byteArrayOf(0x16, 0x02, 0x01, (min and 0xFF).toByte()))
    private fun buildReadPacket() = packet(byteArrayOf(0x16, 0x01))
    private fun hrReadPayload(epoch: Long): ByteArray {
        val ts = epoch.toInt()
        return byteArrayOf(CMD_HR_READ.toByte(),
            (ts and 0xFF).toByte(), ((ts ushr 8) and 0xFF).toByte(),
            ((ts ushr 16) and 0xFF).toByte(), ((ts ushr 24) and 0xFF).toByte())
    }

    private fun packet(head: ByteArray): ByteArray {
        val p = ByteArray(16)
        System.arraycopy(head, 0, p, 0, head.size)
        var sum = 0
        for (i in 0..14) sum += p[i].toInt() and 0xFF
        p[15] = (sum % 255).toByte()
        return p
    }

    private fun u16(r: ByteArray, off: Int) = (r[off].toInt() and 0xFF) or ((r[off + 1].toInt() and 0xFF) shl 8)
    private fun le32(r: ByteArray, off: Int): Long =
        (r[off].toLong() and 0xFF) or ((r[off + 1].toLong() and 0xFF) shl 8) or
            ((r[off + 2].toLong() and 0xFF) shl 16) or ((r[off + 3].toLong() and 0xFF) shl 24)
    private fun bcd(b: Byte): Int = ("%02x".format(b.toInt() and 0xFF)).toInt()

    private fun midnightEpoch(dayOffset: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        return c.timeInMillis / 1000
    }

    private fun ymdEpoch(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month0, day, hour, minute, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000
    }

    // ---------- CSV ----------

    private fun isoFmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private fun loadIntCsv(f: File, map: TreeMap<Long, Int>) {
        if (!f.exists()) return
        f.readLines().drop(1).forEach {
            val p = it.split(','); if (p.size >= 3) {
                val e = p[1].toLongOrNull(); val v = p[2].toIntOrNull()
                if (e != null && v != null) map[e] = v
            }
        }
    }

    private fun saveIntCsv(f: File, map: TreeMap<Long, Int>, valueHeader: String): File {
        val fmt = isoFmt()
        val sb = StringBuilder("timestamp,epoch_s,$valueHeader\n")
        for ((e, v) in map) sb.append(fmt.format(Date(e * 1000))).append(',').append(e).append(',').append(v).append('\n')
        f.writeText(sb.toString()); return f
    }

    private fun loadSteps() {
        val f = File(filesDir, STEPS_CSV); if (!f.exists()) return
        f.readLines().drop(1).forEach {
            val p = it.split(','); if (p.size >= 5) {
                val e = p[1].toLongOrNull(); val s = p[2].toIntOrNull(); val c = p[3].toIntOrNull(); val d = p[4].toIntOrNull()
                if (e != null && s != null && c != null && d != null) stepsHistory[e] = intArrayOf(s, c, d)
            }
        }
    }

    private fun saveSteps(): File {
        val fmt = isoFmt()
        val sb = StringBuilder("timestamp,epoch_s,steps,calories,distance_m\n")
        for ((e, a) in stepsHistory) sb.append(fmt.format(Date(e * 1000))).append(',').append(e).append(',')
            .append(a[0]).append(',').append(a[1]).append(',').append(a[2]).append('\n')
        val f = File(filesDir, STEPS_CSV); f.writeText(sb.toString()); return f
    }

    private fun sleepLabel(s: Int) = when (s) { 2 -> "light"; 3 -> "deep"; 4 -> "rem"; 5 -> "awake"; else -> "?" }

    private fun loadSleep() {
        val f = File(filesDir, SLEEP_CSV); if (!f.exists()) return
        f.readLines().drop(1).forEach {
            val p = it.split(','); if (p.size >= 5) {
                val e = p[1].toLongOrNull(); val st = p[2].toIntOrNull(); val dur = p[4].toIntOrNull()
                if (e != null && st != null && dur != null) sleepStages[e] = intArrayOf(st, dur)
            }
        }
    }

    private fun saveSleep(): File {
        val fmt = isoFmt()
        val sb = StringBuilder("timestamp,epoch_s,stage,stage_label,duration_min\n")
        for ((e, a) in sleepStages) sb.append(fmt.format(Date(e * 1000))).append(',').append(e).append(',')
            .append(a[0]).append(',').append(sleepLabel(a[0])).append(',').append(a[1]).append('\n')
        val f = File(filesDir, SLEEP_CSV); f.writeText(sb.toString()); return f
    }

    private fun shareCsv() {
        val files = filesDir.listFiles { f -> f.name.endsWith(".csv") }?.toList().orEmpty()
        if (files.isEmpty()) { toast("No data yet — tap Sync first"); return }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ring CSVs"))
    }

    // ---------- permissions / misc ----------

    private fun hasPerm(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) pending?.let { withRing(it) }
            else setStatus("Bluetooth permission denied")
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        cancelConnectTimeout()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; writeChar = null; v2WriteChar = null; ready = false; connecting = false
    }

    private fun setStatus(s: String) = runOnUiThread { b.status.text = s }
    private fun setDataStatus(s: String) = runOnUiThread { b.dataStatus.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() { super.onDestroy(); closeGatt() }
}
