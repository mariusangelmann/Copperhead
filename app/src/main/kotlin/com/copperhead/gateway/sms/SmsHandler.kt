package com.copperhead.gateway.sms

import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Handles SMS sending with dual SIM support and bridges to SIP MESSAGE.
 */
class SmsHandler(private val context: Context) {
    companion object {
        private const val TAG = "SmsHandler"
        var instance: SmsHandler? = null
            private set
    }

    // Callback when SMS is received (to forward to SIP)
    var onSmsForSip: ((from: String, body: String, simSlot: Int) -> Unit)? = null

    fun start() {
        instance = this
        Log.i(TAG, "SmsHandler started")
    }

    fun stop() {
        instance = null
        Log.i(TAG, "SmsHandler stopped")
    }

    /**
     * Called by SmsReceiver when an SMS arrives.
     */
    fun onSmsReceived(from: String, body: String, simSlot: Int) {
        Log.i(TAG, "Processing received SMS from $from (SIM $simSlot)")
        onSmsForSip?.invoke(from, body, simSlot)
    }

    /**
     * Send an SMS on a specific SIM slot.
     * Used when Asterisk sends a SIP MESSAGE that should go out as GSM SMS.
     */
    fun sendSms(destination: String, body: String, simSlot: Int = -1) {
        try {
            val smsManager = getSmsManagerForSlot(simSlot)

            if (body.length > 160) {
                val parts = smsManager.divideMessage(body)
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
                Log.i(TAG, "Multi-part SMS sent to $destination on SIM $simSlot (${parts.size} parts)")
            } else {
                smsManager.sendTextMessage(destination, null, body, null, null)
                Log.i(TAG, "SMS sent to $destination on SIM $simSlot")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $destination", e)
        }
    }

    /**
     * Get SmsManager for a specific SIM slot.
     */
    private fun getSmsManagerForSlot(simSlot: Int): SmsManager {
        if (simSlot < 0) return SmsManager.getDefault()

        return try {
            val subManager = context.getSystemService(SubscriptionManager::class.java)
            val subs = subManager?.activeSubscriptionInfoList ?: return SmsManager.getDefault()
            val sub = subs.find { it.simSlotIndex == simSlot }

            if (sub != null) {
                SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get SmsManager for slot $simSlot, using default", e)
            SmsManager.getDefault()
        }
    }
}
