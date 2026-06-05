package com.copperhead.gateway.sip

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RTP stream handler for G.711 audio (PCMU/PCMA).
 * Sends and receives RTP packets over UDP.
 */
class RtpStream(
    private val localPort: Int = 0
) {
    companion object {
        private const val TAG = "RtpStream"
        private const val RTP_HEADER_SIZE = 12
        // RTP timestamp clock — 8 kHz for both G.711 AND G.722 per RFC 3551.
        // For 20 ms packets the timestamp increments by 160 regardless of
        // whether the audio is narrowband or wideband.
        private const val RTP_CLOCK_RATE = 8000
        private const val PTIME = 20 // ms
        private const val SAMPLES_PER_PACKET = RTP_CLOCK_RATE * PTIME / 1000 // 160
        const val PAYLOAD_TYPE_PCMU = 0
        const val PAYLOAD_TYPE_PCMA = 8
        const val PAYLOAD_TYPE_G722 = 9
        const val PAYLOAD_TYPE_TELEPHONE_EVENT = 101
    }

    var socket: DatagramSocket? = null
        private set
    val actualLocalPort: Int get() = socket?.localPort ?: 0

    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var payloadType: Int = PAYLOAD_TYPE_PCMU
    // telephone-event is a DYNAMIC payload type: the remote's SDP answer dictates
    // which number we must send DTMF on. Defaults to our offered 101 until set.
    private var telephoneEventPt: Int = PAYLOAD_TYPE_TELEPHONE_EVENT
    private var sequenceNumber: Int = 0
    private var timestamp: Long = 0
    private var ssrc: Long = (Math.random() * Int.MAX_VALUE).toLong()
    private val running = AtomicBoolean(false)
    // Lazily created when codec is set to G.722 (otherwise stays null and
    // doesn't load the native lib).
    private var g722Codec: G722Codec? = null

    // Outbound RFC 2833 telephone-event (DTMF) state. Driven from the audio
    // capture thread in lock-step with sendAudio(), so it shares the RTP
    // sequenceNumber/timestamp counters without extra locking.
    private var dtmfActive = false
    private var dtmfEventCode = 0
    private var dtmfTimestamp = 0L
    private var dtmfDurationTicks = 0

    /** PCM sample rate of the audio interface for the negotiated codec. */
    val audioSampleRate: Int
        get() = if (payloadType == PAYLOAD_TYPE_G722) G722Codec.SAMPLE_RATE else 8000

    var onAudioReceived: ((ShortArray) -> Unit)? = null

    fun open() {
        socket = DatagramSocket(localPort).apply {
            soTimeout = 100
            receiveBufferSize = 65536
            sendBufferSize = 65536
        }
        Log.i(TAG, "RTP socket opened on port ${socket!!.localPort}")
    }

    fun setRemote(
        address: InetAddress,
        port: Int,
        codec: Int = PAYLOAD_TYPE_PCMU,
        telephoneEventPayloadType: Int = PAYLOAD_TYPE_TELEPHONE_EVENT
    ) {
        remoteAddress = address
        remotePort = port
        payloadType = codec
        telephoneEventPt = telephoneEventPayloadType
        if (codec == PAYLOAD_TYPE_G722 && g722Codec == null) {
            g722Codec = G722Codec()
        }
        val codecName = when (codec) {
            PAYLOAD_TYPE_G722 -> "G722"
            PAYLOAD_TYPE_PCMU -> "PCMU"
            PAYLOAD_TYPE_PCMA -> "PCMA"
            else -> "type=$codec"
        }
        Log.i(TAG, "RTP remote set to ${address.hostAddress}:$port codec=$codecName")
    }

    fun startReceiving() {
        if (running.getAndSet(true)) return
        thread(name = "rtp-recv", isDaemon = true) {
            // Generous buffer: support up to 60 ms G.711 + any header
            // extensions, so we never truncate even if Asterisk renegotiates
            // ptime mid-stream.
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            var firstPacketDumped = false

            while (running.get()) {
                try {
                    // CRITICAL: reset packet's effective length each iteration.
                    // After receive(), packet.length is set to bytes-received,
                    // which becomes the MAX for the next receive() — silently
                    // truncating larger packets. We saw this bug in SipEngine
                    // too; same fix here.
                    packet.setData(buf, 0, buf.size)
                    socket?.receive(packet)
                    if (packet.length < RTP_HEADER_SIZE) continue

                    // Parse the actual RTP header. The standard header is
                    // 12 bytes but is followed by 4*CC bytes of CSRC list,
                    // and (if X bit is set) an extension header.
                    val byte0 = buf[0].toInt() and 0xFF
                    val cc = byte0 and 0x0F
                    val xBit = (byte0 and 0x10) != 0
                    val pt = buf[1].toInt() and 0x7F

                    var headerEnd = RTP_HEADER_SIZE + 4 * cc
                    if (xBit && packet.length >= headerEnd + 4) {
                        val extLen =
                            ((buf[headerEnd + 2].toInt() and 0xFF) shl 8) or
                            (buf[headerEnd + 3].toInt() and 0xFF)
                        headerEnd += 4 + 4 * extLen
                    }
                    if (headerEnd >= packet.length) continue

                    if (!firstPacketDumped) {
                        firstPacketDumped = true
                        Log.i(TAG, "First RTP packet: ${packet.length} bytes, pt=$pt, cc=$cc, x=$xBit, headerEnd=$headerEnd, audioLen=${packet.length - headerEnd}")
                    }

                    val audioLen = packet.length - headerEnd
                    when (pt) {
                        PAYLOAD_TYPE_G722 -> {
                            val codec = g722Codec ?: run {
                                g722Codec = G722Codec()
                                g722Codec
                            }
                            // 1 G.722 octet → 2 PCM 16-bit samples at 16 kHz.
                            val samples = ShortArray(audioLen * 2)
                            codec?.decode(buf, headerEnd, audioLen, samples, 0)
                            onAudioReceived?.invoke(samples)
                        }
                        PAYLOAD_TYPE_PCMU -> {
                            val samples = ShortArray(audioLen)
                            for (i in 0 until audioLen) {
                                samples[i] = ulawDecode(buf[headerEnd + i].toInt() and 0xFF)
                            }
                            onAudioReceived?.invoke(samples)
                        }
                        PAYLOAD_TYPE_PCMA -> {
                            val samples = ShortArray(audioLen)
                            for (i in 0 until audioLen) {
                                samples[i] = alawDecode(buf[headerEnd + i].toInt() and 0xFF)
                            }
                            onAudioReceived?.invoke(samples)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "RTP receive error", e)
                }
            }
        }
    }

    /**
     * Send a frame of audio. Sample count interpretation depends on the
     * negotiated codec:
     *   - PCMU/PCMA: [samples] is N samples at 8 kHz; one octet per sample.
     *   - G.722: [samples] is N samples at 16 kHz (even count); one octet
     *     per 2 samples (so output size is N/2). RTP timestamp still
     *     increments by N/2 (8 kHz clock per RFC 3551).
     */
    fun sendAudio(samples: ShortArray) {
        val remote = remoteAddress ?: return
        val port = remotePort
        if (port <= 0) return

        val payload: ByteArray
        when (payloadType) {
            PAYLOAD_TYPE_G722 -> {
                val codec = g722Codec ?: return
                if (samples.size % 2 != 0) return
                payload = ByteArray(samples.size / 2)
                codec.encode(samples, samples.size, payload, 0)
            }
            PAYLOAD_TYPE_PCMU -> {
                payload = ByteArray(samples.size)
                for (i in samples.indices) payload[i] = ulawEncode(samples[i]).toByte()
            }
            PAYLOAD_TYPE_PCMA -> {
                payload = ByteArray(samples.size)
                for (i in samples.indices) payload[i] = alawEncode(samples[i]).toByte()
            }
            else -> return
        }

        val packet = buildRtpPacket(payload)
        try {
            socket?.send(DatagramPacket(packet, packet.size, remote, port))
        } catch (e: Exception) {
            Log.w(TAG, "RTP send error", e)
        }
    }

    /**
     * Begin an RFC 2833 telephone-event for [eventCode] (0-9, 10='*', 11='#',
     * 12-15='A'-'D'). Call once at tone onset, then [dtmfUpdate] per audio
     * frame while the tone is held, then [dtmfEnd] at release.
     *
     * MUST be called from the same thread as [sendAudio] (the audio capture
     * thread): they share the RTP sequence/timestamp counters with no locking.
     */
    fun dtmfBegin(eventCode: Int) {
        if (eventCode < 0 || eventCode > 15) return
        if (dtmfActive) dtmfEnd()
        dtmfActive = true
        dtmfEventCode = eventCode
        // Anchor the event at the current media timestamp. The audio thread
        // keeps advancing `timestamp` (blanked frames are still sent during the
        // tone), so audio after the event lines up exactly at start+duration —
        // and all event packets carry this same timestamp per RFC 2833 §3.4.
        dtmfTimestamp = timestamp
        dtmfDurationTicks = SAMPLES_PER_PACKET
        sendTelephoneEvent(marker = true, end = false)
    }

    /** Extend the in-progress telephone-event by one frame. No-op if inactive. */
    fun dtmfUpdate() {
        if (!dtmfActive) return
        dtmfDurationTicks = (dtmfDurationTicks + SAMPLES_PER_PACKET).coerceAtMost(0xFFFF)
        sendTelephoneEvent(marker = false, end = false)
    }

    /** Finish the in-progress telephone-event. No-op if inactive. */
    fun dtmfEnd() {
        if (!dtmfActive) return
        // Three end packets (E bit set) for resilience against UDP loss —
        // Asterisk finalises the digit on whichever it receives first.
        repeat(3) { sendTelephoneEvent(marker = false, end = true) }
        dtmfActive = false
    }

    private fun sendTelephoneEvent(marker: Boolean, end: Boolean) {
        val remote = remoteAddress ?: return
        val port = remotePort
        if (port <= 0) return

        val packet = ByteArray(RTP_HEADER_SIZE + 4)
        // Version=2, no padding/extension/CSRC.
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or (telephoneEventPt and 0x7F)).toByte()
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        // All event packets carry the event's start timestamp (NOT the running
        // audio timestamp), so the receiver sees one event, not a slide.
        packet[4] = ((dtmfTimestamp shr 24) and 0xFF).toByte()
        packet[5] = ((dtmfTimestamp shr 16) and 0xFF).toByte()
        packet[6] = ((dtmfTimestamp shr 8) and 0xFF).toByte()
        packet[7] = (dtmfTimestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()

        // Payload: event(8) | E,R,volume(8) | duration(16), per RFC 2833 §3.5.
        packet[12] = dtmfEventCode.toByte()
        packet[13] = ((if (end) 0x80 else 0) or 0x0A).toByte() // E bit + volume 10
        packet[14] = ((dtmfDurationTicks shr 8) and 0xFF).toByte()
        packet[15] = (dtmfDurationTicks and 0xFF).toByte()

        try {
            socket?.send(DatagramPacket(packet, packet.size, remote, port))
        } catch (e: Exception) {
            Log.w(TAG, "DTMF send error", e)
        }
    }

    private fun buildRtpPacket(payload: ByteArray, marker: Boolean = false): ByteArray {
        val packet = ByteArray(RTP_HEADER_SIZE + payload.size)

        // Version=2, no padding, no extension, no CSRC
        packet[0] = 0x80.toByte()
        // Marker + payload type
        packet[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        // Sequence number
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        // Timestamp
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        // RTP timestamp clock = 8 kHz for both G.711 AND G.722 (RFC 3551).
        // At 64 kbps and 8 kHz clock, payload.size octets = payload.size
        // timestamp ticks. Works for any ptime (10/20/30 ms) without changes.
        timestamp += payload.size
        // SSRC
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()

        System.arraycopy(payload, 0, packet, RTP_HEADER_SIZE, payload.size)
        return packet
    }

    fun stop() {
        running.set(false)
    }

    fun close() {
        stop()
        socket?.close()
        socket = null
        g722Codec?.close()
        g722Codec = null
    }
}

// G.711 μ-law encode/decode
private val ULAW_BIAS = 0x84
private val ULAW_CLIP = 32635

fun ulawEncode(sample: Short): Int {
    var pcm = sample.toInt()
    val sign = (pcm shr 8) and 0x80
    if (sign != 0) pcm = -pcm
    if (pcm > ULAW_CLIP) pcm = ULAW_CLIP
    pcm += ULAW_BIAS

    val exponent = ulawCompressTable[(pcm shr 7) and 0xFF]
    val mantissa = (pcm shr (exponent + 3)) and 0x0F
    return (sign or (exponent shl 4) or mantissa).inv() and 0xFF
}

fun ulawDecode(ulaw: Int): Short {
    val u = ulaw.inv() and 0xFF
    val sign = u and 0x80
    val exponent = (u shr 4) and 0x07
    val mantissa = u and 0x0F
    var sample = ((mantissa shl 4) + 0x08) shl exponent
    sample -= ULAW_BIAS
    return if (sign != 0) (-sample).toShort() else sample.toShort()
}

fun alawEncode(sample: Short): Int {
    var pcm = sample.toInt()
    val sign: Int
    if (pcm >= 0) {
        sign = 0xD5
    } else {
        sign = 0x55
        pcm = -pcm - 1
    }

    val exponent: Int
    val mantissa: Int
    if (pcm < 256) {
        exponent = 0
        mantissa = pcm shr 4
    } else {
        var e = 1
        var p = pcm shr 5
        while (p > 1 && e < 7) {
            p = p shr 1
            e++
        }
        exponent = e
        mantissa = (pcm shr (exponent + 3)) and 0x0F
    }

    return ((exponent shl 4) or mantissa) xor sign
}

fun alawDecode(alaw: Int): Short {
    val a = alaw xor 0x55
    val exponent = (a shr 4) and 0x07
    val mantissa = a and 0x0F
    var sample = if (exponent == 0) {
        (mantissa shl 4) + 8
    } else {
        ((mantissa shl 4) + 0x108) shl (exponent - 1)
    }
    if (a and 0x80 == 0) sample = -sample
    return sample.toShort()
}

private val ulawCompressTable = intArrayOf(
    0,0,1,1,2,2,2,2,3,3,3,3,3,3,3,3,
    4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,
    5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
    5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
    6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
    6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
    6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
    6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7
)
