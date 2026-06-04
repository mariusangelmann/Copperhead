package com.copperhead.gateway.gsm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GSM calls with dual SIM support.
 * Bridges between Android Telecom framework and the gateway logic.
 */
class GsmCallManager(private val context: Context) {
    companion object {
        private const val TAG = "GsmCallManager"
        /**
         * Telecom Call extra set when CallBridge takes ownership of a GSM
         * call for bridging. Lives in Telecom's Call object so it survives
         * Copperhead's process death — the next instance reads it in
         * onGsmCallAdded to identify orphan calls from a dead previous
         * instance and end them.
         */
        const val EXTRA_COPPERHEAD_BRIDGED = "com.copperhead.gateway.BRIDGED"
        var instance: GsmCallManager? = null
            private set
    }

    private val activeCalls = ConcurrentHashMap<Call, GsmCallInfo>()
    private val telecomManager = context.getSystemService(TelecomManager::class.java)
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

    // Callbacks
    var onCallReceived: ((GsmCallInfo) -> Unit)? = null
    var onCallStateChanged: ((GsmCallInfo, Int) -> Unit)? = null
    var onCallTerminated: ((GsmCallInfo) -> Unit)? = null

    data class GsmCallInfo(
        val call: Call,
        val number: String,
        val simSlot: Int,
        val isIncoming: Boolean,
        var state: Int = Call.STATE_NEW
    )

    fun start() {
        instance = this
        Log.i(TAG, "GsmCallManager started")
    }

    fun stop() {
        instance = null
        activeCalls.clear()
        Log.i(TAG, "GsmCallManager stopped")
    }

    fun onGsmCallAdded(call: Call, number: String, simSlot: Int, isIncoming: Boolean) {
        // Orphan detection: a call tagged BRIDGED that we haven't started
        // tracking in this instance is a leftover from a previous Copperhead
        // process that died with the cellular leg still up. End it on sight —
        // closes the mic-to-modem path that the guardian may have missed
        // (e.g. if Telecom.endCall raced or failed) and prevents an
        // already-active call from being treated as a new incoming one.
        val wasBridged = try {
            call.details?.extras?.getBoolean(EXTRA_COPPERHEAD_BRIDGED, false) == true
        } catch (_: Throwable) { false }
        if (wasBridged && !activeCalls.containsKey(call)) {
            Log.w(TAG, "Orphan bridged call detected (number=$number state=${call.state}) — disconnecting")
            try { call.disconnect() } catch (t: Throwable) {
                Log.e(TAG, "Failed to disconnect orphan call", t)
            }
            return
        }

        val info = GsmCallInfo(call, number, simSlot, isIncoming, call.state)
        activeCalls[call] = info
        onCallReceived?.invoke(info)
    }

    fun onGsmCallRemoved(call: Call) {
        val info = activeCalls.remove(call) ?: return
        info.state = Call.STATE_DISCONNECTED
        onCallTerminated?.invoke(info)
    }

    fun onGsmCallStateChanged(call: Call, state: Int) {
        val info = activeCalls[call] ?: return
        info.state = state
        onCallStateChanged?.invoke(info, state)
    }

    /**
     * Place an outgoing GSM call on a specific SIM slot.
     */
    fun makeCall(number: String, simSlot: Int = -1) {
        val uri = Uri.fromParts("tel", number, null)
        val extras = Bundle()

        if (simSlot >= 0) {
            val accountHandle = getPhoneAccountForSlot(simSlot)
            if (accountHandle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
            }
        }

        try {
            telecomManager?.placeCall(uri, extras)
            Log.i(TAG, "Placing GSM call to $number on SIM $simSlot")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied to place call", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call", e)
        }
    }

    /**
     * Answer a ringing GSM call.
     */
    fun answerCall(call: Call) {
        try {
            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
            Log.i(TAG, "Answered GSM call")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
        }
    }

    /**
     * Hang up a GSM call.
     */
    fun hangupCall(call: Call) {
        try {
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
            Log.i(TAG, "Hung up GSM call")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hang up call", e)
        }
    }

    /**
     * Get the PhoneAccountHandle for a specific SIM slot.
     */
    private fun getPhoneAccountForSlot(slotIndex: Int): PhoneAccountHandle? {
        try {
            val subInfo = subscriptionManager?.activeSubscriptionInfoList ?: return null
            val sub = subInfo.find { it.simSlotIndex == slotIndex } ?: return null
            val accounts = telecomManager?.callCapablePhoneAccounts ?: return null
            // Match account to subscription
            for (account in accounts) {
                if (account.id.contains(sub.subscriptionId.toString()) ||
                    account.id.contains(sub.iccId ?: "")) {
                    return account
                }
            }
            // Fallback: use slot index directly
            return accounts.getOrNull(slotIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get phone account for slot $slotIndex", e)
            return null
        }
    }

    /**
     * Get available SIM slot count.
     */
    fun getSimCount(): Int {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return tm?.activeModemCount ?: 1
    }

    /**
     * Get info about available SIMs.
     */
    fun getSimInfoList(): List<SimInfo> {
        val result = mutableListOf<SimInfo>()
        try {
            val subs = subscriptionManager?.activeSubscriptionInfoList ?: return result
            for (sub in subs) {
                result.add(SimInfo(
                    slotIndex = sub.simSlotIndex,
                    subscriptionId = sub.subscriptionId,
                    displayName = sub.displayName?.toString() ?: "SIM ${sub.simSlotIndex + 1}",
                    carrierName = sub.carrierName?.toString() ?: "",
                    number = sub.number ?: ""
                ))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied for subscription info", e)
        }
        return result
    }

    data class SimInfo(
        val slotIndex: Int,
        val subscriptionId: Int,
        val displayName: String,
        val carrierName: String,
        val number: String
    )
}
