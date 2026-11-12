package com.wifigatekeeper.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Answers the crucial question: is the phone on the same LAN as the configured router? */
class LocalNetworkChecker(context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private data class Link(val isWifi: Boolean, val properties: LinkProperties?)

    private fun activeTransport(): Link? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val link = connectivity.getLinkProperties(network)
        return Link(isWifi, link)
    }

    /** True when the phone currently talks over Wi-Fi or Ethernet (not mobile data). */
    fun isOnWifi(): Boolean = activeTransport()?.isWifi == true

    /**
     * True when [routerIp] is the default gateway of the current Wi-Fi link or
     * shares its /24 subnet. Prevents the classic bug of trying to log in to
     * 192.168.1.1 from a mobile-data connection.
     */
    fun isOnSameNetworkAs(routerIp: String): Boolean {
        val (_, link) = activeTransport() ?: return false
        val linkProperties = link ?: return false

        // 1) Exact match: default gateway of the Wi-Fi link == configured router.
        val gateways = linkProperties.routes.mapNotNull { it.gateway?.hostAddress }
        if (gateways.any { it == routerIp }) return true

        // 2) Fallback: same /24 prefix as any local IPv4 address on that link.
        val routerPrefix = routerIp.substringBeforeLast('.')
        return linkProperties.linkAddresses.any { linkAddress ->
            val address = linkAddress.address
            address is Inet4Address &&
                address.hostAddress?.substringBeforeLast('.') == routerPrefix
        }
    }

    /** The default gateway of the current link, when discoverable (used to prefill setup). */
    fun currentGateway(): String? {
        val (_, link) = activeTransport() ?: return null
        val linkProperties = link ?: return null
        return linkProperties.routes
            .mapNotNull { it.gateway?.hostAddress }
            .firstOrNull { it?.contains(':') == false }
    }

    /** This phone's own IPv4 on the current Wi-Fi link (used to spot its MAC in scans). */
    fun currentLocalIp(): String? {
        val (_, link) = activeTransport() ?: return null
        val linkProperties = link ?: return null
        return linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?.address?.hostAddress
    }
}
