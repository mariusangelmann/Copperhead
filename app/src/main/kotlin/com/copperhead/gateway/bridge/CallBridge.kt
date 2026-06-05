package com.copperhead.gateway.bridge

import android.content.Context
import android.os.Bundle
import android.telecom.Call
import android.util.Log
import com.copperhead.gateway.audio.AudioBridge
import com.copperhead.gateway.gsm.GsmCallManager
import com.copperhead.gateway.sip.SipCall
import com.copperhead.gateway.sip.SipEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges GSM calls to/from SIP calls.
 *
 * Flow 1: SIP -> GSM (Asterisk calls a number, gateway dials it on GSM)
 *   1. SipEngine receives INVITE from Asterisk
 *   2. CallBridge extracts destination number
 *   3. GsmCallManager places GSM call on appropriate SIM
 *   4. Audio is bridged between SIP RTP and GSM audio
 *
 * Flow 2: GSM -> SIP (incoming GSM call forwarded to Asterisk)
 *   1. GsmCallService detects incoming call
 *   2. CallBridge makes SIP call to Asterisk
 *   3. Audio is bridged
 */
class CallBridge(
    private val sipEngine: SipEngine,
    private val gsmCallManager: GsmCallManager,
    private val context: Context? = null,
    private val useSpeakerphoneLoopback: Boolean = false,
    /** SIP extension to which inbound GSM calls are routed via INVITE. */
    private val forwardExtension: String = "",
    /** Mute the device mic so only SIP audio reaches the cellular caller. */
    private val muteDeviceMic: Boolean = true
) {
    companion object {
        private const val TAG = "CallBridge"
    }

    // Maps SIP call ID -> BridgedCall
    private val bridges = ConcurrentHashMap<String, BridgedCall>()
    // Maps GSM Call -> SIP call ID
    private val gsmToSip = ConcurrentHashMap<Call, String>()

    var onLog: ((String) -> Unit)? = null

    /**
     * Fired whenever the number of active bridges changes. GatewayService
     * forwards this to the guardian sidecar so it knows whether the main
     * process holds a live cellular leg at the moment of death.
     */
    var onActiveCountChange: ((Int) -> Unit)? = null

    private fun notifyActiveCount() {
        try { onActiveCountChange?.invoke(bridges.size) } catch (_: Throwable) {}
    }

    /**
     * Stamp a Telecom Call object so that if Copperhead dies and a future
     * instance comes up while the call is still active, GsmCallManager
     * recognises it as a previously-bridged orphan and terminates it.
     * Extras live in the Telecom framework, not our process — they survive
     * the bridge being torn down with us.
     */
    private fun tagAsBridged(call: Call) {
        try {
            call.putExtras(Bundle().apply {
                putBoolean(GsmCallManager.EXTRA_COPPERHEAD_BRIDGED, true)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to tag call as bridged", t)
        }
    }

    inner class BridgedCall(
        val sipCall: SipCall,
        var gsmCall: Call? = null,
        var gsmCallInfo: GsmCallManager.GsmCallInfo? = null,
        var hangupSent: Boolean = false,
        var gsmAnswered: Boolean = false,
        var gsmActive: Boolean = false,
        var audioBridgeStarted: Boolean = false,
        val audioBridge: AudioBridge = AudioBridge(context, useSpeakerphoneLoopback, muteDeviceMic).also {
            it.onLog = { msg -> log(msg) }
        }
    )

    private fun hasNegotiatedRtp(call: SipCall): Boolean =
        call.rtpStream != null && call.remoteRtpAddress != null && call.remoteRtpPort > 0

    /**
     * Start the audio bridge once BOTH legs are ready: GSM call ACTIVE and SIP
     * media negotiated. For normal calls that happens at 200 OK. For Follow Me
     * and queue/MOH style dialplans it often happens earlier in a 183 with SDP,
     * and waiting for CONNECTED leaves the cellular caller listening to silence
     * until the PBX answers the INVITE.
     */
    private fun maybeStartAudioBridge(bridge: BridgedCall) {
        if (bridge.audioBridgeStarted) return
        if (!bridge.gsmActive) return
        val hasSipMedia = bridge.sipCall.state == SipCall.State.CONNECTED ||
                (bridge.sipCall.state == SipCall.State.PROGRESS && hasNegotiatedRtp(bridge.sipCall))
        if (!hasSipMedia) return
        val rtp = bridge.sipCall.rtpStream ?: return
        bridge.audioBridge.start(rtp, rtp.audioSampleRate)
        bridge.audioBridgeStarted = true
        val phase = if (bridge.sipCall.state == SipCall.State.PROGRESS) "early media" else "connected"
        log("[BRIDGE] ✓ audio bridge active ($phase, call=${bridge.sipCall.callId.substringBefore('@').take(8)}, rate=${rtp.audioSampleRate}Hz)")
    }

    fun start() {
        // Listen for SIP events
        sipEngine.onIncomingCall = { sipCall -> onSipCallReceived(sipCall) }
        sipEngine.onCallStateChanged = { sipCall -> onSipCallStateChanged(sipCall) }

        // Listen for GSM events
        gsmCallManager.onCallReceived = { info -> onGsmCallReceived(info) }
        gsmCallManager.onCallStateChanged = { info, state -> onGsmCallStateChanged(info, state) }
        gsmCallManager.onCallTerminated = { info -> onGsmCallTerminated(info) }

        Log.i(TAG, "CallBridge started")
    }

    fun stop() {
        // Snapshot the bridges first — hangupCall may trigger callbacks that
        // mutate the bridges map mid-iteration.
        val snapshot = bridges.values.toList()
        for (bridge in snapshot) {
            // Order matters: hang up the cellular leg BEFORE restoring audio
            // state. While the modem still has an active call, closing the
            // mic mute (via audioBridge.stop → restoreAudioManager) would
            // open a brief window where the modem's mic path is live again.
            try {
                bridge.gsmCall?.let { gsmCallManager.hangupCall(it) }
            } catch (t: Throwable) { Log.w(TAG, "stop: gsm hangup failed", t) }
            try {
                sipEngine.hangupCall(bridge.sipCall)
            } catch (t: Throwable) { Log.w(TAG, "stop: sip hangup failed", t) }
            try {
                bridge.audioBridge.stop()
            } catch (t: Throwable) { Log.w(TAG, "stop: audio stop failed", t) }
        }
        bridges.clear()
        gsmToSip.clear()
        notifyActiveCount()
        Log.i(TAG, "CallBridge stopped (${snapshot.size} call(s) torn down)")
    }

    // --- SIP -> GSM ---

    private fun onSipCallReceived(sipCall: SipCall) {
        // Incoming SIP call from Asterisk
        // The "To" number is the GSM destination
        val toUser = sipCall.remoteNumber // Actually this is the From, we need the To destination

        // In gateway mode, Asterisk calls us at a number that represents the GSM destination
        // The request URI contains the actual destination
        val destination = com.copperhead.gateway.sip.extractUserFromUri(sipCall.requestUri)
            ?: sipCall.remoteNumber

        log("[BRIDGE] SIP→GSM: dialing $destination on SIM ${sipCall.simSlot}")

        // Send 180 Ringing to Asterisk while we dial
        sipEngine.ringingCall(sipCall)

        val bridge = BridgedCall(sipCall = sipCall)
        bridges[sipCall.callId] = bridge
        notifyActiveCount()

        // Privacy: mute mic before the outbound GSM dial brings the modem's
        // audio path up. Defence-in-depth — the cellular leg shouldn't have
        // a live mic until SIP is CONNECTED and the bridge is running.
        if (muteDeviceMic) bridge.audioBridge.preMuteMic()

        // Place GSM call
        gsmCallManager.makeCall(destination, sipCall.simSlot)
    }

    private fun onSipCallStateChanged(sipCall: SipCall) {
        val bridge = bridges[sipCall.callId] ?: return

        when (sipCall.state) {
            SipCall.State.PROGRESS -> {
                // 183 Session Progress with SDP: PBX is sending early media
                // such as Follow Me ringback or MOH before final answer.
                maybeStartAudioBridge(bridge)
            }
            SipCall.State.CONNECTED -> {
                // (SIP→GSM only) If we were holding the GSM call open while
                // waiting for SIP to accept, now answer it.
                if (!sipCall.isIncoming && !bridge.gsmAnswered) {
                    val gsmCall = bridge.gsmCall
                    if (gsmCall != null) {
                        bridge.gsmAnswered = true
                        gsmCallManager.answerCall(gsmCall)
                        log("[BRIDGE] SIP connected → answering GSM call")
                    }
                }
                // Both legs might now be up — kick the bridge if so. For
                // GSM→SIP this is the common path: GSM already ACTIVE while
                // SIP just finished negotiating its codec.
                maybeStartAudioBridge(bridge)
            }
            SipCall.State.DISCONNECTED, SipCall.State.DISCONNECTING -> {
                if (!bridge.hangupSent) {
                    bridge.hangupSent = true
                    val gsmCall = bridge.gsmCall
                    if (gsmCall != null) {
                        if (!bridge.gsmAnswered) {
                            // SIP died before we ever answered the cellular
                            // side — reject the inbound call so the caller
                            // hears busy instead of silence-then-drop.
                            gsmCallManager.hangupCall(gsmCall)
                            log("[BRIDGE] SIP failed before connect → rejecting GSM call")
                        } else {
                            gsmCallManager.hangupCall(gsmCall)
                            log("[BRIDGE] SIP hung up → ending GSM call")
                        }
                    }
                    bridge.audioBridge.stop()
                }
                bridges.remove(sipCall.callId)
                bridge.gsmCall?.let { gsmToSip.remove(it) }
                notifyActiveCount()
            }
            else -> {}
        }
    }

    // --- GSM -> SIP ---

    private fun onGsmCallReceived(info: GsmCallManager.GsmCallInfo) {
        if (info.isIncoming) {
            // Check if this is a response to our outgoing SIP->GSM bridge
            val existingBridge = bridges.values.find { it.gsmCall == null && it.sipCall.state != SipCall.State.DISCONNECTED }
            if (existingBridge != null) {
                // This shouldn't happen for incoming, but handle it
                log("[BRIDGE] ⚠ unexpected incoming GSM call during bridge: ${info.number}")
                return
            }

            // New incoming GSM call → forward to Asterisk via SIP.
            if (forwardExtension.isBlank()) {
                log("[BRIDGE] ✗ Cannot forward GSM call from ${info.number}: no forward extension configured. Set 'Forward to extension' in settings.")
                gsmCallManager.hangupCall(info.call)
                return
            }

            log("[BRIDGE] GSM→SIP: ${info.number} → ext $forwardExtension (SIM ${info.simSlot}) — auto-answering")

            // INVITE target = the configured extension (so PBX rings the right
            // place). Original GSM caller's number rides as Caller-ID via
            // P-Asserted-Identity / Remote-Party-ID, NOT as the dial target.
            val sipCall = sipEngine.makeCall(
                destination = forwardExtension,
                simSlot = info.simSlot,
                callerIdNumber = info.number
            )
            val bridge = BridgedCall(sipCall = sipCall, gsmCall = info.call, gsmCallInfo = info)
            bridge.gsmAnswered = true
            bridges[sipCall.callId] = bridge
            gsmToSip[info.call] = sipCall.callId
            notifyActiveCount()

            // Privacy: mute the mic-to-modem path BEFORE answering. The SIP
            // leg takes 100s of ms to seconds to negotiate; without this the
            // caller hears room audio for that whole window.
            if (muteDeviceMic) bridge.audioBridge.preMuteMic()

            // Tag BEFORE answering so that even if we crash mid-answer,
            // the extra is already in the Telecom Call object and the
            // next instance will recognise it as our orphan.
            tagAsBridged(info.call)

            gsmCallManager.answerCall(info.call)
        } else {
            // Outgoing GSM call (initiated by us for SIP->GSM bridge)
            // Find the waiting bridge
            val bridge = bridges.values.find { it.gsmCall == null }
            if (bridge != null) {
                bridge.gsmCall = info.call
                bridge.gsmCallInfo = info
                gsmToSip[info.call] = bridge.sipCall.callId
                tagAsBridged(info.call)
                log("[BRIDGE] outgoing GSM linked to SIP call ${bridge.sipCall.callId.substringBefore('@').take(8)}")
            }
        }
    }

    private fun onGsmCallStateChanged(info: GsmCallManager.GsmCallInfo, state: Int) {
        val sipCallId = gsmToSip[info.call] ?: return
        val bridge = bridges[sipCallId] ?: return

        when (state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                // GSM is dialing, send progress to SIP
                if (bridge.sipCall.isIncoming) {
                    sipEngine.progressCall(bridge.sipCall)
                }
                log("[BRIDGE] GSM dialing…")
            }
            Call.STATE_RINGING -> {
                // GSM ringing (outgoing call)
                if (bridge.sipCall.isIncoming) {
                    sipEngine.ringingCall(bridge.sipCall)
                }
                log("[BRIDGE] GSM ringing…")
            }
            Call.STATE_ACTIVE -> {
                bridge.gsmActive = true
                if (bridge.sipCall.isIncoming) {
                    sipEngine.answerCall(bridge.sipCall)
                }
                // Start audio only when SIP is ALSO connected — codec (and
                // thus the PCM rate) is only known then. Without this, the
                // race causes 16 kHz G.722 packets to be played back as if
                // they were 8 kHz with half the samples discarded.
                maybeStartAudioBridge(bridge)
            }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                onGsmCallTerminated(info)
            }
        }
    }

    private fun onGsmCallTerminated(info: GsmCallManager.GsmCallInfo) {
        val sipCallId = gsmToSip.remove(info.call) ?: return
        val bridge = bridges.remove(sipCallId) ?: return
        notifyActiveCount()

        bridge.audioBridge.stop()

        if (!bridge.hangupSent) {
            bridge.hangupSent = true
            sipEngine.hangupCall(bridge.sipCall)
            log("[BRIDGE] GSM hung up → ending SIP call")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        onLog?.invoke(message)
    }
}
