package com.copperhead.gateway.sip

/**
 * SIP account configuration for connecting to Asterisk.
 */
data class SipConfig(
    val displayName: String,
    val username: String,
    val password: String,
    val domain: String,
    val port: Int = 5060,
    val transport: String = "UDP",
    val registrationExpiry: Int = 3600,
    val localPort: Int = 0, // 0 = auto-assign
    val rtpPortBase: Int = 10000,
    val rtpPortRange: Int = 200,
    val stunServer: String? = null,
    val outboundProxy: String? = null,
    val simSlot: Int = -1 // -1 = any, 0 = SIM1, 1 = SIM2
) {
    val sipUri: String get() = "sip:$username@$domain"
    val registrarUri: String get() = "sip:$domain:$port"

    companion object {
        val EMPTY = SipConfig("", "", "", "")
    }
}
