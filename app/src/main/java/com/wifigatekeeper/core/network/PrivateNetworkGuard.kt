package com.wifigatekeeper.core.network

/**
 * Decides whether a host is a private LAN address. The quick-link browser may
 * only load and observe pages on the local network - a credential hook that
 * also fired on internet sites would be a phishing primitive, so anything that
 * is not RFC 1918 (or localhost / .local) fails closed.
 */
object PrivateNetworkGuard {

    /** True when [host] is RFC 1918, localhost, or a `.local` mDNS name. */
    fun isPrivateLanHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false

        // Defensive cleanup: hosts normally arrive bare (Uri.host, auth
        // callback), but tolerate a full URL or a host:port string too.
        val candidate = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trimEnd('.')

        if (candidate.equals("localhost", ignoreCase = true)) return true
        if (candidate.endsWith(".local", ignoreCase = true) && candidate.length > 6) return true

        val parts = candidate.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false

        val (a, b) = octets
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }
}
