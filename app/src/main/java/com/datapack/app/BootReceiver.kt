package com.datapack.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Starts listening again after the phone boots, after the app is updated,
 * and when the service's keep-alive alarm fires.
 * Also re-arms the email WorkManager task after a reboot or update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            RecordingService.ACTION_KEEPALIVE -> {
                if (hasMic) try {
                    RecordingService.start(context)
                } catch (e: IllegalStateException) {
                    // Android 12+ may refuse a foreground-service start from the background
                    // (ForegroundServiceStartNotAllowedException). Granting "Display over other
                    // apps" lifts that restriction; otherwise the service resumes when the app opens.
                }
                // Re-arm the email worker (WorkManager state is wiped on reboot)
                if (EmailSettings.isEnabled(context)) {
                    EmailWorker.scheduleNext(context)
                }
            }
        }
    }
}
