package com.pocketserver.persistence.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketserver.persistence.boot.PersistencePreferences
import com.pocketserver.persistence.power.PowerGovernor
import com.pocketserver.persistence.vpn.PocketServerVpnService
import com.pocketserver.persistence.wifi.WifiLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Primary host coordinator service for PocketServer.
 * Orchestrates:
 * - The VpnService persistence loopback shield
 * - The Dual Wi-Fi Lock Governor (High-Perf & Low-Latency)
 * - The PowerGovernor (Thermal throttling and Partial WakeLocks)
 * - Memory trimming alerts for sandboxed child processes
 */
class PocketServerHostService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var wifiLockManager: WifiLockManager
    private lateinit var powerGovernor: PowerGovernor

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): PocketServerHostService = this@PocketServerHostService
    }

    companion object {
        private const val TAG = "PocketServerHostService"
        const val CHANNEL_ID = "pocketserver_host_channel"
        const val NOTIFICATION_ID = 42002

        const val ACTION_START = "com.pocketserver.persistence.host.START"
        const val ACTION_STOP = "com.pocketserver.persistence.host.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, PocketServerHostService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PocketServerHostService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiLockManager = WifiLockManager(this)
        powerGovernor = PowerGovernor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping host service on request")
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
                activateHostShields()
                _isRunning.value = true
                return START_STICKY
            }
        }
    }

    private fun activateHostShields() {
        // 1. Acquire Partial WakeLock for CPU scheduling
        powerGovernor.acquireWakeLock()

        // 2. Engage High-Performance Wi-Fi Lock
        if (PersistencePreferences.isWifiLockEnabled(this)) {
            wifiLockManager.acquireHighPerf()
        }

        // 3. Launch VpnService Loopback Persistence Shield
        if (PersistencePreferences.isVpnShieldEnabled(this)) {
            PocketServerVpnService.start(this)
        }

        Log.i(TAG, "All host persistence shields activated successfully")
    }

    private fun shutdown() {
        _isRunning.value = false
        wifiLockManager.releaseAll()
        powerGovernor.teardown()
        PocketServerVpnService.stop(this)
        serviceScope.cancel()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory trim event ($level). Signaling cache drop.")
                // Tell the runtime/sandboxes to drop memory buffers
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.i(TAG, "Low memory trim event ($level).")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    fun getPowerGovernor(): PowerGovernor = powerGovernor
    fun getWifiLockManager(): WifiLockManager = wifiLockManager

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketServer Core Host",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Coordinates local containers, socket activation, and background persistence"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketServer Host Active")
            .setContentText("Local web services running • Socket activation ready")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
