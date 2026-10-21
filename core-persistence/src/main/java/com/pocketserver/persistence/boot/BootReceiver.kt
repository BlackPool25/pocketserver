package com.pocketserver.persistence.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pocketserver.persistence.host.PocketServerHostService

/**
 * Boot receiver enabling PocketServer to act as a permanent, unattended 24/7 home micro-server.
 *
 * Listens for:
 * - Intent.ACTION_BOOT_COMPLETED (Standard unlock boot)
 * - Intent.ACTION_LOCKED_BOOT_COMPLETED (Direct Boot prior to device passcode entry)
 * - Intent.ACTION_MY_PACKAGE_REPLACED (App update via Play Store or adb)
 *
 * If "Server Mode / Start on Boot" is enabled in settings, PocketServerHostService
 * is started automatically in the background.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PocketServerBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received system broadcast action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val shouldAutoStart = PersistencePreferences.isAutoStartOnBoot(context)
                if (shouldAutoStart) {
                    Log.i(TAG, "Auto-start enabled. Launching PocketServerHostService...")
                    PocketServerHostService.start(context)
                } else {
                    Log.i(TAG, "Auto-start disabled in user preferences. Doing nothing.")
                }
            }
        }
    }
}
