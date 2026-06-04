package com.copperhead.gateway.sip

import android.util.Log
import java.net.InetAddress

/**
 * Represents a single SIP call session.
 * Manages INVITE/ACK/BYE state machine and RTP media.
 */
class SipCall(
    val callId: String,
    val isIncoming: Boolean,
    val localTag: String,
    var remoteTag: String? = null,
    val remoteNumber: String,
    val simSlot: Int = -1
) {
    companion object {
        private const val TAG = "SipCall"
    }

    enum class State {
        IDLE, TRYING, RINGING, PROGRESS, CONNECTED, DISCONNECTING, DISCONNECTED
    }

    var state: State = State.IDLE
        private set
    var cseq: Int = 1
    var localSdp: String? = null
    var remoteSdp: String? = null
    var rtpStream: RtpStream? = null

    // Parsed from remote SDP
    var remoteRtpAddress: InetAddress? = null
    var remoteRtpPort: Int = 0
    var remoteCodec: Int = RtpStream.PAYLOAD_TYPE_PCMU

    // Store the last INVITE message for ACK
    var lastInviteMessage: SipMessage? = null
    // Store Via from incoming request for responses
    var incomingVia: List<String>? = null
    var incomingContact: String? = null
    var fromHeader: String? = null
    var toHeader: String? = null
    var requestUri: String? = null

    var onStateChanged: ((State) -> Unit)? = null

    fun updateState(newState: State) {
        if (state == newState) return
        val old = state
        state = newState
        Log.i(TAG, "Call $callId: $old -> $newState (remote=$remoteNumber)")
        onStateChanged?.invoke(newState)
    }

    fun parseRemoteSdp(sdp: String?) {
        if (sdp.isNullOrBlank()) return
        remoteSdp = sdp

        // Parse c= line for IP
        val connectionRegex = Regex("""c=IN IP4 (\S+)""")
        connectionRegex.find(sdp)?.let {
            remoteRtpAddress = InetAddress.getByName(it.groupValues[1])
        }

        // Parse m=audio line for port and codecs
        val mediaRegex = Regex("""m=audio (\d+) RTP/AVP (.+)""")
        mediaRegex.find(sdp)?.let {
            remoteRtpPort = it.groupValues[1].toInt()
            val codecs = it.groupValues[2].trim().split("\\s+".toRegex())
            // Preference order: G.722 (wideband) → PCMU → PCMA.
            // Pick the FIRST one the remote offers that we support, in our
            // priority. Asterisk will respect this because it intersects.
            remoteCodec = when {
                codecs.contains("9") -> RtpStream.PAYLOAD_TYPE_G722
                codecs.contains("0") -> RtpStream.PAYLOAD_TYPE_PCMU
                codecs.contains("8") -> RtpStream.PAYLOAD_TYPE_PCMA
                else -> codecs.firstOrNull()?.toIntOrNull() ?: RtpStream.PAYLOAD_TYPE_PCMU
            }
        }

        Log.i(TAG, "Parsed remote SDP: ${remoteRtpAddress?.hostAddress}:$remoteRtpPort codec=$remoteCodec")
    }

    fun buildLocalSdp(localIp: String, localRtpPort: Int): String {
        val sdp = buildString {
            appendLine("v=0")
            appendLine("o=copperhead 0 0 IN IP4 $localIp")
            appendLine("s=Copperhead Gateway")
            appendLine("c=IN IP4 $localIp")
            appendLine("t=0 0")
            // Codec priority: 9=G.722 (wideband) → 0=PCMU → 8=PCMA → 101=DTMF.
            // Per RFC 3551 the rtpmap clock rate for G.722 is 8000 (historical
            // quirk — codec samples are 16 kHz but RTP timestamp clock is 8 kHz).
            appendLine("m=audio $localRtpPort RTP/AVP 9 0 8 101")
            appendLine("a=rtpmap:9 G722/8000")
            appendLine("a=rtpmap:0 PCMU/8000")
            appendLine("a=rtpmap:8 PCMA/8000")
            appendLine("a=rtpmap:101 telephone-event/8000")
            appendLine("a=fmtp:101 0-16")
            appendLine("a=ptime:20")
            appendLine("a=sendrecv")
        }
        localSdp = sdp
        return sdp
    }

    fun setupRtp(localPort: Int = 0): RtpStream {
        rtpStream?.close()
        val rtp = RtpStream(localPort)
        rtp.open()
        rtpStream = rtp
        return rtp
    }

    fun connectRtp() {
        val rtp = rtpStream ?: return
        val addr = remoteRtpAddress ?: return
        if (remoteRtpPort <= 0) return
        rtp.setRemote(addr, remoteRtpPort, remoteCodec)
        rtp.startReceiving()
    }

    fun cleanup() {
        rtpStream?.close()
        rtpStream = null
        updateState(State.DISCONNECTED)
    }

    override fun toString(): String = "SipCall(id=$callId, state=$state, remote=$remoteNumber, sim=$simSlot)"
}
