package com.copperhead.gateway.sip

import java.security.MessageDigest

/**
 * SIP Digest Authentication (RFC 2617).
 * Handles 401/407 challenge-response for Asterisk.
 */
object SipAuth {

    data class Challenge(
        val realm: String,
        val nonce: String,
        val algorithm: String = "MD5",
        val qop: String? = null,
        val opaque: String? = null
    )

    fun parseChallenge(header: String): Challenge {
        fun extract(param: String): String? {
            val regex = Regex("""$param\s*=\s*"?([^",]+)"?""", RegexOption.IGNORE_CASE)
            return regex.find(header)?.groupValues?.get(1)
        }

        return Challenge(
            realm = extract("realm") ?: "",
            nonce = extract("nonce") ?: "",
            algorithm = extract("algorithm") ?: "MD5",
            qop = extract("qop"),
            opaque = extract("opaque")
        )
    }

    fun buildResponse(
        challenge: Challenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        nc: String = "00000001",
        cnonce: String = SipMessage.newTag()
    ): String {
        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")

        val response = if (challenge.qop != null) {
            md5("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
        } else {
            md5("$ha1:${challenge.nonce}:$ha2")
        }

        return buildString {
            append("Digest ")
            append("username=\"$username\", ")
            append("realm=\"${challenge.realm}\", ")
            append("nonce=\"${challenge.nonce}\", ")
            append("uri=\"$uri\", ")
            if (challenge.qop != null) {
                append("qop=${challenge.qop}, ")
                append("nc=$nc, ")
                append("cnonce=\"$cnonce\", ")
            }
            append("response=\"$response\", ")
            append("algorithm=${challenge.algorithm}")
            if (challenge.opaque != null) {
                append(", opaque=\"${challenge.opaque}\"")
            }
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
