package com.pocketserver.persistence.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages dual Wi-Fi locks to defeat aggressive mobile baseband DTIM sleep:
 * - HIGH_PERF: Kept active while the device screen is locked to ensure background TCP packets are not dropped.
 * - LOW_LATENCY (API 29+): Dynamically engaged during active connection streaming to minimize round-trip packet jitter.
 */
class WifiLockManager(private val context: Context) {

    enum class LockMode {
        RELEASED,
        HIGH_PERF,
        LOW_LATENCY
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var highPerfLock: WifiManager.WifiLock? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null

    private val _currentMode = MutableStateFlow(LockMode.RELEASED)
    val currentMode: StateFlow<LockMode> = _currentMode.asStateFlow()

    companion object {
        private const val TAG = "WifiLockManager"
        private const val LOCK_TAG_HIGH_PERF = "PocketServer:HighPerfLock"
        private const val LOCK_TAG_LOW_LATENCY = "PocketServer:LowLatencyLock"
    }

    /**
     * Acquires High-Performance lock mode for background screen-off persistence.
     */
    @Synchronized
    fun acquireHighPerf() {
        if (wifiManager == null) {
            Log.w(TAG, "WifiManager unavailable")
            return
        }

        // Release low latency if active
        releaseLowLatencyInternal()

        if (highPerfLock == null) {
            @Suppress("DEPRECATION")
            highPerfLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                LOCK_TAG_HIGH_PERF
            ).apply {
                setReferenceCounted(false)
            }
        }

        highPerfLock?.let {
            if (!it.isHeld) {
                it.acquire()
                Log.i(TAG, "Acquired WIFI_MODE_FULL_HIGH_PERF lock")
            }
        }
        _currentMode.value = LockMode.HIGH_PERF
    }

    /**
     * Acquires Low-Latency lock mode (Android 10+ / API 29) for active request handling.
     */
    @Synchronized
    fun acquireLowLatency() {
        if (wifiManager == null) {
            Log.w(TAG, "WifiManager unavailable")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Release high-perf if active
            releaseHighPerfInternal()

            if (lowLatencyLock == null) {
                lowLatencyLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    LOCK_TAG_LOW_LATENCY
                ).apply {
                    setReferenceCounted(false)
                }
            }

            lowLatencyLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.i(TAG, "Acquired WIFI_MODE_FULL_LOW_LATENCY lock")
                }
            }
            _currentMode.value = LockMode.LOW_LATENCY
        } else {
            // Fallback to high-perf on pre-Q devices
            acquireHighPerf()
        }
    }

    /**
     * Releases all held Wi-Fi locks to return baseband to standard battery saving mode.
     */
    @Synchronized
    fun releaseAll() {
        releaseHighPerfInternal()
        releaseLowLatencyInternal()
        _currentMode.value = LockMode.RELEASED
        Log.i(TAG, "Released all Wi-Fi locks")
    }

    private fun releaseHighPerfInternal() {
        highPerfLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    private fun releaseLowLatencyInternal() {
        lowLatencyLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}
