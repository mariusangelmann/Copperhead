package com.copperhead.gateway.sip

/**
 * In-band DTMF (touch-tone) detector using the Goertzel algorithm.
 *
 * Why this exists: the remote cellular caller's key presses arrive only as
 * in-band audio tones in the GSM downlink. FreePBX/Asterisk by default expect
 * DTMF as out-of-band RFC 2833 `telephone-event` RTP packets and do NOT sniff
 * the audio — and our default G.722 codec mangles the tones anyway. So we
 * detect the tones here, frame by frame, and re-emit them as RFC 2833 via
 * [RtpStream] (driven by [onStart]/[onUpdate]/[onEnd]).
 *
 * Detection is intentionally conservative: a false positive injects a phantom
 * digit into the caller's IVR session, which is worse than missing a marginal
 * press. We require two dominant narrowband tones (one low-group, one
 * high-group) that together hold most of the frame's energy, stable for at
 * least [CONFIRM_BLOCKS] frames (~40 ms, the DTMF minimum tone duration).
 *
 * [process] is called once per captured ~20 ms PCM frame, on the audio capture
 * thread, BEFORE the frame is forwarded to RTP — its return value tells the
 * caller whether to blank the frame (so the tone isn't also sent in-band).
 */
class DtmfDetector(private val sampleRate: Int) {

    companion object {
        // DTMF row (low group) and column (high group) frequencies in Hz.
        private val LOW_FREQS = intArrayOf(697, 770, 852, 941)
        private val HIGH_FREQS = intArrayOf(1209, 1336, 1477, 1633)
        // keypad[lowIndex][highIndex]
        private val KEYPAD = arrayOf(
            charArrayOf('1', '2', '3', 'A'),
            charArrayOf('4', '5', '6', 'B'),
            charArrayOf('7', '8', '9', 'C'),
            charArrayOf('*', '0', '#', 'D'),
        )

        // Consecutive matching frames required before a digit is declared
        // "pressed". 2 frames ≈ 40 ms = the DTMF minimum valid duration, and
        // debounces transients/speech that momentarily look tone-like.
        // Capture frame duration (codec ptime), used to approximate press length.
        private const val PTIME_MS = 20

        private const val CONFIRM_BLOCKS = 2
        // Frames of absence tolerated before a held digit is declared released.
        // Absorbs brief energy dips mid-press without splitting one key into two.
        private const val RELEASE_BLOCKS = 1

        // Minimum mean per-sample energy (samples normalised to [-1,1]) for a
        // frame to be considered at all. This is only a silence gate — the real
        // DTMF discrimination is the dominance/twist/concentration tests below —
        // so it's set low enough to catch an attenuated downlink tone while
        // still excluding comfort-noise.
        private const val MIN_FRAME_ENERGY = 2.0e-4
        // The winning tone in each group must exceed the runner-up by this
        // factor — a clean single tone, not broadband speech with many partials.
        private const val GROUP_DOMINANCE = 4.0
        // The two winning tones together must hold at least this fraction of the
        // energy across all eight DTMF bins.
        private const val ENERGY_CONCENTRATION = 0.45
        // Allowed level imbalance between the low and high tone ("twist"),
        // expressed as a power ratio (~±10 dB). Rejects pairs where one tone is
        // really just a speech formant near a DTMF frequency.
        private const val MAX_TWIST = 10.0
    }

    /** Fired when a new digit is confirmed pressed. */
    var onStart: ((Char) -> Unit)? = null
    /** Fired once per frame while a digit is held (after [onStart]). */
    var onUpdate: (() -> Unit)? = null
    /** Fired when a held digit is released, with its total duration in ms. */
    var onEnd: ((Char, Int) -> Unit)? = null

    // Generalised-Goertzel coefficients (2·cos ω) for each target frequency.
    private val coeffLow = DoubleArray(4) { 2.0 * Math.cos(2.0 * Math.PI * LOW_FREQS[it] / sampleRate) }
    private val coeffHigh = DoubleArray(4) { 2.0 * Math.cos(2.0 * Math.PI * HIGH_FREQS[it] / sampleRate) }

    // State machine.
    private var heldDigit: Char = NONE      // currently-pressed digit, or NONE
    private var candidate: Char = NONE       // digit accumulating toward confirmation
    private var matchCount = 0               // consecutive frames matching `candidate`
    private var missCount = 0                // consecutive frames missing `heldDigit`
    private var activeFrames = 0             // frames the current press has lasted

