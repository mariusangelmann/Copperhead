package com.copperhead.gateway.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.copperhead.gateway.sip.DtmfDetector
import com.copperhead.gateway.sip.RtpStream
import com.copperhead.gateway.sip.dtmfEventCode
import com.copperhead.gateway.util.MagiskModule
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Bridges audio between an in-progress GSM cellular call and the SIP/RTP stream.
 *
 * Reality check: stock Android does NOT let an app inject samples directly into
 * the GSM uplink, nor read the downlink. The audio HAL doesn't expose those
 * paths to third-party (or even system) apps without vendor cooperation. We
 * therefore have two working modes:
 *
 *  - **HAL mode (default)**: set [AudioManager.MODE_IN_CALL], capture via
 *    [MediaRecorder.AudioSource.VOICE_COMMUNICATION] (or VOICE_CALL with
 *    CAPTURE_AUDIO_OUTPUT), play via legacy [AudioTrack] on the
 *    [AudioManager.STREAM_VOICE_CALL] stream. On devices whose HAL honours this
 *    stream as a call uplink injection point, the remote cellular party will
 *    hear our SIP audio. On the rest, it just plays on the earpiece — there's
 *    no way around that without HAL access.
 *
 *  - **Speakerphone-loopback mode**: opt-in fallback. We force the speakerphone
 *    on so SIP audio is played loud out of the speaker, and the cellular
 *    call's own microphone picks it up acoustically. Awful quality, lots of
 *    echo, but actually bridges audio on every device.
 */
