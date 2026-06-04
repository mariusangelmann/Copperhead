package com.copperhead.gateway.sip

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core SIP protocol engine.
 * Handles UDP transport, registration, call signaling, and MESSAGE for SMS.
 * Designed for direct integration with Asterisk PBX.
 */
class SipEngine {
    companion object {
        private const val TAG = "SipEngine"
        private const val SIP_MAX_PACKET = 65535
        private const val REGISTER_RETRY_MS = 30_000L
        // Dump full SIP messages to the live log. Flip to false to silence.
        const val WIRE_TRACE = true
    }

    private var socket: DatagramSocket? = null
    private var config: SipConfig = SipConfig.EMPTY
    private val running = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)
    private var registerCseq = 1
    var localIp: String = "0.0.0.0"
        private set
    var localPort: Int = 0
        private set
    // Tracks whether ANY SIP packet has been received from the server since the
    // last REGISTER was sent. Used by the no-response watchdog.
    private val sawResponseSinceRegister = AtomicBoolean(false)
    private var registerWatchdog: ScheduledFuture<*>? = null

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sip-engine").apply { isDaemon = true }
    }
    private var receiveThread: Thread? = null
    private val calls = ConcurrentHashMap<String, SipCall>()
    private var registrationFuture: ScheduledFuture<*>? = null

    // RTP port allocator
    private var nextRtpPort = 10000

    // Callbacks
    var onRegistrationChanged: ((Boolean) -> Unit)? = null
    var onIncomingCall: ((SipCall) -> Unit)? = null
    var onCallStateChanged: ((SipCall) -> Unit)? = null
    var onIncomingMessage: ((from: String, to: String, body: String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    val isRegistered: Boolean get() = registered.get()
    val activeCalls: Map<String, SipCall> get() = calls.toMap()

    fun start(sipConfig: SipConfig) {
        config = sipConfig
        localIp = getLocalIpAddress()
        nextRtpPort = config.rtpPortBase

        socket = DatagramSocket(config.localPort).apply {
            soTimeout = 100
            receiveBufferSize = SIP_MAX_PACKET
        }
        localPort = socket!!.localPort

        running.set(true)
        log("[NET] Engine started on $localIp:$localPort")
        log("[NET] Account: ${config.username}@${config.domain}:${config.port}/${config.transport}")
        if (!config.outboundProxy.isNullOrBlank()) {
            log("[NET] Outbound proxy: ${config.outboundProxy}")
        }
        log("[NET] Reg expiry: ${config.registrationExpiry}s (refresh every ${config.registrationExpiry - 60}s)")

        // Start receive loop on dedicated thread (executor is for sending/handling)
        receiveThread = Thread({ receiveLoop() }, "sip-receive").apply {
            isDaemon = true
            start()
        }

        // Start registration (on background thread to avoid NetworkOnMainThread)
        executor.execute { register(initial = true) }

        // Schedule periodic re-registration
        registrationFuture = executor.scheduleAtFixedRate(
            { if (running.get()) register(initial = false) },
            config.registrationExpiry.toLong() - 60,
            config.registrationExpiry.toLong() - 60,
            TimeUnit.SECONDS
        )
    }

    fun stop() {
        running.set(false)
        registrationFuture?.cancel(false)
        registrationFuture = null
        registerWatchdog?.cancel(false)
        registerWatchdog = null

        // Unregister (best-effort, on current thread since we're shutting down)
        try {
            val s = socket
            if (s != null && !s.isClosed) unregister()
        } catch (_: Exception) {}

        // Cleanup calls
        calls.values.forEach { it.cleanup() }
        calls.clear()

        socket?.close()
        socket = null
        receiveThread?.interrupt()
        receiveThread = null
        registered.set(false)

        log("[NET] Engine stopped")
    }

    // --- Registration ---

    private fun register(initial: Boolean = false) {
        val cseq = registerCseq++
        val msg = SipMessage.Builder()
            .request("REGISTER", config.registrarUri)
            .via(localIp, localPort)
            .from(config.displayName, config.sipUri)
            .to(config.displayName, config.sipUri)
            .callId("reg-${config.username}@copperhead")
            .cseq(cseq, "REGISTER")
            .contact("sip:${config.username}@$localIp:$localPort")
            .expires(config.registrationExpiry)
            .maxForwards()
            .userAgent()
            .allow()
            .build()

        send(msg)

        val proxy = config.outboundProxy
        val targetHost: String
        val targetPort: Int
        if (!proxy.isNullOrBlank()) {
            val parts = proxy.split(":")
            targetHost = parts[0]
            targetPort = parts.getOrNull(1)?.toIntOrNull() ?: config.port
        } else {
            targetHost = config.domain
            targetPort = config.port
        }
        val resolvedIp = try { InetAddress.getByName(targetHost).hostAddress ?: "?" } catch (_: Exception) { "DNS failed" }
        val phase = if (initial) "REGISTER (initial)" else "REGISTER (refresh)"
        log("[REG] → $phase $targetHost:$targetPort [$resolvedIp] cseq=$cseq expires=${config.registrationExpiry}s")

        // Arm the no-response watchdog. The send() path will log incoming packets
        // and set sawResponseSinceRegister=true; if nothing arrives, we surface a
        // detailed hint instead of leaving the user staring at silence.
        sawResponseSinceRegister.set(false)
        registerWatchdog?.cancel(false)
        registerWatchdog = executor.schedule({
            if (running.get() && !sawResponseSinceRegister.get()) {
                log("[REG] ⚠ No response from $targetHost:$targetPort after 5s")
                log("[REG]   Checklist:")
                log("[REG]   • Is $targetHost:$targetPort/UDP reachable from this device?")
                log("[REG]   • FreePBX 'Responsive Firewall' may be dropping packets — whitelist this IP")
                log("[REG]   • If FreePBX uses chan_pjsip, default UDP port is often 5060 (chan_sip uses 5160)")
                log("[REG]   • Verify the SIP transport is UDP (this client does NOT support TCP/TLS)")
                log("[REG]   • Confirm the extension exists and 'NAT' / 'Permit' settings allow this client")
            }
        }, 5, TimeUnit.SECONDS)
    }

    private fun unregister() {
        val msg = SipMessage.Builder()
            .request("REGISTER", config.registrarUri)
            .via(localIp, localPort)
            .from(config.displayName, config.sipUri)
            .to(config.displayName, config.sipUri)
            .callId("reg-${config.username}@copperhead")
            .cseq(registerCseq++, "REGISTER")
            .contact("sip:${config.username}@$localIp:$localPort")
            .expires(0)
            .maxForwards()
            .userAgent()
            .build()

        send(msg)
        log("[REG] → un-REGISTER ${config.domain} (expires=0)")
    }

    private fun handleRegisterResponse(response: SipMessage) {
        when (response.statusCode) {
            200 -> {
                val wasRegistered = registered.getAndSet(true)
                val grantedExpires = response.getHeader("Contact")
                    ?.let { Regex("""expires\s*=\s*(\d+)""").find(it)?.groupValues?.get(1) }
                    ?: response.getHeader("Expires")
                    ?: "${config.registrationExpiry}"
                if (wasRegistered) {
                    log("[REG] ← 200 OK refreshed (expires ${grantedExpires}s)")
                } else {
                    log("[REG] ✓ Registered as ${config.username}@${config.domain} (expires ${grantedExpires}s)")
                }
                onRegistrationChanged?.invoke(true)
            }
            401, 407 -> {
                val challengeHeader = response.getHeader("WWW-Authenticate")
                    ?: response.getHeader("Proxy-Authenticate")
                if (challengeHeader != null) {
                    val challenge = SipAuth.parseChallenge(challengeHeader)
                    log("[REG] ← ${response.statusCode} ${response.reasonPhrase} (realm=\"${challenge.realm}\", algo=${challenge.algorithm}${if (challenge.qop != null) ", qop=${challenge.qop}" else ""})")
                    val authResponse = SipAuth.buildResponse(
                        challenge, config.username, config.password,
                        "REGISTER", config.registrarUri
                    )
                    val cseq = registerCseq++
                    val msg = SipMessage.Builder()
                        .request("REGISTER", config.registrarUri)
                        .via(localIp, localPort)
                        .from(config.displayName, config.sipUri)
                        .to(config.displayName, config.sipUri)
                        .callId("reg-${config.username}@copperhead")
                        .cseq(cseq, "REGISTER")
                        .contact("sip:${config.username}@$localIp:$localPort")
                        .expires(config.registrationExpiry)
                        .maxForwards()
                        .userAgent()
                        .allow()
                        .authorization(authResponse)
                        .build()
                    send(msg)
                    log("[REG] → REGISTER (authenticated) ${config.domain} cseq=$cseq")
                } else {
                    log("[REG] ⚠ ${response.statusCode} challenge missing WWW-Authenticate/Proxy-Authenticate")
                }
            }
            403 -> {
                registered.set(false)
                log("[REG] ✗ 403 Forbidden — check username/password/domain")
                onRegistrationChanged?.invoke(false)
            }
            404 -> {
                registered.set(false)
                log("[REG] ✗ 404 Not Found — account does not exist on ${config.domain}")
                onRegistrationChanged?.invoke(false)
            }
            408 -> {
                registered.set(false)
                log("[REG] ✗ 408 Request Timeout — server not responding")
                onRegistrationChanged?.invoke(false)
            }
            else -> {
                registered.set(false)
                log("[REG] ✗ ${response.statusCode} ${response.reasonPhrase}")
                onRegistrationChanged?.invoke(false)
            }
        }
    }

    // --- Outgoing calls ---

    /**
     * Place an outbound INVITE.
     *
     * @param destination  SIP user/URI being called — goes into the Request-URI
     *                     and To header. For a registered extension forwarding
     *                     into a PBX this is the **target extension** (e.g.
     *                     "101"), NOT the original caller's number.
     * @param simSlot      Which SIM is this call associated with (for bridging).
     * @param callerIdNumber Optional original caller's number (e.g. the GSM
     *                     caller when forwarding inbound cellular). When set,
     *                     it's advertised via the From display name and
     *                     P-Asserted-Identity / Remote-Party-ID so the PBX can
     *                     present it as caller-ID — but the From user stays as
     *                     the gateway extension so PBX auth doesn't break.
     * @param callerIdName Optional human-readable name for the caller-ID.
     */
    fun makeCall(
        destination: String,
        simSlot: Int = -1,
        callerIdNumber: String? = null,
        callerIdName: String? = null
    ): SipCall {
        val callId = SipMessage.newCallId()
        val localTag = SipMessage.newTag()
        val destUri = if (destination.contains("@")) "sip:$destination" else "sip:$destination@${config.domain}"

        val call = SipCall(
            callId = callId,
            isIncoming = false,
            localTag = localTag,
            remoteNumber = destination,
            simSlot = simSlot
        )

        // Setup RTP
        val rtp = call.setupRtp(allocateRtpPort())
        val sdp = call.buildLocalSdp(localIp, rtp.actualLocalPort)

        // From header: user stays as the registered gateway extension (so the
        // PBX accepts the call as authenticated). Display name advertises the
        // forwarded caller-ID when provided.
        val fromDisplay = callerIdName ?: callerIdNumber ?: config.displayName

        call.requestUri = destUri
        call.fromHeader = "\"$fromDisplay\" <${config.sipUri}>;tag=$localTag"
        call.toHeader = "<$destUri>"

        val builder = SipMessage.Builder()
            .request("INVITE", destUri)
            .via(localIp, localPort)
            .from(fromDisplay, config.sipUri, localTag)
            .to(null, destUri)
            .callId(callId)
            .cseq(call.cseq++, "INVITE")
            .contact("sip:${config.username}@$localIp:$localPort")
            .maxForwards()
            .userAgent()
            .allow()
            .contentType("application/sdp")
            .body(sdp)

        // RFC 3325 / Cisco RPID — proper caller-ID transport across a trusted
        // trunk. Without these headers FreePBX would see only the gateway's
        // own extension as the caller and the original GSM number would be
        // lost in the PBX call log / CDR.
        if (!callerIdNumber.isNullOrBlank()) {
            val paiUri = "sip:$callerIdNumber@${config.domain}"
            builder.pAssertedIdentity(paiUri, callerIdName ?: callerIdNumber)
            builder.remotePartyId(paiUri, callerIdName ?: callerIdNumber)
        }

        val msg = builder.build()
        call.lastInviteMessage = msg
        call.updateState(SipCall.State.TRYING)
        calls[callId] = call
        send(msg)

        val cidInfo = if (!callerIdNumber.isNullOrBlank()) " (CID=$callerIdNumber)" else ""
        log("[CALL] → INVITE $destination$cidInfo (call=${shortId(callId)})")
        onCallStateChanged?.invoke(call)
        return call
    }

    fun answerCall(call: SipCall) {
        if (!call.isIncoming) return

        val rtp = call.rtpStream ?: call.setupRtp(allocateRtpPort())
        val sdp = call.buildLocalSdp(localIp, rtp.actualLocalPort)

        val msg = SipMessage.Builder()
            .response(200, "OK")
            .apply {
                call.incomingVia?.forEach { addHeader("Via", it) }
            }
            .header("From", call.fromHeader ?: "")
            .header("To", "${call.toHeader ?: ""};tag=${call.localTag}")
            .callId(call.callId)
            .cseq(1, "INVITE")
            .contact("sip:${config.username}@$localIp:$localPort")
            .userAgent()
            .allow()
            .contentType("application/sdp")
            .body(sdp)
            .build()

        send(msg)
        call.updateState(SipCall.State.CONNECTED)
        call.connectRtp()

        log("[CALL] → 200 OK (call=${shortId(call.callId)}) — RTP connected")
        onCallStateChanged?.invoke(call)
    }

    fun ringingCall(call: SipCall) {
        if (!call.isIncoming) return

        val msg = SipMessage.Builder()
            .response(180, "Ringing")
            .apply {
                call.incomingVia?.forEach { addHeader("Via", it) }
            }
            .header("From", call.fromHeader ?: "")
            .header("To", "${call.toHeader ?: ""};tag=${call.localTag}")
            .callId(call.callId)
            .cseq(1, "INVITE")
            .contact("sip:${config.username}@$localIp:$localPort")
            .userAgent()
            .build()

        send(msg)
        call.updateState(SipCall.State.RINGING)
        log("[CALL] → 180 Ringing (call=${shortId(call.callId)})")
        onCallStateChanged?.invoke(call)
    }

    fun progressCall(call: SipCall) {
        if (!call.isIncoming) return

        // 183 Session Progress with SDP for early media
        val rtp = call.rtpStream ?: call.setupRtp(allocateRtpPort())
        val sdp = call.buildLocalSdp(localIp, rtp.actualLocalPort)

        val msg = SipMessage.Builder()
            .response(183, "Session Progress")
            .apply {
                call.incomingVia?.forEach { addHeader("Via", it) }
            }
            .header("From", call.fromHeader ?: "")
            .header("To", "${call.toHeader ?: ""};tag=${call.localTag}")
            .callId(call.callId)
            .cseq(1, "INVITE")
            .contact("sip:${config.username}@$localIp:$localPort")
            .userAgent()
            .contentType("application/sdp")
            .body(sdp)
            .build()

        send(msg)
        call.updateState(SipCall.State.PROGRESS)
        call.connectRtp()
        log("[CALL] → 183 Session Progress (call=${shortId(call.callId)}) — early media")
        onCallStateChanged?.invoke(call)
    }

    fun hangupCall(call: SipCall) {
        call.updateState(SipCall.State.DISCONNECTING)

        val msg = SipMessage.Builder()
            .request("BYE", call.requestUri ?: "sip:${call.remoteNumber}@${config.domain}")
            .via(localIp, localPort)
            .header("From", call.fromHeader ?: "\"${config.displayName}\" <${config.sipUri}>;tag=${call.localTag}")
            .header("To", call.toHeader ?: "<sip:${call.remoteNumber}@${config.domain}>")
            .callId(call.callId)
            .cseq(call.cseq++, "BYE")
            .maxForwards()
            .userAgent()
            .build()

        send(msg)
        log("[CALL] → BYE (call=${shortId(call.callId)})")

        call.cleanup()
        calls.remove(call.callId)
        onCallStateChanged?.invoke(call)
    }

    fun declineCall(call: SipCall) {
        if (!call.isIncoming) return

        val msg = SipMessage.Builder()
            .response(486, "Busy Here")
            .apply {
                call.incomingVia?.forEach { addHeader("Via", it) }
            }
            .header("From", call.fromHeader ?: "")
            .header("To", "${call.toHeader ?: ""};tag=${call.localTag}")
            .callId(call.callId)
            .cseq(1, "INVITE")
            .userAgent()
            .build()

        send(msg)
        log("[CALL] → 486 Busy Here (call=${shortId(call.callId)})")
        call.cleanup()
        calls.remove(call.callId)
        onCallStateChanged?.invoke(call)
    }

    // --- SIP MESSAGE (for SMS) ---

    /**
     * Send a SIP MESSAGE.
     *
     * @param destination Target SIP user (e.g. the forward extension).
     * @param body Message text.
     * @param simSlot Sending SIM (informational, sent as X-SIM-Slot).
     * @param senderNumber Optional original sender (e.g. inbound SMS sender)
     *                     to advertise via From display name + PAI/RPID.
     */
    fun sendMessage(
        destination: String,
        body: String,
        simSlot: Int = -1,
        senderNumber: String? = null
    ) {
        val destUri = if (destination.contains("@")) "sip:$destination" else "sip:$destination@${config.domain}"
        val fromDisplay = senderNumber ?: config.displayName

        val builder = SipMessage.Builder()
            .request("MESSAGE", destUri)
            .via(localIp, localPort)
            .from(fromDisplay, config.sipUri)
            .to(null, destUri)
            .callId(SipMessage.newCallId())
            .cseq(1, "MESSAGE")
            .maxForwards()
            .userAgent()
            .contentType("text/plain")
            .body(body)

        if (!senderNumber.isNullOrBlank()) {
            val paiUri = "sip:$senderNumber@${config.domain}"
            builder.pAssertedIdentity(paiUri, senderNumber)
            builder.remotePartyId(paiUri, senderNumber)
        }

        val msg = builder.build()
        if (simSlot >= 0) msg.setHeader("X-SIM-Slot", simSlot.toString())

        send(msg)
        val cidInfo = if (!senderNumber.isNullOrBlank()) " (CID=$senderNumber)" else ""
        log("[SMS] → MESSAGE $destination$cidInfo${if (simSlot >= 0) " (SIM $simSlot)" else ""}: \"${body.take(60)}${if (body.length > 60) "…" else ""}\"")
    }

    fun getCall(callId: String): SipCall? = calls[callId]

    // --- Receive loop ---

    private fun receiveLoop() {
        val buf = ByteArray(SIP_MAX_PACKET)
        val packet = DatagramPacket(buf, buf.size)

        while (running.get()) {
            try {
                // Reset the packet's effective length each iteration — after
                // a successful receive(), packet.length is set to bytes-received,
                // which would silently truncate subsequent (larger) packets.
                packet.setData(buf, 0, buf.size)
                socket?.receive(packet) ?: continue
                sawResponseSinceRegister.set(true)
                val data = String(buf, 0, packet.length, Charsets.UTF_8)
                val src = "${packet.address.hostAddress}:${packet.port}"
                val firstLine = data.lineSequence().firstOrNull()?.trim() ?: ""
                log("[NET] ← ${packet.length}B from $src — $firstLine")
                if (WIRE_TRACE) log("[SIP-RX]\n${data.trimEnd()}\n[/SIP-RX]")
                val msg = try {
                    SipMessage.parse(data)
                } catch (e: Exception) {
                    log("[NET] ⚠ Failed to parse SIP from $src: ${e.message}")
                    continue
                }

                executor.execute { handleMessage(msg, packet.address, packet.port) }
            } catch (_: java.net.SocketTimeoutException) {
                // Normal
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Receive error", e)
                    log("[NET] ⚠ Receive error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun handleMessage(msg: SipMessage, fromAddr: InetAddress, fromPort: Int) {
        if (msg.isRequest) {
            handleRequest(msg, fromAddr, fromPort)
        } else {
            handleResponse(msg)
        }
    }

    private fun handleRequest(msg: SipMessage, fromAddr: InetAddress, fromPort: Int) {
        when (msg.method) {
            "INVITE" -> handleIncomingInvite(msg)
            "ACK" -> handleAck(msg)
            "BYE" -> handleBye(msg)
            "CANCEL" -> handleCancel(msg)
            "MESSAGE" -> handleIncomingMessage(msg)
            "OPTIONS" -> handleOptions(msg)
            else -> {
                // Send 405 Method Not Allowed
                val response = SipMessage.Builder()
                    .response(405, "Method Not Allowed")
                    .apply { msg.via.forEach { addHeader("Via", it) } }
                    .header("From", msg.from ?: "")
                    .header("To", msg.to ?: "")
                    .callId(msg.callId ?: "")
                    .header("CSeq", msg.cseq ?: "")
                    .allow()
                    .userAgent()
                    .build()
                send(response)
            }
        }
    }

    private fun handleIncomingInvite(msg: SipMessage) {
        val callId = msg.callId ?: return

        // Check if this is a re-INVITE for an existing call
        val existing = calls[callId]
        if (existing != null) {
            // Re-INVITE: update SDP
            existing.parseRemoteSdp(msg.body)
            answerCall(existing)
            return
        }

        val fromUser = extractUserFromUri(extractUri(msg.from))
        val toUser = extractUserFromUri(extractUri(msg.to))
        val localTag = SipMessage.newTag()

        val call = SipCall(
            callId = callId,
            isIncoming = true,
            localTag = localTag,
            remoteTag = msg.fromTag,
            remoteNumber = fromUser ?: "unknown",
            simSlot = config.simSlot
        )

        call.incomingVia = msg.via
        call.fromHeader = msg.from
        call.toHeader = msg.to
        call.requestUri = msg.requestUri
        call.incomingContact = msg.contact
        call.parseRemoteSdp(msg.body)

        // Send 100 Trying
        val trying = SipMessage.Builder()
            .response(100, "Trying")
            .apply { msg.via.forEach { addHeader("Via", it) } }
            .header("From", msg.from ?: "")
            .header("To", "${msg.to};tag=$localTag")
            .callId(callId)
            .header("CSeq", msg.cseq ?: "")
            .userAgent()
            .build()
        send(trying)

        calls[callId] = call
        log("[CALL] ← INVITE from $fromUser → $toUser (call=${shortId(callId)})")

        // Determine destination number from the To URI
        // In a gateway scenario, Asterisk calls us with the GSM number as the To user
        onIncomingCall?.invoke(call)
    }

    private fun handleAck(msg: SipMessage) {
        val callId = msg.callId ?: return
        val call = calls[callId] ?: return
        if (call.state == SipCall.State.CONNECTED) {
            log("[CALL] ← ACK (call=${shortId(callId)}) — connected")
        }
    }

    private fun handleBye(msg: SipMessage) {
        val callId = msg.callId ?: return
        val call = calls[callId]

        // Send 200 OK for BYE
        val ok = SipMessage.Builder()
            .response(200, "OK")
            .apply { msg.via.forEach { addHeader("Via", it) } }
            .header("From", msg.from ?: "")
            .header("To", msg.to ?: "")
            .callId(callId)
            .header("CSeq", msg.cseq ?: "")
            .userAgent()
            .build()
        send(ok)

        if (call != null) {
            log("[CALL] ← BYE (call=${shortId(callId)}) — remote hung up")
            call.cleanup()
            calls.remove(callId)
            onCallStateChanged?.invoke(call)
        }
    }

    private fun handleCancel(msg: SipMessage) {
        val callId = msg.callId ?: return
        val call = calls[callId]

        // Send 200 OK for CANCEL
        val ok = SipMessage.Builder()
            .response(200, "OK")
            .apply { msg.via.forEach { addHeader("Via", it) } }
            .header("From", msg.from ?: "")
            .header("To", msg.to ?: "")
            .callId(callId)
            .header("CSeq", msg.cseq ?: "")
            .userAgent()
            .build()
        send(ok)

        if (call != null) {
            // Send 487 Request Terminated for the INVITE
            val terminated = SipMessage.Builder()
                .response(487, "Request Terminated")
                .apply { call.incomingVia?.forEach { addHeader("Via", it) } }
                .header("From", call.fromHeader ?: "")
                .header("To", "${call.toHeader};tag=${call.localTag}")
                .callId(callId)
                .cseq(1, "INVITE")
                .userAgent()
                .build()
            send(terminated)

            log("[CALL] ← CANCEL (call=${shortId(callId)}) — caller hung up before answer")
            call.cleanup()
            calls.remove(callId)
            onCallStateChanged?.invoke(call)
        }
    }

    private fun handleIncomingMessage(msg: SipMessage) {
        val fromUser = extractUserFromUri(extractUri(msg.from))
        val toUser = extractUserFromUri(extractUri(msg.to))
        val body = msg.body

        // Send 200 OK
        val ok = SipMessage.Builder()
            .response(200, "OK")
            .apply { msg.via.forEach { addHeader("Via", it) } }
            .header("From", msg.from ?: "")
            .header("To", msg.to ?: "")
            .callId(msg.callId ?: "")
            .header("CSeq", msg.cseq ?: "")
            .userAgent()
            .build()
        send(ok)

        if (body != null && fromUser != null) {
            log("[SMS] ← MESSAGE from $fromUser → ${toUser ?: "?"}: \"${body.take(60)}${if (body.length > 60) "…" else ""}\"")
            onIncomingMessage?.invoke(fromUser, toUser ?: "", body)
        }
    }

    private fun handleOptions(msg: SipMessage) {
        val ok = SipMessage.Builder()
            .response(200, "OK")
            .apply { msg.via.forEach { addHeader("Via", it) } }
            .header("From", msg.from ?: "")
            .header("To", "${msg.to};tag=${SipMessage.newTag()}")
            .callId(msg.callId ?: "")
            .header("CSeq", msg.cseq ?: "")
            .contact("sip:${config.username}@$localIp:$localPort")
            .userAgent()
            .allow()
            .build()
        send(ok)
    }

    private fun handleResponse(msg: SipMessage) {
        val cseqMethod = msg.cseq?.split(" ")?.getOrNull(1)

        when (cseqMethod) {
            "REGISTER" -> handleRegisterResponse(msg)
            "INVITE" -> handleInviteResponse(msg)
            "BYE" -> { /* BYE response, nothing to do */ }
            "MESSAGE" -> {
                when (msg.statusCode) {
                    200, 202 -> log("[SMS] ← ${msg.statusCode} ${msg.reasonPhrase} (delivered)")
                    401, 407 -> log("[SMS] ⚠ ${msg.statusCode} auth required — not yet implemented")
                    in 400..699 -> log("[SMS] ✗ ${msg.statusCode} ${msg.reasonPhrase}")
                }
            }
        }
    }

    private fun handleInviteResponse(msg: SipMessage) {
        val callId = msg.callId ?: return
        val call = calls[callId] ?: return

        when (msg.statusCode) {
            100 -> {
                call.updateState(SipCall.State.TRYING)
                log("[CALL] ← 100 Trying (call=${shortId(callId)})")
                onCallStateChanged?.invoke(call)
            }
            180 -> {
                call.updateState(SipCall.State.RINGING)
                log("[CALL] ← 180 Ringing (call=${shortId(callId)})")
                onCallStateChanged?.invoke(call)
            }
            183 -> {
                call.parseRemoteSdp(msg.body)
                call.updateState(SipCall.State.PROGRESS)
                call.connectRtp()
                log("[CALL] ← 183 Session Progress (call=${shortId(callId)}) — early media")
                onCallStateChanged?.invoke(call)
            }
            200 -> {
                call.remoteTag = msg.toTag
                call.parseRemoteSdp(msg.body)
                call.updateState(SipCall.State.CONNECTED)
                call.connectRtp()

                // ACK CSeq MUST match the INVITE's — after 401 re-auth that's not 1 (RFC 3261 §17.1.1.3; otherwise Timer H = 32 s → BYE).
                val inviteCseq = msg.cseq?.substringBefore(' ')?.trim()?.toIntOrNull() ?: 1
                val contactUri = extractUri(msg.contact) ?: call.requestUri ?: "sip:${call.remoteNumber}@${config.domain}"
                val ack = SipMessage.Builder()
                    .request("ACK", contactUri)
                    .via(localIp, localPort)
                    .header("From", call.fromHeader ?: "")
                    .header("To", msg.to ?: "")
                    .callId(callId)
                    .cseq(inviteCseq, "ACK")
                    .maxForwards()
                    .userAgent()
                    .build()
                send(ack)

                log("[CALL] ✓ 200 OK (call=${shortId(callId)}) — ACK sent, RTP connected")
                onCallStateChanged?.invoke(call)
            }
            401, 407 -> {
                // Auth challenge for INVITE
                val challengeHeader = msg.getHeader("WWW-Authenticate")
                    ?: msg.getHeader("Proxy-Authenticate")
                if (challengeHeader != null) {
                    val challenge = SipAuth.parseChallenge(challengeHeader)
                    val uri = call.requestUri ?: "sip:${call.remoteNumber}@${config.domain}"
                    val authResponse = SipAuth.buildResponse(
                        challenge, config.username, config.password, "INVITE", uri
                    )

                    // Send ACK for the 401/407
                    val ack = SipMessage.Builder()
                        .request("ACK", uri)
                        .via(localIp, localPort)
                        .header("From", call.fromHeader ?: "")
                        .header("To", msg.to ?: "")
                        .callId(callId)
                        .cseq(call.cseq - 1, "ACK")
                        .maxForwards()
                        .build()
                    send(ack)

                    // Re-send INVITE with auth
                    val rtp = call.rtpStream ?: call.setupRtp(allocateRtpPort())
                    val sdp = call.buildLocalSdp(localIp, rtp.actualLocalPort)

                    val authHeader = if (msg.statusCode == 407) "Proxy-Authorization" else "Authorization"
                    val invite = SipMessage.Builder()
                        .request("INVITE", uri)
                        .via(localIp, localPort)
                        .header("From", call.fromHeader ?: "")
                        .to(null, uri)
                        .callId(callId)
                        .cseq(call.cseq++, "INVITE")
                        .contact("sip:${config.username}@$localIp:$localPort")
                        .maxForwards()
                        .userAgent()
                        .allow()
                        .header(authHeader, authResponse)
                        .contentType("application/sdp")
                        .body(sdp)
                        .build()
                    send(invite)
                    log("[CALL] → INVITE (authenticated) (call=${shortId(callId)})")
                }
            }
            in 400..699 -> {
                log("[CALL] ✗ ${msg.statusCode} ${msg.reasonPhrase} (call=${shortId(callId)})")
                call.cleanup()
                calls.remove(callId)
                onCallStateChanged?.invoke(call)
            }
        }
    }

    // --- Transport ---

    private fun send(msg: SipMessage) {
        val s = socket ?: run {
            log("[NET] ✗ Cannot send — socket is closed")
            return
        }
        val data = msg.toByteArray()
        // If outbound proxy is set, send all packets there instead of directly to the domain
        val proxy = config.outboundProxy
        val targetHost: String
        val targetPort: Int
        if (!proxy.isNullOrBlank()) {
            val parts = proxy.split(":")
            targetHost = parts[0]
            targetPort = parts.getOrNull(1)?.toIntOrNull() ?: config.port
        } else {
            targetHost = config.domain
            targetPort = config.port
        }
        val sendAction = Runnable {
            try {
                val addr = InetAddress.getByName(targetHost)
                s.send(DatagramPacket(data, data.size, addr, targetPort))
                log("[NET] → ${data.size}B to ${addr.hostAddress}:$targetPort from $localIp:$localPort")
                if (WIRE_TRACE) log("[SIP-TX]\n${String(data, Charsets.UTF_8).trimEnd()}\n[/SIP-TX]")
            } catch (e: java.net.UnknownHostException) {
                log("[NET] ✗ DNS lookup failed for $targetHost — check domain/network")
            } catch (e: java.net.SocketException) {
                log("[NET] ✗ Socket error sending to $targetHost:$targetPort — ${e.message}")
            } catch (e: Exception) {
                log("[NET] ✗ Send failed: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Failed to send SIP message", e)
            }
        }
        if (Thread.currentThread().name == "sip-engine") {
            sendAction.run()
        } else {
            executor.execute(sendAction)
        }
    }

    private fun allocateRtpPort(): Int {
        val port = nextRtpPort
        nextRtpPort += 2 // RTP uses even ports, RTCP uses odd
        if (nextRtpPort > config.rtpPortBase + config.rtpPortRange) {
            nextRtpPort = config.rtpPortBase
        }
        return port
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        onLog?.invoke(message)
    }

    private fun shortId(callId: String): String =
        callId.substringBefore('@').take(8)

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP", e)
        }
        return "0.0.0.0"
    }
}
