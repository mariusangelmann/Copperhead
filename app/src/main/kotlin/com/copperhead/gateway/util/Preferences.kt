package com.copperhead.gateway.util

import android.content.Context
import android.content.SharedPreferences
import com.copperhead.gateway.sip.SipConfig

/**
 * Manages gateway preferences and SIP account configuration.
 * Supports up to 2 SIP accounts (one per SIM slot).
 */
class Preferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "copperhead_gateway"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_FORWARD_INCOMING_CALLS = "forward_incoming_calls"
        private const val KEY_ACCOUNT_COUNT = "account_count"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SPEAKERPHONE_LOOPBACK = "speakerphone_loopback"
        private const val KEY_FORWARD_EXTENSION = "forward_extension"
        private const val KEY_MUTE_DEVICE_MIC = "mute_device_mic"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    var forwardIncomingCalls: Boolean
        get() = prefs.getBoolean(KEY_FORWARD_INCOMING_CALLS, true)
        set(value) = prefs.edit().putBoolean(KEY_FORWARD_INCOMING_CALLS, value).apply()

    /**
     * Acoustic loopback mode for audio bridging. When ON, speakerphone is
     * forced and SIP audio is played loud out of the speaker so the cellular
     * call's mic can pick it up acoustically. Use this on devices where the
     * audio HAL doesn't route STREAM_VOICE_CALL into the cellular uplink
     * (i.e. most non-rooted devices). Trade-off: bad quality, echo.
     */
    var speakerphoneLoopback: Boolean
        get() = prefs.getBoolean(KEY_SPEAKERPHONE_LOOPBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEAKERPHONE_LOOPBACK, value).apply()

    /**
     * SIP extension to which incoming GSM calls/SMS are forwarded. This goes
     * into the INVITE/MESSAGE Request-URI + To header (e.g. "101"). Without
     * this, FreePBX would interpret the GSM caller's number as the dial
     * target and try to route outbound — usually hitting "All circuits busy".
     */
    var forwardExtension: String
        get() = prefs.getString(KEY_FORWARD_EXTENSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FORWARD_EXTENSION, value).apply()

    /**
     * Mute the device's physical microphone during a bridged call so the
     * cellular caller hears ONLY what we inject via SIP (e.g. FreePBX
     * announcements), not ambient room noise. Default: true (gateway mode).
     * Turn off only if the device is used as an actual handset where someone
     * is speaking into it.
     */
    var muteDeviceMic: Boolean
        get() = prefs.getBoolean(KEY_MUTE_DEVICE_MIC, true)
        set(value) = prefs.edit().putBoolean(KEY_MUTE_DEVICE_MIC, value).apply()

    fun getSipConfigs(): List<SipConfig> {
        val count = prefs.getInt(KEY_ACCOUNT_COUNT, 0)
        return (0 until count).mapNotNull { getSipConfig(it) }.filter { it.username.isNotBlank() }
    }

    fun getSipConfig(index: Int): SipConfig? {
        val prefix = "sip_${index}_"
        val username = prefs.getString("${prefix}username", null) ?: return null
        if (username.isBlank()) return null

        return SipConfig(
            displayName = prefs.getString("${prefix}display_name", username) ?: username,
            username = username,
            password = prefs.getString("${prefix}password", "") ?: "",
            domain = prefs.getString("${prefix}domain", "") ?: "",
            port = prefs.getInt("${prefix}port", 5060),
            transport = prefs.getString("${prefix}transport", "UDP") ?: "UDP",
            registrationExpiry = prefs.getInt("${prefix}reg_expiry", 3600),
            localPort = prefs.getInt("${prefix}local_port", 0),
            rtpPortBase = prefs.getInt("${prefix}rtp_port_base", 10000),
            rtpPortRange = prefs.getInt("${prefix}rtp_port_range", 200),
            stunServer = prefs.getString("${prefix}stun", null),
            outboundProxy = prefs.getString("${prefix}proxy", null),
            simSlot = prefs.getInt("${prefix}sim_slot", -1)
        )
    }

    fun saveSipConfig(index: Int, config: SipConfig) {
        val prefix = "sip_${index}_"
        prefs.edit()
            .putString("${prefix}display_name", config.displayName)
            .putString("${prefix}username", config.username)
            .putString("${prefix}password", config.password)
            .putString("${prefix}domain", config.domain)
            .putInt("${prefix}port", config.port)
            .putString("${prefix}transport", config.transport)
            .putInt("${prefix}reg_expiry", config.registrationExpiry)
            .putInt("${prefix}local_port", config.localPort)
            .putInt("${prefix}rtp_port_base", config.rtpPortBase)
            .putInt("${prefix}rtp_port_range", config.rtpPortRange)
            .putString("${prefix}stun", config.stunServer)
            .putString("${prefix}proxy", config.outboundProxy)
            .putInt("${prefix}sim_slot", config.simSlot)
            .apply()

        // Update account count
        val currentCount = prefs.getInt(KEY_ACCOUNT_COUNT, 0)
        if (index >= currentCount) {
            prefs.edit().putInt(KEY_ACCOUNT_COUNT, index + 1).apply()
        }
    }

    fun removeSipConfig(index: Int) {
        val prefix = "sip_${index}_"
        prefs.edit()
            .remove("${prefix}display_name")
            .remove("${prefix}username")
            .remove("${prefix}password")
            .remove("${prefix}domain")
            .remove("${prefix}port")
            .remove("${prefix}transport")
            .remove("${prefix}reg_expiry")
            .remove("${prefix}local_port")
            .remove("${prefix}rtp_port_base")
            .remove("${prefix}rtp_port_range")
            .remove("${prefix}stun")
            .remove("${prefix}proxy")
            .remove("${prefix}sim_slot")
            .apply()
    }
}