class AudioBridge(
    private val context: Context? = null,
    private val useSpeakerphoneLoopback: Boolean = false,
    /**
     * If true, mute the device's physical mic so the cellular caller only
     * hears what we explicitly write to TYPE_TELEPHONY (i.e. SIP audio from
     * FreePBX), not room noise.
     */
    private val muteDeviceMic: Boolean = true
) {
    companion object {
        private const val TAG = "AudioBridge"
        private const val PTIME_MS = 20
        // Native rate of the Pixel/Tensor "voice call tx" mix port.
        private const val PLAYBACK_SAMPLE_RATE = 48000
        // Match the "voice call tx" mix port channel layout in the Pixel
        // audio_policy XML (stereo). Duplicating L=R ourselves is free and
        // more predictable than letting the framework convert.
        private const val PLAYBACK_CHANNELS = 2
        // Attenuation applied to playback samples before they hit the modem.
        // VoLTE/AMR encoders compress hot signals very aggressively (AGC);
        // -6 dB pre-attenuation keeps us in the codec's intended range.
        private const val PLAYBACK_GAIN = 0.5
        // Jitter buffer warm-up packets (4 = 80 ms).
        private const val JITTER_WARMUP_PACKETS = 4
        // Jitter buffer overflow cap (12 = 240 ms).
        private const val JITTER_MAX_PACKETS = 12
        // AudioTrack buffer target in 20 ms packets (200 ms headroom).
        private const val PLAYBACK_BUFFER_PACKETS = 10
    }

    var onLog: ((String) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    /**
     * Background AudioRecord on hidden source VOICE_UPLINK=2. Not actually
     * read from. Its presence in the audio_policy "claims" the uplink stream,
     * which on some Qualcomm/MediaTek HALs causes the routing matrix to send
     * STREAM_VOICE_CALL playback INTO the cellular uplink instead of the
     * earpiece. Undocumented, HAL-dependent.
     */
    private var uplinkClaim: AudioRecord? = null
    private var uplinkDrainThread: Thread? = null
    // Track the audio worker threads so stop() can wait for them to exit
    // before releasing the underlying AudioRecord/AudioTrack — releasing while
    // a thread is mid-read/write deallocates the native HAL object under it
    // and crashes natively.
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var rtpStream: RtpStream? = null
    private val running = AtomicBoolean(false)

    // Saved AudioManager state so we can restore on stop()
    private var savedMode: Int = -1
    private var savedSpeakerphone: Boolean = false
    private var savedMicMute: Boolean = false
    private var audioManager: AudioManager? = null
    private var preMuted: Boolean = false

    /**
     * Privacy guard: close the device's mic-to-modem path BEFORE the cellular
     * call goes active. Without this, the window between answering the
     * cellular leg and AudioBridge.start() (which only fires once SIP is
     * CONNECTED) is ~100s of ms to a couple of seconds during which the
     * modem routes room audio straight to the caller. CallBridge invokes
     * this right before gsmCallManager.answer / makeCall.
     *
     * Idempotent. State saved here is preserved across the later start()
     * call so the original mode/mute are restored on stop().
     */
    fun preMuteMic() {
        if (preMuted) return
        val am = context?.getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        savedMode = am.mode
        savedSpeakerphone = am.isSpeakerphoneOn
        savedMicMute = am.isMicrophoneMute
        am.mode = AudioManager.MODE_IN_CALL
        am.isMicrophoneMute = true
        preMuted = true
        log("Pre-muted mic (was mode=$savedMode micMute=$savedMicMute) — closes room-audio window before SIP connects")
    }

    /**
     * Start bridging.
     *
     * @param rtp The RTP stream feeding/draining audio.
     * @param inputSampleRate PCM sample rate at the audio interface, derived
     *   from the negotiated codec — 8000 for G.711 (PCMU/PCMA), 16000 for
     *   G.722. The drain thread upsamples to PLAYBACK_SAMPLE_RATE (48 kHz).
     */
    fun start(rtp: RtpStream, inputSampleRate: Int = 8000) {
        if (running.getAndSet(true)) return
        rtpStream = rtp
        val sampleRate = inputSampleRate
        val packetSamples = sampleRate * PTIME_MS / 1000          // 160 (G.711) or 320 (G.722)
        val upsampleRatio = PLAYBACK_SAMPLE_RATE / sampleRate     // 6 or 3
        val playbackPacketShorts = packetSamples * upsampleRatio * PLAYBACK_CHANNELS // 1920 in both cases (!)
        log("Codec input rate=${sampleRate} Hz, packet=${packetSamples} samples, upsample=${upsampleRatio}×, output stereo @ ${PLAYBACK_SAMPLE_RATE} Hz")

        // ── DTMF: detect the caller's in-band tones and re-emit as RFC 2833 ──
        // The remote cellular party's key presses reach us only as audio tones
        // in the GSM downlink. FreePBX expects out-of-band RFC 2833, so we
        // detect the tones here and the RtpStream forwards them as
        // telephone-event packets (then blank the in-band tone — see capture
        // loop — so the digit can't register twice).
        val dtmfDetector = DtmfDetector(sampleRate).apply {
            onStart = { digit ->
                val code = dtmfEventCode(digit)
                if (code >= 0) {
                    rtp.dtmfBegin(code)
                    log("DTMF '$digit' → RFC 2833 (event $code)")
                }
            }
            onUpdate = { rtp.dtmfUpdate() }
            onEnd = { digit, durationMs ->
                rtp.dtmfEnd()
                log("DTMF '$digit' released (${durationMs}ms)")
            }
        }

        audioManager = context?.getSystemService(AudioManager::class.java)?.also { am ->
            // Only capture original state if preMuteMic() didn't already.
            // Otherwise we'd save the already-muted state and restore TO
            // muted on stop(), leaving the device silent after hangup.
            if (!preMuted) {
                savedMode = am.mode
                savedSpeakerphone = am.isSpeakerphoneOn
                savedMicMute = am.isMicrophoneMute
            }
            // MODE_IN_CALL tells the audio HAL we want call-audio routing for
            // STREAM_VOICE_CALL. Without it, STREAM_VOICE_CALL still ends up
            // on the earpiece. Note: MODE_IN_CALL traditionally required
            // MODIFY_PHONE_STATE (granted by your Magisk module).
            am.mode = AudioManager.MODE_IN_CALL
            am.isSpeakerphoneOn = useSpeakerphoneLoopback
            // Mute the physical mic so the cellular caller only hears what we
            // inject via SIP, not whatever ambient sound is around the gateway.
            // Without this, room noise leaks into the uplink alongside SIP.
            if (muteDeviceMic) {
                am.isMicrophoneMute = true
            }
            log("AudioManager mode=IN_CALL spk=$useSpeakerphoneLoopback micMute=${am.isMicrophoneMute} (was mode=$savedMode spk=$savedSpeakerphone micMute=$savedMicMute)")
        }

        // ── Self-check: are we running as priv-app? ───────────────────────
        // Without this, CAPTURE_AUDIO_OUTPUT is silently ignored and every
        // attempt to use VOICE_CALL/VOICE_DOWNLINK will fall through to mic.
        val ctx = context
        if (ctx != null) {
            val isPriv = MagiskModule.isPrivApp(ctx)
            val capGranted = ctx.checkSelfPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            log("Priv-app=$isPriv, CAPTURE_AUDIO_OUTPUT=$capGranted (sourceDir=${ctx.applicationInfo.sourceDir})")
            if (!isPriv) {
                log("⚠ NOT installed as /system/priv-app/ — CAPTURE_AUDIO_OUTPUT will be ignored. Run Magisk module install + reboot.")
            } else if (!capGranted) {
                log("⚠ Priv-app OK but CAPTURE_AUDIO_OUTPUT not granted — privapp-permissions XML missing?")
            }
        }

        // ── Capture (GSM → SIP) ───────────────────────────────────────────
        // Source priority depends on mode:
        //   - speakerphone-loopback → mic-based capture (acoustic coupling)
        //   - mic-muted gateway     → VOICE_DOWNLINK first (we only need the
        //       cellular caller's voice; uplink is silent anyway because mic
        //       is muted, so VOICE_CALL=4's mix gives us no extra info)
        //   - mic-active (handset)  → VOICE_CALL first (real uplink+downlink mix)
        val sourcePriority: IntArray = when {
            useSpeakerphoneLoopback -> intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            muteDeviceMic -> intArrayOf(
                3,  // VOICE_DOWNLINK (hidden) — only the cellular party's voice
                4,  // VOICE_CALL fallback
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            )
            else -> intArrayOf(
                4,  // VOICE_CALL (hidden) — full uplink+downlink mix
                3,  // VOICE_DOWNLINK fallback
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            )
        }

        val recordBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(packetSamples * 2)

        var chosenSource = -1
        for (src in sourcePriority) {
            try {
                @Suppress("MissingPermission")
                val ar = AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize
                )
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = ar
                    chosenSource = src
                    log("AudioRecord source=$src (${sourceName(src)}) initialised")
                    break
                } else {
                    log("⚠ AudioRecord source=$src (${sourceName(src)}) constructed but state=${ar.state}, releasing")
                    ar.release()
                }
            } catch (e: SecurityException) {
                log("⚠ AudioRecord source=$src (${sourceName(src)}) denied: ${e.message}")
            } catch (e: Exception) {
                log("⚠ AudioRecord source=$src (${sourceName(src)}) failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        if (audioRecord == null) {
            log("✗ All AudioRecord sources failed. Uplink capture is dead.")
            running.set(false)
            restoreAudioManager()
            return
        }

        // ── Uplink claim trick (SIP → GSM injection attempt) ──────────────
        // Opening AudioRecord on VOICE_UPLINK=2 in parallel can flip the HAL's
        // routing matrix so that subsequent AudioTrack output on
        // STREAM_VOICE_CALL gets injected into the cellular uplink instead of
        // playing on the earpiece. Effect is HAL-dependent: works on some
        // Qualcomm/MediaTek devices, no-op on Pixel/Samsung. We always try.
        if (!useSpeakerphoneLoopback) {
            try {
                @Suppress("MissingPermission")
                val claim = AudioRecord(
                    2, // VOICE_UPLINK (hidden)
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize
                )
                if (claim.state == AudioRecord.STATE_INITIALIZED) {
                    claim.startRecording()
                    uplinkClaim = claim
                    // Drain the claim's buffer in a daemon thread so the
                    // ring buffer doesn't overflow and trigger the HAL to
                    // drop our claim.
                    uplinkDrainThread = thread(name = "audio-uplink-drain", isDaemon = true) {
                        val drainBuf = ShortArray(packetSamples)
                        while (running.get()) {
                            try {
                                claim.read(drainBuf, 0, packetSamples)
                            } catch (_: Exception) { break }
                        }
                    }
                    log("✓ VOICE_UPLINK claim active — HAL may route playback into cellular uplink")
                } else {
                    log("⚠ VOICE_UPLINK claim state=${claim.state}, not retained")
                    claim.release()
                }
            } catch (e: SecurityException) {
                log("⚠ VOICE_UPLINK claim denied: ${e.message} (priv-app needed)")
            } catch (e: Exception) {
                log("⚠ VOICE_UPLINK claim failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        // ── Playback (SIP → GSM) ──────────────────────────────────────────
        // Build modern AudioTrack with USAGE_VOICE_COMMUNICATION. The trick
        // that actually works on devices like Pixel (chenxiaolong/BCP) is to
        // enumerate output devices and explicitly set the preferred device to
        // AudioDeviceInfo.TYPE_TELEPHONY (=18) — that's the official cellular
        // uplink path. With MODIFY_PHONE_STATE (priv-app) granted, AudioTrack
        // samples then flow INTO the active call. Fallback to legacy
        // STREAM_VOICE_CALL on devices without TYPE_TELEPHONY output.
        // AudioTrack runs at HAL-native 48 kHz so no resampling happens after us.
        // 200 ms of buffer gives the HAL room to absorb burst-y RTP writes
        // from the jitter buffer's drain thread.
        val playBufferSize = (AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )).coerceAtLeast(playbackPacketShorts * 2 * PLAYBACK_BUFFER_PACKETS)

        val telephonyOutput: AudioDeviceInfo? = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.find { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

        if (telephonyOutput != null) {
            log("✓ Found TYPE_TELEPHONY output (id=${telephonyOutput.id}, name=${telephonyOutput.productName}) — will route SIP → cellular uplink")
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val routed = audioTrack?.setPreferredDevice(telephonyOutput) ?: false
            if (routed) {
                log("✓ AudioTrack.setPreferredDevice(TYPE_TELEPHONY) accepted")
            } else {
                log("⚠ AudioTrack.setPreferredDevice(TYPE_TELEPHONY) refused — uplink injection won't work")
            }
        } else {
            log("⚠ No TYPE_TELEPHONY output exposed by this device — falling back to STREAM_VOICE_CALL legacy routing (HAL roulette)")
            audioTrack = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    PLAYBACK_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    playBufferSize,
                    AudioTrack.MODE_STREAM
                )
            } catch (e: Exception) {
                log("⚠ Legacy AudioTrack failed (${e.message}), using Builder/USAGE_VOICE_COMMUNICATION")
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(PLAYBACK_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(playBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }
        }

        audioRecord?.startRecording()
        audioTrack?.play()

        // GSM → SIP: read mic/uplink samples, send as RTP.
        captureThread = thread(name = "audio-gsm2sip", isDaemon = true) {
            val buffer = ShortArray(packetSamples)
            while (running.get()) {
                val read = audioRecord?.read(buffer, 0, packetSamples) ?: break
                if (read > 0) {
                    // Detect the caller's DTMF in the captured PCM. While a tone
                    // is active we blank it from the forwarded audio: the digit
                    // travels out-of-band as RFC 2833 (see dtmfDetector wiring),
                    // so leaving the in-band tone in would risk a double digit.
                    if (dtmfDetector.process(buffer, read)) {
                        buffer.fill(0, 0, read)
                    }
                    val samples = if (read == packetSamples) buffer else buffer.copyOf(read)
                    rtpStream?.sendAudio(samples)
                }
            }
            // Flush any tone still held when capture stops (still on this thread,
            // so it shares the RTP counters safely).
            dtmfDetector.reset()
        }

        // SIP → GSM: receive-side jitter buffer.
        //
        // Why: RTP arrives with ±5-30 ms jitter even on a healthy LAN. Writing
        // every packet immediately means AudioTrack alternates between
        // overfeed and starvation → constant underruns, audible as the
        // clicking/choppy "trash" hold-music symptom.
        //
        // How: incoming packets are enqueued. A drain thread pulls one packet
        // per 20 ms, writes silence on underrun (PLC), drops oldest on
        // overflow. AudioTrack only starts playing once the queue has
        // warmed up to JITTER_WARMUP_PACKETS — otherwise the first dozen
        // packets click as the buffer underruns while filling.
        val jitterQueue = LinkedBlockingQueue<ShortArray>()
        val droppedOverflow = AtomicInteger(0)
        val droppedUnderrun = AtomicInteger(0)
        val received = AtomicInteger(0)

        rtp.onAudioReceived = { samples ->
            if (running.get()) {
                received.incrementAndGet()
                jitterQueue.offer(samples)
                while (jitterQueue.size > JITTER_MAX_PACKETS) {
                    jitterQueue.poll()
                    droppedOverflow.incrementAndGet()
                }
            }
        }

        playbackThread = thread(name = "audio-sip2gsm", isDaemon = true) {
            val silenceStereo = ShortArray(playbackPacketShorts)
            val outScratch = ShortArray(playbackPacketShorts)
            var warmed = false
            var lastStatsAt = System.currentTimeMillis()
            var peakAmp = 0
            var routingChecked = false

            while (running.get()) {
                // Warm-up: wait until queue has enough packets before draining.
                if (!warmed) {
                    if (jitterQueue.size >= JITTER_WARMUP_PACKETS) {
                        warmed = true
                        log("[AUDIO] Jitter buffer warm (depth=${jitterQueue.size})")
                    } else {
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                        continue
                    }
                }

                val packet = jitterQueue.poll(25, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    val n = packet.size.coerceAtMost(packetSamples)
                    // Zero-order hold upsample: each input sample held for
                    // `upsampleRatio` output samples (stereo duplicated L=R).
                    // -6 dB gain attenuation keeps the AMR encoder out of AGC.
                    for (i in 0 until n) {
                        val attenuated = (packet[i] * PLAYBACK_GAIN).toInt()
                            .coerceIn(-32768, 32767).toShort()
                        for (j in 0 until upsampleRatio) {
                            val outIdx = (i * upsampleRatio + j) * PLAYBACK_CHANNELS
                            outScratch[outIdx] = attenuated
                            outScratch[outIdx + 1] = attenuated
                        }
                        val abs = if (attenuated < 0) -attenuated.toInt() else attenuated.toInt()
                        if (abs > peakAmp) peakAmp = abs
                    }
                    val outSampleCount = n * upsampleRatio * PLAYBACK_CHANNELS
                    audioTrack?.write(outScratch, 0, outSampleCount, AudioTrack.WRITE_BLOCKING)

                    // One-shot routing verification after AudioTrack is
                    // actually playing. setPreferredDevice() returning true
                    // doesn't guarantee the HAL honoured it.
                    if (!routingChecked) {
                        routingChecked = true
                        val routedTo = audioTrack?.routedDevice
                        if (routedTo != null) {
                            log("[AUDIO] AudioTrack routed to type=${routedTo.type} (${if (routedTo.type == AudioDeviceInfo.TYPE_TELEPHONY) "TELEPHONY UPLINK ✓" else "NOT telephony — caller won't hear us cleanly"})")
                        }
                    }
                } else {
                    audioTrack?.write(silenceStereo, 0, silenceStereo.size, AudioTrack.WRITE_BLOCKING)
                    droppedUnderrun.incrementAndGet()
                    if (jitterQueue.isEmpty() && droppedUnderrun.get() % 25 == 0) {
                        warmed = false
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastStatsAt >= 5000) {
                    val rx = received.getAndSet(0)
                    val ovr = droppedOverflow.getAndSet(0)
                    val urn = droppedUnderrun.getAndSet(0)
                    val peakDb = if (peakAmp > 0) (20.0 * Math.log10(peakAmp / 32767.0)).toInt() else -99
                    log("[AUDIO] RTP rx=${rx / 5}/s, queue=${jitterQueue.size}, overflows=${ovr}/5s, underruns=${urn}/5s, peak=${peakDb} dBFS")
                    lastStatsAt = now
                    peakAmp = 0
                }
            }
        }

        log("✓ AudioBridge started (capture=${sourceName(chosenSource)}, rate=${sampleRate}Hz, upsample=${upsampleRatio}×, mode=${if (useSpeakerphoneLoopback) "speakerphone-loopback" else "HAL"})")
    }

    private fun sourceName(s: Int): String = when (s) {
        2 -> "VOICE_UPLINK"
        3 -> "VOICE_DOWNLINK"
        4 -> "VOICE_CALL"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> "source=$s"
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        // No more RTP callbacks into our jitter queue.
        rtpStream?.onAudioReceived = null

        // 1. Stop (not release!) the native audio objects first. This unblocks
        //    any thread currently parked in read()/write() with an ERROR
        //    return so it can notice running=false and exit cleanly.
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { uplinkClaim?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}

        // 2. Wait briefly for the worker threads to exit their loops. Without
        //    this, releasing the AudioRecord/AudioTrack while a thread is in
        //    a native read/write tears down the HAL object under it → native
        //    SIGSEGV → app restart (which is exactly the crash we were hitting
        //    on call end).
        listOfNotNull(captureThread, playbackThread, uplinkDrainThread).forEach {
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        captureThread = null
        playbackThread = null
        uplinkDrainThread = null

        // 3. Now safe to release the native objects.
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { uplinkClaim?.release() } catch (_: Exception) {}
        uplinkClaim = null

        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        restoreAudioManager()

        log("AudioBridge stopped")
    }

    private fun restoreAudioManager() {
        audioManager?.let { am ->
            try {
                if (savedMode >= 0) am.mode = savedMode
                am.isSpeakerphoneOn = savedSpeakerphone
                am.isMicrophoneMute = savedMicMute
            } catch (_: Exception) {}
        }
        audioManager = null
        savedMode = -1
        preMuted = false
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        onLog?.invoke("[AUDIO] $message")
    }
}
