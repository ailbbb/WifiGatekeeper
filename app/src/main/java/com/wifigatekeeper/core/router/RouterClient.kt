package com.wifigatekeeper.core.router

/** Common contract implemented by every router UI client. */
interface RouterClient {
    val kind: RouterKind

    /** Confirms the credentials work; returns the number of devices discovered. */
    suspend fun probeConnection(): Result<Int>

    suspend fun fetchConnectedDevices(): Result<List<DeviceScanEntry>>
    suspend fun fetchWhitelistedMacAddresses(): Result<List<String>>
    suspend fun addToWhitelist(mac: String, label: String): Result<Unit>
    suspend fun removeFromWhitelist(mac: String): Result<Unit>
    suspend fun isMacFilteringEnabled(): Result<Boolean?>
    suspend fun setMacFilteringEnabled(enabled: Boolean): Result<Unit>
}

/** Builds the concrete client for the selected router kind. */
object RouterClientFactory {
    /**
     * [socketFactory] pins every connection to the Wi-Fi transport when the
     * phone also has mobile data (see [com.wifigatekeeper.core.network.LanNetworkBinding]);
     * null keeps OkHttp's default routing.
     */
    fun create(
        kind: RouterKind,
        baseUrl: String,
        username: String,
        password: String,
        socketFactory: javax.net.SocketFactory? = null,
    ): RouterClient {
        return when (kind) {
            RouterKind.TP_LINK_CLASSIC ->
                TpLinkClassicRouterClient(baseUrl, username, password, socketFactory)
            RouterKind.TP_LINK_NEW ->
                TpLinkNewRouterClient(baseUrl, username, password, socketFactory)
            RouterKind.GENERIC ->
                GenericHttpRouterClient(baseUrl, username, password, socketFactory)
        }
    }
}
