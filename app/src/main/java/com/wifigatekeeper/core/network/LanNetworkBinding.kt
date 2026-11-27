package com.wifigatekeeper.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import javax.net.SocketFactory

/**
 * Resolves the socket factory of the ACTIVE Wi-Fi (or Ethernet) network so
 * router HTTP requests are pinned to the LAN transport.
 *
 * Why this exists: when a phone has both Wi-Fi and mobile data, Android is
 * free to route any socket wherever it likes - requests aimed at 192.168.x.x
 * have been observed leaving over cellular, which makes the app report
 * "Could not reach the router" while the user is clearly on the Wi-Fi.
 * Binding every OkHttp client to the Wi-Fi Network's socket factory removes
 * that whole failure class. When no Wi-Fi/Ethernet transport is up, null is
 * returned and callers fall back to the default routing.
 */
class LanNetworkBinding(context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The active Wi-Fi or Ethernet [Network], preferring Wi-Fi, or null. */
    fun activeLanNetwork(): Network? {
        val networks = connectivity.allNetworks
        val lan = networks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        // Wi-Fi first, Ethernet second - a phone will rarely have both.
        return lan.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: lan.firstOrNull()
    }

    /**
     * Socket factory that guarantees LAN-bound traffic stays on the Wi-Fi
     * transport, or null when no LAN transport is currently available.
     */
    fun wifiSocketFactory(): SocketFactory? = activeLanNetwork()?.socketFactory
}
