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
    private var writeChar: BluetoothGattCharacteristic? = null
    private var connecting = false
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var connectTimeout: Runnable? = null

    // ---- heart-rate log sync state ----
    private val hrHistory = TreeMap<Long, Int>()   // epochSeconds -> bpm (merged across syncs)
    private val syncOffsets = ArrayDeque<Int>()     // day offsets still to request (0 = today)
    private var syncing = false
    private var syncTimeout: Runnable? = null
    private var newSampleCount = 0
    private var curInterval = 5
    private var curSize = 0
    private var curCount = 0
    private var curDayStart = 0L
    private var curDayIdx = 0

    private enum class Stage { HR, STEPS }
    private var stage = Stage.HR
    private val stepsOffsets = ArrayDeque<Int>()            // day counts still to request (0 = today)
    private val stepsHistory = TreeMap<Long, IntArray>()    // epochSeconds -> [steps, calories, distance_m]
    private var newStepsCount = 0

    companion object {
        // Set via RING_MAC env var or ring.mac in local.properties at build time.
        private val MAC = BuildConfig.RING_MAC
        private val SVC: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CMD_HR_LOG = 0x16
        private const val CMD_HR_READ = 0x15
        private const val CMD_STEPS = 0x43
        private const val HR_CSV = "ring_hr.csv"
        private const val STEPS_CSV = "ring_steps.csv"
        private const val SYNC_STALL_MS = 8_000L
        private const val REQ_PERM = 42
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val RECONNECT_DELAY_MS = 1_600L
        private const val KEY_LAST = "last_interval"
        private const val KEY_AUTO = "auto_reconnect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = getSharedPreferences("ringset", Context.MODE_PRIVATE)

        val presets = listOf(
            b.p1 to 1, b.p3 to 3, b.p5 to 5,
            b.p10 to 10, b.p30 to 30, b.p60 to 60
        )
        for ((btn, min) in presets) btn.setOnClickListener { setInterval(min) }

        b.setCustom.setOnClickListener {
            val v = b.customValue.text?.toString()?.toIntOrNull()
            if (v == null || v < 1 || v > 255) toast("Enter a number 1–255")
            else setInterval(v)
        }
        b.readBtn.setOnClickListener { readInterval() }

        b.autoReconnect.isChecked = prefs.getBoolean(KEY_AUTO, false)
        b.autoReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO, checked).apply()
        }
        b.reconnectBtn.setOnClickListener {
            val last = prefs.getInt(KEY_LAST, -1)
            reconnectCycle(if (last in 1..255) last else null)
        }
        b.syncBtn.setOnClickListener { startSync() }
        b.shareBtn.setOnClickListener { shareCsv() }

        b.footer.text = "Ring ${BuildConfig.RING_MAC}\nWake the ring & close the QRing app before setting."
    }

    // ---- public actions ----

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

    /** Cleanly drop the BLE link and reconnect, then re-apply [applyMin] so the ring commits it. */
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

    // ---- connection orchestration ----

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun withRing(action: () -> Unit) {
        pending = action
        if (!hasPerm()) { askPerm(); return }
        val adapter = btAdapter()
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off — turn it on")
            toast("Turn on Bluetooth")
            return
        }
        if (ready && writeChar != null) runPending() else connect(adapter)
    }

    private fun runPending() {
        val p = pending
        pending = null
        p?.invoke()
    }

    @SuppressLint("MissingPermission")
    private fun connect(adapter: BluetoothAdapter) {
        if (connecting) return
        connecting = true
        setStatus("Connecting to ring…")
        val device = adapter.getRemoteDevice(MAC)
        gatt = device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
        cancelConnectTimeout()
        connectTimeout = Runnable {
            if (connecting && !ready) {
                connecting = false
                closeGatt()
                setStatus("Couldn't reach the ring.\nWake it (off charger / move it) and close the QRing app, then tap again.")
            }
        }
        handler.postDelayed(connectTimeout!!, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
    }

    private fun onReady() {
        cancelConnectTimeout()
        ready = true
        connecting = false
        setStatus("Connected ✓")
        runPending()
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    setStatus("Connected — discovering…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting = false
                    ready = false
                    writeChar = null
                    if (gatt === g) gatt = null
                    try { g.close() } catch (_: Exception) {}
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC)
            val wc = svc?.getCharacteristic(WRITE_UUID)
            val nc = svc?.getCharacteristic(NOTIFY_UUID)
            if (wc == null || nc == null) {
                connecting = false
                setStatus("Ring BLE service not found")
                return
            }
            writeChar = wc
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(CCCD)
            if (cccd == null) { onReady(); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onReady()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleResponse(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleResponse(ch.value ?: return)
        }
    }

    private fun handleResponse(r: ByteArray) {
        if (r.isEmpty()) return
        when (r[0].toInt() and 0xFF) {
            CMD_HR_LOG -> {
                if (r.size < 4) return
                val enabled = r[2].toInt() and 0xFF
                val interval = r[3].toInt() and 0xFF
                val state = if (enabled == 1) "ON" else "OFF"
                setStatus("Ring is set to $interval min  ($state)")
            }
            CMD_HR_READ -> handleHrPacket(r)
            CMD_STEPS -> handleStepsPacket(r)
        }
    }

    // ---- heart-rate log sync ----

    private fun startSync() {
        if (syncing) { toast("Already syncing…"); return }
        setDataStatus("Syncing…")
        withRing {
            syncing = true
            newSampleCount = 0
            newStepsCount = 0
            loadHistory()
            loadSteps()
            stage = Stage.HR
            syncOffsets.clear()
            syncOffsets.addAll(listOf(0, -1, -2))   // today + 2 previous days
            requestNextDay()
        }
    }

    private fun requestNextDay() {
        val off = syncOffsets.removeFirstOrNull()
        if (off == null) { startStepsStage(); return }
        curSize = 0; curCount = 0; curDayIdx = 0
        curDayStart = midnightEpoch(off)
        scheduleSyncStall()
        doWrite(packet(hrReadPayload(curDayStart)), confirm = null)
    }

    private fun startStepsStage() {
        stage = Stage.STEPS
        setDataStatus("Syncing steps…")
        stepsOffsets.clear()
        stepsOffsets.addAll(listOf(0, 1, 2))        // daysAgo: today + 2 previous
        requestNextStepsDay()
    }

    private fun requestNextStepsDay() {
        val d = stepsOffsets.removeFirstOrNull()
        if (d == null) { finishSync(); return }
        scheduleSyncStall()
        doWrite(packet(byteArrayOf(CMD_STEPS.toByte(), d.toByte(), 0x0f, 0x00, 0x5f, 0x01)), confirm = null)
    }

    private fun handleStepsPacket(r: ByteArray) {
        if (!syncing || stage != Stage.STEPS || r.size < 13) return
        scheduleSyncStall()
        when (r[1].toInt() and 0xFF) {
            0xFF -> { requestNextStepsDay(); return }   // empty day
            0xF0 -> return                              // header packet, no sample
        }
        val year = 2000 + bcd(r[1])
        val month0 = bcd(r[2]) - 1
        val day = bcd(r[3])
        val q = r[4].toInt() and 0xFF
        val ts = ymdEpoch(year, month0, day, q / 4, (q % 4) * 15)
        val cal = u16(r, 7); val steps = u16(r, 9); val dist = u16(r, 11)
        if (steps in 0..100_000 && year in 2020..2100) {
            if (stepsHistory.put(ts, intArrayOf(steps, cal, dist)) == null) newStepsCount++
        }
        val cur = r[5].toInt() and 0xFF
        val total = r[6].toInt() and 0xFF
        if (cur >= total - 1) requestNextStepsDay()
    }

    private fun handleHrPacket(r: ByteArray) {
        if (!syncing || r.size < 15) return
        scheduleSyncStall()
        when (val sub = r[1].toInt() and 0xFF) {
            0xFF -> requestNextDay()                       // no data / error for this day
            0 -> {
                curSize = r[2].toInt() and 0xFF
                curInterval = (r[3].toInt() and 0xFF).coerceAtLeast(1)
                curCount = 0; curDayIdx = 0
                if (curSize == 0) requestNextDay()
            }
            1 -> {
                curDayStart = le32(r, 2)
                for (i in 6..14) addHr(r[i])
                curCount++
                if (curCount >= curSize - 1) requestNextDay()   // size counts the header packet
            }
            else -> {
                for (i in 2..14) addHr(r[i])
                curCount++
                if (curCount >= curSize - 1) requestNextDay()   // size counts the header packet
            }
        }
    }

    private fun addHr(b: Byte) {
        val bpm = b.toInt() and 0xFF
        val ts = curDayStart + curDayIdx.toLong() * curInterval * 60
        curDayIdx++
        val nowPlus = System.currentTimeMillis() / 1000 + 60
        if (bpm in 1..250 && ts <= nowPlus) {
            if (hrHistory.put(ts, bpm) == null) newSampleCount++
        }
    }

    private fun finishSync() {
        cancelSyncStall()
        if (!syncing) return
        syncing = false
        saveHistory()
        saveSteps()
        setDataStatus(
            "Synced ✓\nHR: ${hrHistory.size} (+$newSampleCount)   ·   " +
                "Steps: ${stepsHistory.size} (+$newStepsCount)\nSaved ring_hr.csv, ring_steps.csv"
        )
        toast("Synced: +$newSampleCount HR, +$newStepsCount step buckets")
    }

    private fun scheduleSyncStall() {
        cancelSyncStall()
        syncTimeout = Runnable { if (syncing) finishSync() }
        handler.postDelayed(syncTimeout!!, SYNC_STALL_MS)
    }

    private fun cancelSyncStall() {
        syncTimeout?.let { handler.removeCallbacks(it) }
        syncTimeout = null
    }

    private fun midnightEpoch(dayOffset: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        return c.timeInMillis / 1000
    }

    private fun hrReadPayload(epoch: Long): ByteArray {
        val ts = epoch.toInt()
        return byteArrayOf(
            CMD_HR_READ.toByte(),
            (ts and 0xFF).toByte(),
            ((ts ushr 8) and 0xFF).toByte(),
            ((ts ushr 16) and 0xFF).toByte(),
            ((ts ushr 24) and 0xFF).toByte()
        )
    }

    private fun le32(r: ByteArray, off: Int): Long =
        (r[off].toLong() and 0xFF) or
            ((r[off + 1].toLong() and 0xFF) shl 8) or
            ((r[off + 2].toLong() and 0xFF) shl 16) or
            ((r[off + 3].toLong() and 0xFF) shl 24)

    private fun u16(r: ByteArray, off: Int): Int =
        (r[off].toInt() and 0xFF) or ((r[off + 1].toInt() and 0xFF) shl 8)

    /** The ring encodes dates as ints-used-as-literal-bytes, e.g. 2026 -> 0x26. */
    private fun bcd(b: Byte): Int = ("%02x".format(b.toInt() and 0xFF)).toInt()

    private fun ymdEpoch(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month0, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000
    }

    // ---- CSV persistence + share ----

    private fun csvFile() = File(filesDir, HR_CSV)

    private fun loadHistory() {
        val f = csvFile()
        if (!f.exists()) return
        f.readLines().drop(1).forEach { line ->
            val parts = line.split(',')
            if (parts.size >= 3) {
                val ep = parts[1].toLongOrNull()
                val bpm = parts[2].toIntOrNull()
                if (ep != null && bpm != null) hrHistory[ep] = bpm
            }
        }
    }

    private fun saveHistory(): File {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val sb = StringBuilder("timestamp,epoch_s,bpm\n")
        for ((ep, bpm) in hrHistory) {
            sb.append(fmt.format(Date(ep * 1000))).append(',').append(ep).append(',').append(bpm).append('\n')
        }
        val f = csvFile()
        f.writeText(sb.toString())
        return f
    }

    private fun stepsFile() = File(filesDir, STEPS_CSV)

    private fun loadSteps() {
        val f = stepsFile()
        if (!f.exists()) return
        f.readLines().drop(1).forEach { line ->
            val p = line.split(',')
            if (p.size >= 5) {
                val ep = p[1].toLongOrNull(); val s = p[2].toIntOrNull()
                val c = p[3].toIntOrNull(); val d = p[4].toIntOrNull()
                if (ep != null && s != null && c != null && d != null) {
                    stepsHistory[ep] = intArrayOf(s, c, d)
                }
            }
        }
    }

    private fun saveSteps(): File {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val sb = StringBuilder("timestamp,epoch_s,steps,calories,distance_m\n")
        for ((ep, a) in stepsHistory) {
            sb.append(fmt.format(Date(ep * 1000))).append(',').append(ep).append(',')
                .append(a[0]).append(',').append(a[1]).append(',').append(a[2]).append('\n')
        }
        val f = stepsFile()
        f.writeText(sb.toString())
        return f
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

    private fun setDataStatus(s: String) = runOnUiThread { b.dataStatus.text = s }

    @SuppressLint("MissingPermission")
    private fun doWrite(pkt: ByteArray, confirm: String?) {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) { setStatus("Not connected"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = pkt
                g.writeCharacteristic(ch)
            }
        }
        if (confirm != null) handler.postDelayed({ setStatus(confirm) }, 350)
    }

    private fun buildSetPacket(min: Int): ByteArray =
        packet(byteArrayOf(0x16, 0x02, 0x01, (min and 0xFF).toByte()))

    private fun buildReadPacket(): ByteArray =
        packet(byteArrayOf(0x16, 0x01))

    private fun packet(head: ByteArray): ByteArray {
        val p = ByteArray(16)
        System.arraycopy(head, 0, p, 0, head.size)
        var sum = 0
        for (i in 0..14) sum += p[i].toInt() and 0xFF
        p[15] = (sum % 255).toByte()
        return p
    }

    // ---- permissions ----

    private fun hasPerm(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQ_PERM
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pending?.let { withRing(it) }
            } else setStatus("Bluetooth permission denied")
        }
    }

    // ---- helpers ----

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        cancelConnectTimeout()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        ready = false
        connecting = false
    }

    private fun setStatus(s: String) = runOnUiThread { b.status.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        closeGatt()
    }
}
