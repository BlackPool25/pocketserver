package com.pocketserver.persistence.boot

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted configuration for PocketServer background and startup behavior.
 */
object PersistencePreferences {

    private const val PREFS_NAME = "pocketserver_persistence_prefs"
    private const val KEY_AUTO_START_ON_BOOT = "key_auto_start_on_boot"
    private const val KEY_VPN_SHIELD_ENABLED = "key_vpn_shield_enabled"
    private const val KEY_WIFI_LOCK_ENABLED = "key_wifi_lock_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        // Direct Boot safe: get device protected storage if user has not unlocked phone yet
        val deviceContext = if (context.isDeviceProtectedStorage) {
            context
        } else {
            context.createDeviceProtectedStorageContext()
        }
        return deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoStartOnBoot(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, false)
    }

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun isVpnShieldEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VPN_SHIELD_ENABLED, true)
    }

    fun setVpnShieldEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VPN_SHIELD_ENABLED, enabled).apply()
    }

    fun isWifiLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WIFI_LOCK_ENABLED, true)
    }

    fun setWifiLockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WIFI_LOCK_ENABLED, enabled).apply()
    }
}
