package com.copperhead.gateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.copperhead.gateway.util.Preferences

/**
 * Starts the gateway service on boot if auto-start is enabled.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Preferences(context)
        if (!prefs.autoStartEnabled) {
            Log.i(TAG, "Auto-start disabled, not starting gateway")
            return
        }

        Log.i(TAG, "Boot completed, starting gateway service")
        val serviceIntent = Intent(context, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
        }
        context.startForegroundService(serviceIntent)
    }
}
