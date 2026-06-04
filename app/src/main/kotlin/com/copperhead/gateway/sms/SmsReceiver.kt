package com.copperhead.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Receives incoming SMS messages and forwards them to the gateway.
 * Detects which SIM slot received the message for dual-SIM routing.
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Determine SIM slot
        val subId = intent.getIntExtra("subscription", -1)
        val simSlot = getSimSlotFromSubId(context, subId)

        // Group message parts by sender (for multi-part SMS)
        val grouped = messages.groupBy { it.originatingAddress ?: "unknown" }

        for ((sender, parts) in grouped) {
            val fullBody = parts.joinToString("") { it.messageBody ?: "" }
            Log.i(TAG, "SMS received from $sender on SIM $simSlot: ${fullBody.take(50)}")

            SmsHandler.instance?.onSmsReceived(sender, fullBody, simSlot)
        }
    }

    private fun getSimSlotFromSubId(context: Context, subId: Int): Int {
        if (subId < 0) return -1
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            val info = manager?.getActiveSubscriptionInfo(subId)
            info?.simSlotIndex ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine SIM slot from subId=$subId", e)
            -1
        }
    }
}
