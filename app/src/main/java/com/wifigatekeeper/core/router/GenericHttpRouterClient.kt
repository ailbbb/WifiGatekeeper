package com.wifigatekeeper.core.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Tolerant client for routers with a standard web login. It uses HTTP Basic
 * auth and scans a list of common device-table paths. MAC filtering operations
 * are delegated to the TP-Link Classic client because many firmwares share the
 * /userRpm endpoint layout; when they do not, the failure is reported honestly.
 */
class GenericHttpRouterClient(
    baseUrl: String,
    username: String,
    password: String,
    socketFactory: javax.net.SocketFactory? = null,
) : RouterClient {

    override val kind = RouterKind.GENERIC

    private val base = baseUrl.trim().trimEnd('/').let {
        if (it.startsWith("http")) it else "http://$it"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .apply { socketFactory?.let { socketFactory(it) } }
        .build()

    private val basicHeader: String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    // MAC filtering is attempted through the classic TP-Link endpoints, which
    // several generic firmwares implement verbatim.
    private val classic = TpLinkClassicRouterClient(base, username, password)

    private val devicePaths = listOf(
        "/userRpm/AssignedIpAddrListRpm.htm",
        "/dhcp_table.html",
        "/status_lan.html",
        "/html/client_table.html",
        "/dhcpClientsTable.cgi",
        "/info.lan.html",
    )

    private fun request(path: String): Request = Request.Builder()
        .url("$base$path")
        .header("Authorization", basicHeader)
        .header("User-Agent", TpLinkClassicRouterClient.USER_AGENT)
        .build()

    private suspend fun getHtml(path: String): Result<String> =
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request(path)).execute().use { response ->
                    when {
                        response.code == 401 ->
                            Result.failure(RouterAuthException("Router rejected the credentials (HTTP 401)."))
                        !response.isSuccessful ->
                            Result.failure(RouterException("Router answered HTTP ${response.code} for $path."))
                        else -> Result.success(response.body?.string().orEmpty())
                    }
                }
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }

    private fun mapError(e: Throwable): RouterException = when {
        e is kotlinx.coroutines.CancellationException -> throw e
        e is RouterException -> e
        e is java.io.IOException ->
            RouterConnectionException("Could not reach the router: ${e.message ?: e.javaClass.simpleName}", e)
        else -> RouterException("Unexpected failure: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }

    private suspend fun discoverDevices(): List<DeviceScanEntry> {
        val entries = linkedMapOf<String, DeviceScanEntry>()
        for (path in devicePaths) {
            val html = getHtml(path).getOrElse { "" }
            if (html.isEmpty()) continue
            for (entry in RouterPageParser.parseDeviceEntriesFromTable(html)) {
                entries.putIfAbsent(entry.mac, entry)
            }
            for (entry in RouterPageParser.parseDeviceEntriesFromJsRows(RouterPageParser.parseJsArrayBlocks(html))) {
                entries.putIfAbsent(entry.mac, entry)
            }
            if (entries.isNotEmpty()) break
        }
        return entries.values.toList()
    }

    // ---------- RouterClient ----------

    override suspend fun probeConnection(): Result<Int> = guarded {
        // Hitting "/" with Basic auth doubles as the credential check: a wrong
        // password surfaces as HTTP 401, a right one as a real page.
        getHtml("/").getOrThrow()
        val devices = discoverDevices()
        devices.size
    }

    override suspend fun fetchConnectedDevices(): Result<List<DeviceScanEntry>> = guarded {
        val devices = discoverDevices()
        if (devices.isEmpty()) {
            throw RouterParseException(
                "Connected to the router, but the device table could not be parsed; " +
                    "this model may need a dedicated profile.",
            )
        }
        devices
    }

    override suspend fun fetchWhitelistedMacAddresses(): Result<List<String>> =
        classic.fetchWhitelistedMacAddresses()

    override suspend fun addToWhitelist(mac: String, label: String): Result<Unit> =
        classic.addToWhitelist(mac, label)

    override suspend fun removeFromWhitelist(mac: String): Result<Unit> =
        classic.removeFromWhitelist(mac)

    override suspend fun isMacFilteringEnabled(): Result<Boolean?> =
        classic.isMacFilteringEnabled()

    override suspend fun setMacFilteringEnabled(enabled: Boolean): Result<Unit> =
        classic.setMacFilteringEnabled(enabled)
}
