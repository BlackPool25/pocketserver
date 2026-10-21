package com.pocketserver.persistence.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Monitors device thermal status and battery power metrics, and manages
 * partial wake-locks to guarantee CPU execution when tasks are running.
 */
class PowerGovernor(private val context: Context) {

    enum class ThermalState {
        NOMINAL,
        THROTTLED,
        CRITICAL
    }

    data class PowerTelemetry(
        val currentMicroAmps: Long,
        val voltageMilliVolts: Int,
        val wattageWatts: Double,
        val isCharging: Boolean,
        val batteryPercentage: Int
    )

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: Any? = null // Stored as Any to prevent classloading issues on < API 29

    private val _thermalState = MutableStateFlow(ThermalState.NOMINAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _isWakeLockHeld = MutableStateFlow(false)
    val isWakeLockHeld: StateFlow<Boolean> = _isWakeLockHeld.asStateFlow()

    private val _telemetry = MutableStateFlow(
        PowerTelemetry(0, 0, 0.0, false, 100)
    )
    val telemetry: StateFlow<PowerTelemetry> = _telemetry.asStateFlow()

    companion object {
        private const val TAG = "PowerGovernor"
        private const val WAKELOCK_TAG = "PocketServer:HostPowerGovernor"
    }

    init {
        registerThermalListener()
    }

    /**
     * Acquires partial wake lock so the CPU remains active while the screen is turned off.
     */
    @Synchronized
    fun acquireWakeLock(timeoutMs: Long = 0) {
        if (powerManager == null) return

        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }

        wakeLock?.let {
            if (!it.isHeld) {
                if (timeoutMs > 0) {
                    it.acquire(timeoutMs)
                } else {
                    it.acquire()
                }
                _isWakeLockHeld.value = true
                Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK")
            }
        }
    }

    /**
     * Releases partial wake lock.
     */
    @Synchronized
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                _isWakeLockHeld.value = false
                Log.i(TAG, "Released PARTIAL_WAKE_LOCK")
            }
        }
    }

    /**
     * Updates and samples real-time instantaneous battery metrics and wattage.
     */
    fun samplePowerTelemetry(): PowerTelemetry {
        val batteryStatusIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val voltageMv = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3800) ?: 3800
        val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val batteryPct = if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100).toInt()
        } else {
            100
        }

        val rawCurrent = if (batteryManager != null) {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } else {
            0L
        }

        // Android BatteryManager returns Long.MIN_VALUE when the hardware fuel gauge is not present
        val currentMicroAmps = if (rawCurrent == Long.MIN_VALUE || rawCurrent == Long.MAX_VALUE) {
            0L
        } else {
            rawCurrent
        }

        // Power (Watts) = (|I_uA| / 1,000,000) * (V_mV / 1,000)
        val currentAmps = abs(currentMicroAmps.toDouble()) / 1_000_000.0
        val voltageVolts = (if (voltageMv > 0) voltageMv else 3800).toDouble() / 1_000.0
        val wattage = currentAmps * voltageVolts

        val result = PowerTelemetry(
            currentMicroAmps = currentMicroAmps,
            voltageMilliVolts = voltageMv,
            wattageWatts = wattage,
            isCharging = isCharging,
            batteryPercentage = batteryPct
        )
        _telemetry.value = result
        return result
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val state = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                    PowerManager.THERMAL_STATUS_LIGHT,
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.THROTTLED
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.NOMINAL
                }
                Log.i(TAG, "Device thermal status changed: $status -> $state")
                _thermalState.value = state
            }
            thermalListener = listener
            try {
                powerManager.addThermalStatusListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to register thermal status listener", e)
            }
        }
    }

    /**
     * Unregisters thermal listeners and releases all held locks.
     */
    fun teardown() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                powerManager.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to remove thermal status listener", e)
            }
        }
    }
}