    /**
     * Feed one PCM frame. Returns true if the frame is part of an active (or
     * forming) DTMF tone and should be blanked from the forwarded audio.
     */
    fun process(samples: ShortArray, count: Int): Boolean {
        val digit = detect(samples, count)

        if (digit != NONE) {
            if (digit == heldDigit) {
                // Same key still down.
                activeFrames++
                missCount = 0
                onUpdate?.invoke()
                return true
            }
            if (heldDigit != NONE) {
                // A different tone appeared while one was held: release the old
                // one immediately, then start counting toward the new one.
                emitEnd()
            }
            if (digit == candidate) matchCount++ else { candidate = digit; matchCount = 1 }
            if (matchCount >= CONFIRM_BLOCKS) {
                heldDigit = digit
                activeFrames = matchCount
                missCount = 0
                candidate = NONE
                onStart?.invoke(heldDigit)
            }
            // Blank from the very first candidate frame so no in-band tone leaks,
            // even before confirmation.
            return true
        }

        // No tone this frame.
        if (heldDigit != NONE) {
            missCount++
            if (missCount > RELEASE_BLOCKS) {
                emitEnd()
                return false
            }
            // Brief dip — treat as still held to avoid splitting the press.
            activeFrames++
            onUpdate?.invoke()
            return true
        }
        candidate = NONE
        matchCount = 0
        return false
    }

    /** Force-release any held digit (call on teardown). */
    fun reset() {
        if (heldDigit != NONE) emitEnd()
        candidate = NONE
        matchCount = 0
        missCount = 0
        activeFrames = 0
    }

    private fun emitEnd() {
        // Frames are captured at the codec's 20 ms ptime, so duration ≈ frames × 20 ms.
        onEnd?.invoke(heldDigit, activeFrames * PTIME_MS)
        heldDigit = NONE
        activeFrames = 0
        missCount = 0
    }

    /** Returns the decoded DTMF digit for this frame, or [NONE]. */
    private fun detect(samples: ShortArray, count: Int): Char {
        if (count <= 0) return NONE

        var energy = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0
            energy += s * s
        }
        if (energy / count < MIN_FRAME_ENERGY) return NONE

        val low = DoubleArray(4) { goertzel(samples, count, coeffLow[it]) }
        val high = DoubleArray(4) { goertzel(samples, count, coeffHigh[it]) }

        val li = strongest(low)
        val hi = strongest(high)

        // Each group's winner must dominate its runner-up.
        if (low[li] < GROUP_DOMINANCE * secondStrongest(low, li)) return NONE
        if (high[hi] < GROUP_DOMINANCE * secondStrongest(high, hi)) return NONE

        // Twist: the two tones must be within ~10 dB of each other.
        val twist = if (high[hi] > 0) low[li] / high[hi] else Double.MAX_VALUE
        if (twist > MAX_TWIST || twist < 1.0 / MAX_TWIST) return NONE

        // The two tones must hold most of the energy across all eight bins.
        var total = 0.0
        for (p in low) total += p
        for (p in high) total += p
        if (total <= 0.0) return NONE
        if ((low[li] + high[hi]) < ENERGY_CONCENTRATION * total) return NONE

        return KEYPAD[li][hi]
    }

    /**
     * Generalised Goertzel power for one target frequency over [count] samples.
     * Returns a scale-relative power (samples normalised to [-1,1]); only used
     * in ratios, so the constant factor is irrelevant.
     */
    private fun goertzel(samples: ShortArray, count: Int, coeff: Double): Double {
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun strongest(p: DoubleArray): Int {
        var idx = 0
        for (i in 1 until p.size) if (p[i] > p[idx]) idx = i
        return idx
    }

    private fun secondStrongest(p: DoubleArray, exclude: Int): Double {
        var max = 0.0
        for (i in p.indices) if (i != exclude && p[i] > max) max = p[i]
        return max
    }
}

private const val NONE = ' '

/** Maps a DTMF digit character to its RFC 2833 event code (0-15). */
fun dtmfEventCode(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    '*' -> 10
    '#' -> 11
    in 'A'..'D' -> 12 + (c - 'A')
    else -> -1
}
