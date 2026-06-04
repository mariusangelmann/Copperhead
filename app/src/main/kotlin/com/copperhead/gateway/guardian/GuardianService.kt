package com.copperhead.gateway.guardian

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log

/**
 * Runs in a SEPARATE process (:guardian, declared in AndroidManifest.xml).
 * Its only job is to detect the main Copperhead process dying — for any
 * reason at all, including native crash, SIGKILL, and the low-memory
 * killer — and immediately end the cellular call so the modem closes its
 * mic-to-uplink path.
 *
 * Why a second process: a JVM-level uncaught-exception handler in the
 * main process doesn't run for native crashes (SIGSEGV/SIGABRT in JNI
 * code like the G.722 codec) and obviously can't run if the OS itself
 * sends SIGKILL. The kernel binder driver, however, notifies linked
 * death recipients whenever the owning process disappears, regardless
 * of how.
 */
class GuardianService : Service() {
    companion object {
        private const val TAG = "GuardianService"
    }

    @Volatile private var hasActiveCall = false

    private val deathRecipient = IBinder.DeathRecipient { onMainProcessDied() }

    private val binder = object : IGuardian.Stub() {
        override fun startWatching(anchor: IBinder) {
            try {
                anchor.linkToDeath(deathRecipient, 0)
                Log.i(TAG, "Watching main process via anchor binder")
            } catch (e: Exception) {
                Log.e(TAG, "linkToDeath failed", e)
            }
        }

        override fun setHasActiveCall(active: Boolean) {
            hasActiveCall = active
            Log.i(TAG, "Active bridge = $active")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun onMainProcessDied() {
        Log.e(TAG, "Main process died. hasActiveCall=$hasActiveCall")
        if (!hasActiveCall) return

        // Layer 1: mute the mic immediately. Even if endCall races or
        // fails, this closes the room-audio path within microseconds.
        try {
            val am = getSystemService(AudioManager::class.java)
            am?.isMicrophoneMute = true
            am?.mode = AudioManager.MODE_NORMAL
            Log.w(TAG, "Mic muted, mode → NORMAL")
        } catch (t: Throwable) {
            Log.e(TAG, "Mic mute failed", t)
        }

        // Layer 2: end the active call via Telecom. Permission requirement
        // (ANSWER_PHONE_CALLS / MODIFY_PHONE_STATE) is declared in the
        // manifest and granted via priv-app status (Magisk module).
        try {
            val tm = getSystemService(TelecomManager::class.java)
            val ended = tm?.endCall() ?: false
            Log.w(TAG, "TelecomManager.endCall() = $ended")
        } catch (e: SecurityException) {
            Log.e(TAG, "endCall denied — check ANSWER_PHONE_CALLS / priv-app", e)
        } catch (t: Throwable) {
            Log.e(TAG, "endCall failed", t)
        }

        hasActiveCall = false
    }
}
