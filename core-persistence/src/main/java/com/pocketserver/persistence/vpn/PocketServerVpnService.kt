package com.pocketserver.persistence.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * PocketServerVpnService acts as a local loopback persistence shield.
 *
 * Background:
 * On aggressive OEM Android distributions (Samsung One UI, Xiaomi HyperOS),
 * background services are aggressively killed by battery management daemons
 * (e.g. Samsung FAS, Xiaomi Joyose) when the device screen locks.
 *
 * Android treats active VPN interfaces as critical system networking infrastructure.
 * By binding a lightweight dummy loopback interface (10.245.0.1/32) without establishing
 * a default gateway route (0.0.0.0/0), PocketServer gains complete immunity from
 * OEM task killers without interfering with the user's Wi-Fi or cellular data traffic.
 */
class PocketServerVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "PocketServerVpnService"
        const val ACTION_START_SHIELD = "com.pocketserver.persistence.action.START_SHIELD"
        const val ACTION_STOP_SHIELD = "com.pocketserver.persistence.action.STOP_SHIELD"

        const val CHANNEL_ID = "pocketserver_persistence_shield"
        const val NOTIFICATION_ID = 42001

        const val LOOPBACK_IP = "10.245.0.1"
        const val LOOPBACK_PREFIX = 32
        const val TUNNEL_MTU = 1500

        private val _isShieldActive = MutableStateFlow(false)
        val isShieldActive: StateFlow<Boolean> = _isShieldActive.asStateFlow()

        fun start(context: Context) {
            val prepareIntent = prepare(context)
            if (prepareIntent != null) {
                Log.w(TAG, "VPN service not yet prepared/approved by user. Cannot start until user grants permission.")
                return
            }
            val intent = Intent(context, PocketServerVpnService::class.java).apply {
                action = ACTION_START_SHIELD
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PocketServerVpnService::class.java).apply {
                action = ACTION_STOP_SHIELD
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SHIELD -> {
                Log.i(TAG, "Stopping persistence shield on request")
                tearDownVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val prepareIntent = prepare(this)
                if (prepareIntent != null) {
                    Log.w(TAG, "VPN service not prepared by user; aborting foreground start.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForeground(NOTIFICATION_ID, buildForegroundNotification())
                    establishLoopbackShield()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to engage foreground notification for VPN shield", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
    }

    /**
     * Establishes the dummy loopback tunnel.
     * Note: We intentionally do NOT add 0.0.0.0/0 or ::/0 routes.
     * This ensures the operating system routes ZERO normal internet traffic into this interface.
     */
    private fun establishLoopbackShield() {
        if (vpnInterface != null) {
            Log.d(TAG, "Persistence shield already active")
            _isShieldActive.value = true
            return
        }

        try {
            val builder = Builder()
                .setSession("PocketServer Persistence Shield")
                .setMtu(TUNNEL_MTU)
                .addAddress(LOOPBACK_IP, LOOPBACK_PREFIX)
                .setBlocking(false)

            // Direct only our isolated dummy address into the interface
            builder.addRoute(LOOPBACK_IP, LOOPBACK_PREFIX)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            _isShieldActive.value = (vpnInterface != null)
            Log.i(TAG, "Persistence shield established successfully: fd=${vpnInterface?.fd}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish loopback shield", e)
            _isShieldActive.value = false
        }
    }

    private fun tearDownVpn() {
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing VPN descriptor", e)
        } finally {
            vpnInterface = null
            _isShieldActive.value = false
        }
    }

    override fun onDestroy() {
        tearDownVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by user or system")
        tearDownVpn()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketServer Background Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Guarantees 24/7 background uptime against OEM task killers"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketServer Host Active")
            .setContentText("Loopback persistence shield active • Zero battery impact")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
