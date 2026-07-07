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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.krejci.qringset.databinding.ActivityMainBinding
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var connecting = false
    private var ready = false
    private var pending: (() -> Unit)? = null

    companion object {
        // Set via RING_MAC env var or ring.mac in local.properties at build time.
        private val MAC = BuildConfig.RING_MAC
        private val SVC: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CMD_HR_LOG = 0x16
        private const val REQ_PERM = 42
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

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

        b.footer.text = "Ring ${BuildConfig.RING_MAC}\nWake the ring & close the QRing app before setting."
    }

    // ---- public actions ----

    private fun setInterval(min: Int) {
        setStatus("Setting $min min…")
        withRing { doWrite(buildSetPacket(min), confirm = "✓ Interval set to $min min") }
    }

    private fun readInterval() {
        setStatus("Reading…")
        withRing { doWrite(buildReadPacket(), confirm = null) }
    }

    // ---- connection orchestration ----

    private fun withRing(action: () -> Unit) {
        pending = action
        if (!hasPerm()) { askPerm(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
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
        handler.postDelayed({
            if (connecting && !ready) {
                connecting = false
                closeGatt()
                setStatus("Couldn't reach the ring.\nWake it (off charger / move it) and close the QRing app, then tap again.")
            }
        }, CONNECT_TIMEOUT_MS)
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
                    setStatus("Disconnected")
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
            // Enable notifications so we can read the interval back.
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(CCCD)
            if (cccd == null) {
                // No CCCD: writes still work, reads won't. Mark ready anyway.
                ready = true
                connecting = false
                setStatus("Connected ✓")
                runPending()
                return
            }
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
            ready = true
            connecting = false
            setStatus("Connected ✓")
            runPending()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleResponse(value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleResponse(ch.value ?: return)
        }
    }

    private fun handleResponse(r: ByteArray) {
        if (r.size < 4 || (r[0].toInt() and 0xFF) != CMD_HR_LOG) return
        val enabled = r[2].toInt() and 0xFF
        val interval = r[3].toInt() and 0xFF
        val state = if (enabled == 1) "ON" else "OFF"
        setStatus("Ring is set to $interval min  ($state)")
    }

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
        // Write-without-response doesn't fire a callback; confirm a set optimistically.
        // A read has confirm == null and is answered via onCharacteristicChanged instead.
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
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        ready = false
    }

    private fun setStatus(s: String) = runOnUiThread { b.status.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        closeGatt()
    }
}
