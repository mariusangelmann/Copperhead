package com.copperhead.gateway.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Ensures the gateway service survives battery optimization.
 * Uses root where available, falls back to standard APIs.
 * All operations are best-effort -- the app works without any of these.
 */
object BatteryOptimizer {
    private const val TAG = "BatteryOptimizer"
    private const val PKG = "com.copperhead.gateway"

    /** Check if battery optimization is already disabled for us. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(PKG)
    }

    /** Intent to open the system battery optimization settings for this app. */
    fun getBatterySettingsIntent(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$PKG")
        }
    }

    /**
     * Use root to aggressively whitelist the app against all battery killers.
     * Returns true if at least one command succeeded.
     */
    fun whitelistWithRoot(): Boolean {
        val commands = mutableListOf(
            // Standard Android Doze whitelist
            "dumpsys deviceidle whitelist +$PKG",
            // Disable app standby
            "am set-standby-bucket $PKG active",
            // Disable battery optimization via settings database
            "cmd appops set $PKG RUN_IN_BACKGROUND allow",
            "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow",
            // Allow InCallService monitoring (Android 12+)
            "cmd appops set $PKG MANAGE_ONGOING_CALLS allow",
        )

        // Manufacturer-specific: disable autostart restrictions
        // Xiaomi
        commands.add("am startservice -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity 2>/dev/null || true")
        // Samsung
        commands.add("cmd appops set $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null || true")
        // Huawei
        commands.add("cmd appops set $PKG POWER_SAVE_WHITELIST allow 2>/dev/null || true")

        var anySuccess = false
        for (cmd in commands) {
            if (runAsRoot(cmd)) anySuccess = true
        }

        if (anySuccess) {
            Log.i(TAG, "Root battery whitelist applied")
        } else {
            Log.w(TAG, "Root battery whitelist failed -- no root or commands rejected")
        }
        return anySuccess
    }

    /**
     * Use root to prevent the system from killing our process.
     * Sets the oom_adj to keep the process alive.
     */
    fun protectProcess(): Boolean {
        val pid = android.os.Process.myPid()
        return runAsRoot("echo -17 > /proc/$pid/oom_adj 2>/dev/null || echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null")
    }

    /** Check if root (su) is available. */
    fun isRootAvailable(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exit = proc.waitFor()
            proc.destroy()
            exit == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runAsRoot(command: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exit = proc.waitFor()
            proc.destroy()
            exit == 0
        } catch (e: Exception) {
            Log.d(TAG, "Root command failed: $command", e)
            false
        }
    }

    /** Get manufacturer name for OEM-specific guidance. */
    fun getOemBatteryKillerName(): String? {
        return when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> "MIUI Battery Saver"
            "samsung" -> "Device Care"
            "huawei", "honor" -> "Power Manager"
            "oppo", "realme" -> "Battery Optimization"
            "vivo" -> "iManager"
            "oneplus" -> "Battery Optimization"
            "asus" -> "Auto-start Manager"
            else -> null
        }
    }
}
