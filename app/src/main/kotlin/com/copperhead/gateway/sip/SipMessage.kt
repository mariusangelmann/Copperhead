package com.copperhead.gateway.sip

import java.util.UUID

/**
 * SIP message parser and builder.
 * Handles both requests (REGISTER, INVITE, BYE, etc.) and responses (200 OK, etc.)
 */
class SipMessage private constructor(
    val isRequest: Boolean,
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val headers: MutableMap<String, MutableList<String>>,
    var body: String?
) {
    val callId: String? get() = getHeader("Call-ID")
    val from: String? get() = getHeader("From")
    val to: String? get() = getHeader("To")
    val cseq: String? get() = getHeader("CSeq")
    val via: List<String> get() = getHeaders("Via")
    val contact: String? get() = getHeader("Contact")
    val contentType: String? get() = getHeader("Content-Type")
    val contentLength: Int get() = getHeader("Content-Length")?.toIntOrNull() ?: 0

    val fromTag: String? get() = extractParam(from, "tag")
    val toTag: String? get() = extractParam(to, "tag")
    val branchId: String? get() = extractParam(via.firstOrNull(), "branch")

    fun getHeader(name: String): String? {
        val key = headers.keys.find { it.equals(name, ignoreCase = true) }
        return key?.let { headers[it]?.firstOrNull() }
    }

    fun getHeaders(name: String): List<String> {
        val key = headers.keys.find { it.equals(name, ignoreCase = true) }
        return key?.let { headers[it] } ?: emptyList()
    }

    fun setHeader(name: String, value: String) {
        headers[name] = mutableListOf(value)
    }

    fun addHeader(name: String, value: String) {
        headers.getOrPut(name) { mutableListOf() }.add(value)
    }

    override fun toString(): String = buildString {
        if (isRequest) {
            appendLine("$method $requestUri SIP/2.0")
        } else {
            appendLine("SIP/2.0 $statusCode $reasonPhrase")
        }
        for ((name, values) in headers) {
            for (v in values) {
                appendLine("$name: $v")
            }
        }
        val b = body ?: ""
        if (b.isNotEmpty()) {
            // Ensure Content-Length is correct
            appendLine("Content-Length: ${b.toByteArray(Charsets.UTF_8).size}")
            appendLine()
            append(b)
        } else {
            appendLine("Content-Length: 0")
            appendLine()
        }
    }

    fun toByteArray(): ByteArray = toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(data: String): SipMessage {
            val lines = data.split("\r\n", "\n")
            if (lines.isEmpty()) throw IllegalArgumentException("Empty SIP message")

            val firstLine = lines[0].trim()
            val isRequest: Boolean
            var method: String? = null
            var requestUri: String? = null
            var statusCode: Int? = null
            var reasonPhrase: String? = null

            if (firstLine.startsWith("SIP/2.0")) {
                isRequest = false
                val parts = firstLine.split(" ", limit = 3)
                statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
                reasonPhrase = parts.getOrNull(2) ?: ""
            } else {
                isRequest = true
                val parts = firstLine.split(" ", limit = 3)
                method = parts.getOrNull(0)
                requestUri = parts.getOrNull(1)
            }

            val headers = mutableMapOf<String, MutableList<String>>()
            var bodyStart = -1

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) {
                    bodyStart = i + 1
                    break
                }
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val name = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers.getOrPut(name) { mutableListOf() }.add(value)
                }
            }

            val body = if (bodyStart > 0 && bodyStart < lines.size) {
                lines.subList(bodyStart, lines.size).joinToString("\r\n").trimEnd('\u0000')
            } else null

            return SipMessage(isRequest, method, requestUri, statusCode, reasonPhrase, headers, body)
        }

        fun parse(data: ByteArray): SipMessage = parse(String(data, Charsets.UTF_8))

        fun newBranch(): String = "z9hG4bK-${UUID.randomUUID().toString().take(12)}"
        fun newTag(): String = UUID.randomUUID().toString().take(8)
        fun newCallId(): String = "${UUID.randomUUID().toString().take(16)}@copperhead"
    }

    class Builder {
        private var isRequest = true
        private var method: String? = null
        private var requestUri: String? = null
        private var statusCode: Int? = null
        private var reasonPhrase: String? = null
        private val headers = mutableMapOf<String, MutableList<String>>()
        private var body: String? = null

        fun request(method: String, uri: String) = apply {
            this.isRequest = true
            this.method = method
            this.requestUri = uri
        }

        fun response(code: Int, phrase: String) = apply {
            this.isRequest = false
            this.statusCode = code
            this.reasonPhrase = phrase
        }

        fun header(name: String, value: String) = apply {
            headers[name] = mutableListOf(value)
        }

        fun addHeader(name: String, value: String) = apply {
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }

        fun via(host: String, port: Int, branch: String = newBranch(), transport: String = "UDP") = apply {
            addHeader("Via", "SIP/2.0/$transport $host:$port;branch=$branch;rport")
        }

        fun from(displayName: String?, uri: String, tag: String = newTag()) = apply {
            val v = if (displayName != null) "\"$displayName\" <$uri>;tag=$tag" else "<$uri>;tag=$tag"
            header("From", v)
        }

        fun to(displayName: String?, uri: String, tag: String? = null) = apply {
            val v = buildString {
                if (displayName != null) append("\"$displayName\" ")
                append("<$uri>")
                if (tag != null) append(";tag=$tag")
            }
            header("To", v)
        }

        fun callId(id: String = newCallId()) = apply { header("Call-ID", id) }
        fun cseq(seq: Int, method: String) = apply { header("CSeq", "$seq $method") }
        fun contact(uri: String) = apply { header("Contact", "<$uri>") }
        fun maxForwards(n: Int = 70) = apply { header("Max-Forwards", n.toString()) }
        fun userAgent(ua: String = "Copperhead/1.0") = apply { header("User-Agent", ua) }
        fun allow() = apply { header("Allow", "INVITE, ACK, BYE, CANCEL, REGISTER, MESSAGE, OPTIONS, INFO") }

        fun contentType(type: String) = apply { header("Content-Type", type) }
        fun body(content: String) = apply { this.body = content }
        fun expires(seconds: Int) = apply { header("Expires", seconds.toString()) }

        fun authorization(value: String) = apply { header("Authorization", value) }
        fun proxyAuthorization(value: String) = apply { header("Proxy-Authorization", value) }

        /**
         * RFC 3325 P-Asserted-Identity — the proper way to convey verified
         * caller-ID through a trusted SIP trunk (e.g. a registered
         * extension forwarding inbound calls into the PBX).
         */
        fun pAssertedIdentity(uri: String, displayName: String? = null) = apply {
            val v = if (displayName != null) "\"$displayName\" <$uri>" else "<$uri>"
            header("P-Asserted-Identity", v)
        }

        /**
         * Cisco Remote-Party-ID — older but still widely accepted (FreePBX,
         * many SBCs). Belt-and-suspenders when interoperating with
         * pre-RFC-3325 stacks.
         */
        fun remotePartyId(uri: String, displayName: String? = null) = apply {
            val name = displayName ?: ""
            header(
                "Remote-Party-ID",
                "\"$name\" <$uri>;party=calling;screen=yes;privacy=off"
            )
        }

        fun build(): SipMessage = SipMessage(isRequest, method, requestUri, statusCode, reasonPhrase, headers, body)
    }
}

private fun extractParam(header: String?, param: String): String? {
    if (header == null) return null
    val regex = Regex("""$param=([^;,>\s]+)""")
    return regex.find(header)?.groupValues?.get(1)
}

fun extractUri(header: String?): String? {
    if (header == null) return null
    val start = header.indexOf('<')
    val end = header.indexOf('>')
    return if (start >= 0 && end > start) header.substring(start + 1, end) else null
}

fun extractUserFromUri(uri: String?): String? {
    if (uri == null) return null
    // sip:user@host or sip:user@host:port
    val sipPrefix = if (uri.startsWith("sip:")) 4 else if (uri.startsWith("sips:")) 5 else 0
    val userPart = uri.substring(sipPrefix)
    val atIndex = userPart.indexOf('@')
    return if (atIndex > 0) userPart.substring(0, atIndex) else userPart
}
