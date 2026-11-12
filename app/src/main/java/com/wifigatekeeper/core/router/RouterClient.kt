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
    fun create(kind: RouterKind, baseUrl: String, username: String, password: String): RouterClient {
        return when (kind) {
            RouterKind.TP_LINK_CLASSIC -> TpLinkClassicRouterClient(baseUrl, username, password)
            RouterKind.GENERIC -> GenericHttpRouterClient(baseUrl, username, password)
        }
    }
}
