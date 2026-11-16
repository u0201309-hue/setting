package com.datapack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.datapack.app.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var micGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var batteryExempt by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micGranted) RecordingService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionState()
        if (!micGranted) requestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1E3A5F),
                    secondary = Color(0xFF2E7D32),
                    surfaceVariant = Color(0xFFE6ECF3),
                )
            ) {
                MainScreen(
                    vm = vm,
                    micGranted = micGranted,
                    overlayGranted = overlayGranted,
                    batteryExempt = batteryExempt,
                    onRequestMic = ::requestPermissions,
                    onRequestOverlay = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    },
                    onRequestBattery = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        )
                    },
                    onFixMic = { RecordingService.start(this, restartCapture = true) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        if (micGranted) {
            // Make sure the service is up. If the OS had muted it (started from the
            // background without an exemption), restarting the capture while we are
            // visible gives it microphone access again.
            RecordingService.start(this, restartCapture = ListenerState.micBlocked.value)
        }
        vm.refresh()
        // Re-arm the email worker if it's configured but not currently scheduled
        // (e.g. after the app was force-stopped or updated).
        if (EmailSettings.isEnabled(this)) {
            EmailWorker.scheduleNext(this)
        }
    }

    private fun refreshPermissionState() {
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(this)
        val pm = getSystemService(PowerManager::class.java)
        batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }
}
