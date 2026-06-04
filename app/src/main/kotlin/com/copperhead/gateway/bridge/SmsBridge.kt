package com.copperhead.gateway.bridge

import android.util.Log
import com.copperhead.gateway.sip.SipEngine
import com.copperhead.gateway.sms.SmsHandler

/**
 * Bridges SMS messages between GSM and SIP MESSAGE.
 *
 * GSM -> SIP: Incoming SMS forwarded as SIP MESSAGE to Asterisk
 * SIP -> GSM: Incoming SIP MESSAGE sent as SMS via GSM
 *
 * SIP MESSAGE format:
 *   From: sip:<sender>@domain
 *   To: sip:<asterisk_extension>@domain
 *   Body: SMS text content
 *   X-SIM-Slot: 0 or 1 (for dual SIM routing)
 *   X-SMS-From: original sender number (for GSM->SIP)
 */
class SmsBridge(
    private val sipEngine: SipEngine,
    private val smsHandler: SmsHandler,
    /** SIP extension to route inbound GSM SMS to. */
    private val forwardExtension: String = ""
) {
    companion object {
        private const val TAG = "SmsBridge"
    }

    var onLog: ((String) -> Unit)? = null

    fun start() {
        // GSM SMS -> SIP MESSAGE
        smsHandler.onSmsForSip = { from, body, simSlot ->
            onGsmSmsReceived(from, body, simSlot)
        }

        // SIP MESSAGE -> GSM SMS
        sipEngine.onIncomingMessage = { from, to, body ->
            onSipMessageReceived(from, to, body)
        }

        Log.i(TAG, "SmsBridge started")
    }

    fun stop() {
        smsHandler.onSmsForSip = null
        sipEngine.onIncomingMessage = null
        Log.i(TAG, "SmsBridge stopped")
    }

    /**
     * Forward incoming GSM SMS to Asterisk as SIP MESSAGE.
     * The SMS sender number is included in the message so Asterisk can route it.
     */
    private fun onGsmSmsReceived(from: String, body: String, simSlot: Int) {
        if (forwardExtension.isBlank()) {
            log("[BRIDGE] ✗ Cannot forward SMS from $from: no forward extension configured.")
            return
        }

        // MESSAGE target = configured extension. Original sender's number
        // rides as Caller-ID via From display + PAI/RPID.
        sipEngine.sendMessage(
            destination = forwardExtension,
            body = body,
            simSlot = simSlot,
            senderNumber = from
        )

        log("[BRIDGE] SMS→SIP: $from → ext $forwardExtension (SIM $simSlot) \"${body.take(60)}${if (body.length > 60) "…" else ""}\"")
    }

    /**
     * Forward incoming SIP MESSAGE to GSM as SMS.
     * The SIP MESSAGE To header contains the GSM destination number.
     */
    private fun onSipMessageReceived(from: String, to: String, body: String) {
        // The 'to' field should contain the phone number to send SMS to
        // Parse the destination number from the SIP URI
        val destination = to.replace(Regex("[^0-9+]"), "")

        if (destination.isEmpty()) {
            log("[BRIDGE] ✗ SIP→SMS: no valid destination in To: $to")
            return
        }

        // Determine SIM slot from the SIP account configuration
        // For now, use default SIM. Could be configured per SIP account.
        val simSlot = -1 // Default SIM

        smsHandler.sendSms(destination, body, simSlot)
        log("[BRIDGE] SIP→SMS: to=$destination \"${body.take(60)}${if (body.length > 60) "…" else ""}\"")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        onLog?.invoke(message)
    }
}
