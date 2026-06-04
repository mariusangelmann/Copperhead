package com.copperhead.gateway.sip

/**
 * G.722 wideband codec (ITU-T G.722, 64 kbps Mode 1).
 *
 * Wraps the Asterisk/spandsp G.722 reference implementation (Steve Underwood,
 * public domain) via JNI. The native code is bit-exact ITU-test-vector
 * conformant — same codec used in Asterisk, FreeSWITCH, PJSIP.
 *
 * Each [G722Codec] instance holds independent encoder + decoder state and
 * MUST NOT be shared across calls. Call [close] when done to release the
 * native state structs.
 *
 * **Sample rate / RTP quirk**: G.722 produces 16 kHz audio output, but RFC
 * 3551 mandates an 8 kHz RTP timestamp clock for historical reasons. So a
 * 20 ms RTP packet carries 160 octets and the RTP timestamp advances by 160.
 *
 * **Wire format**: 8000 octets/s = 64 kbps. Each octet packs 6 bits of
 * low-band ADPCM (0-4 kHz, 48 kbps) + 2 bits of high-band ADPCM
 * (4-8 kHz, 16 kbps).
 */
class G722Codec : AutoCloseable {

    @Volatile private var encoderHandle: Long = 0
    @Volatile private var decoderHandle: Long = 0
    // Single lock guarding both encoder and decoder. Callers across threads
    // (RTP receive thread vs SIP signalling thread calling close()) MUST go
    // through this; otherwise we hit a use-after-free in the native struct.
    private val nativeLock = Any()

    init {
        encoderHandle = nativeNewEncoder()
        decoderHandle = nativeNewDecoder()
        check(encoderHandle != 0L) { "Failed to allocate G.722 encoder" }
        check(decoderHandle != 0L) { "Failed to allocate G.722 decoder" }
    }

    /**
     * Encode 16 kHz mono PCM 16-bit samples into G.722 octets.
     *
     * @param pcm Input 16-bit PCM at 16 kHz mono.
     * @param pcmCount Number of PCM samples to encode. Must be even.
     * @param out Destination buffer for G.722 octets.
     * @param outOffset Starting offset in [out].
     * @return Number of G.722 octets written (= pcmCount / 2).
     */
    fun encode(pcm: ShortArray, pcmCount: Int, out: ByteArray, outOffset: Int): Int {
        require(pcmCount % 2 == 0) { "G.722 requires even PCM sample count, got $pcmCount" }
        require(outOffset + pcmCount / 2 <= out.size) { "out buffer too small" }
        synchronized(nativeLock) {
            if (encoderHandle == 0L) return 0
            return nativeEncode(encoderHandle, pcm, 0, pcmCount, out, outOffset)
        }
    }

    /**
     * Decode G.722 octets into 16 kHz mono PCM 16-bit samples.
     *
     * @param g722 Source G.722 octets.
     * @param g722Offset Starting offset in [g722].
     * @param g722Count Number of G.722 octets to decode.
     * @param out Destination buffer for PCM samples.
     * @param outOffset Starting offset in [out].
     * @return Number of PCM samples written (= 2 × g722Count).
     */
    fun decode(g722: ByteArray, g722Offset: Int, g722Count: Int, out: ShortArray, outOffset: Int): Int {
        require(outOffset + g722Count * 2 <= out.size) { "out buffer too small" }
        synchronized(nativeLock) {
            if (decoderHandle == 0L) return 0
            return nativeDecode(decoderHandle, g722, g722Offset, g722Count, out, outOffset)
        }
    }

    override fun close() {
        synchronized(nativeLock) {
            if (encoderHandle != 0L) {
                nativeFreeEncoder(encoderHandle)
                encoderHandle = 0
            }
            if (decoderHandle != 0L) {
                nativeFreeDecoder(decoderHandle)
                decoderHandle = 0
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("g722jni")
        }

        /** RTP payload type assigned to G.722 by RFC 3551. */
        const val RTP_PAYLOAD_TYPE = 9
        /** G.722 sample rate at the audio interface (16 kHz wideband). */
        const val SAMPLE_RATE = 16000

        @JvmStatic private external fun nativeNewEncoder(): Long
        @JvmStatic private external fun nativeNewDecoder(): Long
        @JvmStatic private external fun nativeFreeEncoder(handle: Long)
        @JvmStatic private external fun nativeFreeDecoder(handle: Long)
        @JvmStatic private external fun nativeEncode(
            handle: Long, pcm: ShortArray, pcmOffset: Int, pcmCount: Int,
            out: ByteArray, outOffset: Int
        ): Int
        @JvmStatic private external fun nativeDecode(
            handle: Long, g722: ByteArray, g722Offset: Int, g722Count: Int,
            out: ShortArray, outOffset: Int
        ): Int
    }
}
