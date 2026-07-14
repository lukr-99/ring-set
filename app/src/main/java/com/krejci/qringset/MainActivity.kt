package com.krejci.qringset

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.krejci.qringset.ui.App
import com.krejci.qringset.ui.RingSetTheme
import com.krejci.qringset.ui.RingViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: RingViewModel by viewModels()
    private var pendingScan = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (pendingScan) {
            pendingScan = false
            if (granted.values.all { it }) vm.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        ensureConnectPermission()
        setContent {
            RingSetTheme {
                App(vm, onExportShare = { exportAndShare() }, onScan = { scan() })
            }
        }
    }

    /** Full-screen: hide the status & navigation bars; a swipe reveals them transiently. */
    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureConnectPermission() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !has(Manifest.permission.BLUETOOTH_CONNECT))
            need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS))
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    private fun scan() {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (need.all { has(it) }) vm.startScan()
        else { pendingScan = true; permLauncher.launch(need) }
    }

    private fun exportAndShare() {
        lifecycleScope.launch {
            vm.exportCsvs()
            shareCsvs()
        }
    }

    private fun shareCsvs() {
        val files = filesDir.listFiles { f -> f.name.endsWith(".csv") || f.name == "ring_export.json" }?.toList().orEmpty()
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ring data"))
    }
}
