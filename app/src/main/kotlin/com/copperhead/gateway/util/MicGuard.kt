package com.copperhead.gateway.util

import android.content.Context
import android.hardware.SensorPrivacyManager
import android.os.Build
import android.util.Log

/**
 * Thin wrapper around the system mic sensor-privacy toggle (Android 12+).
 *
 * Engaging it sets the same state that the Quick Settings "Mic access" tile
 * controls — system-wide hard mute that:
 *   - applies to every app (cellular calls, voice assistants, recorders…)
 *   - persists across reboots and across Copperhead being killed
 *   - on supported Pixels engages a hardware-level disconnect at the audio
 *     codec, so even a kernel-side mic tap reads silence
 *
 * Caveats the user must understand:
 *   - The user can flip it back off via Quick Settings at any time. This is
 *     a defence against background software, NOT against the person
 *     physically holding the phone.
 *   - Emergency calls (911 / 112 / 110 / 119) bypass it on stock AOSP, so
 *     the dispatcher can still hear the caller.
 *   - Any app that tries to capture mic while engaged gets a system dialog
 *     telling the user the mic is blocked, which can prompt them to unblock.
 *
 * The read/write methods (`isSensorPrivacyEnabled`, `setSensorPrivacy`) are
 * marked `@SystemApi` in the platform — accessible at runtime but absent
 * from the public SDK jar, so we go through reflection. `supportsSensorToggle`
 * is public and called directly.
 *
 * Requires the signature|privileged permission MANAGE_SENSOR_PRIVACY,
 * granted via the Magisk privapp-permissions allowlist.
 */
object MicGuard {
    private const val TAG = "MicGuard"

    /** True if the runtime supports the API and the device exposes it. */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            context.getSystemService(SensorPrivacyManager::class.java)
                ?.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE) == true
        } catch (t: Throwable) {
            Log.w(TAG, "supportsSensorToggle threw", t)
            false
        }
    }

    /** Current system-wide mic sensor-privacy state. */
    fun isBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val mgr = context.getSystemService(SensorPrivacyManager::class.java) ?: return false
        return try {
            val method = mgr.javaClass.getMethod(
                "isSensorPrivacyEnabled",
                Int::class.javaPrimitiveType
            )
            method.invoke(mgr, SensorPrivacyManager.Sensors.MICROPHONE) as? Boolean ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "isSensorPrivacyEnabled reflective call failed", t)
            false
        }
    }

    /**
     * Set the system-wide mic sensor-privacy state. Returns true on success.
     * Fails (returns false, logs reason) if the permission isn't granted or
     * the device doesn't support the toggle.
     */
    fun setBlocked(context: Context, blocked: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "setBlocked: needs Android 12+; current API ${Build.VERSION.SDK_INT}")
            return false
        }
        val mgr = context.getSystemService(SensorPrivacyManager::class.java) ?: return false
        return try {
            val method = mgr.javaClass.getMethod(
                "setSensorPrivacy",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(mgr, SensorPrivacyManager.Sensors.MICROPHONE, blocked)
            Log.i(TAG, "Mic sensor-privacy set to $blocked")
            true
        } catch (e: ReflectiveOperationException) {
            val cause = e.cause
            if (cause is SecurityException) {
                Log.e(TAG, "setSensorPrivacy denied — MANAGE_SENSOR_PRIVACY not granted (priv-app install needed)", cause)
            } else {
                Log.e(TAG, "setSensorPrivacy reflective invoke failed", e)
            }
            false
        } catch (t: Throwable) {
            Log.e(TAG, "setSensorPrivacy failed", t)
            false
        }
    }
}
