package com.copperhead.gateway.gsm

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * InCallService that monitors and controls GSM calls.
 * Receives callbacks when calls are added/removed and state changes.
 * Works with the GsmCallManager to bridge calls to SIP.
 */
class GsmCallService : InCallService() {
    companion object {
        private const val TAG = "GsmCallService"
        var instance: GsmCallService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "GsmCallService created")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "GsmCallService destroyed")
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        val details = call.details
        val number = details?.handle?.schemeSpecificPart ?: "unknown"
        val simSlot = getSimSlot(call)
        val isIncoming = details?.callDirection == Call.Details.DIRECTION_INCOMING

        Log.i(TAG, "Call added: number=$number sim=$simSlot incoming=$isIncoming state=${call.state}")

        call.registerCallback(callCallback)
        GsmCallManager.instance?.onGsmCallAdded(call, number, simSlot, isIncoming)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)

        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "Call removed: number=$number")

        GsmCallManager.instance?.onGsmCallRemoved(call)
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
            val stateName = callStateToString(state)
            Log.i(TAG, "Call state changed: number=$number state=$stateName")
            GsmCallManager.instance?.onGsmCallStateChanged(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            // Details update (e.g. caller ID resolution)
        }
    }

    private fun getSimSlot(call: Call): Int {
        return try {
            val details = call.details
            val extras = details?.extras
            val accountHandle = details?.accountHandle
            val subId = extras?.getInt("android.telecom.extra.SUBSCRIPTION_ID", -1) ?: -1
            if (subId >= 0) {
                // Map subscription ID to slot index
                val manager = getSystemService(android.telephony.SubscriptionManager::class.java)
                val info = manager?.getActiveSubscriptionInfo(subId)
                info?.simSlotIndex ?: -1
            } else {
                // Try to extract from phone account handle
                accountHandle?.id?.toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine SIM slot", e)
            -1
        }
    }

    private fun callStateToString(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_PULLING_CALL -> "PULLING_CALL"
        else -> "UNKNOWN($state)"
    }
}
