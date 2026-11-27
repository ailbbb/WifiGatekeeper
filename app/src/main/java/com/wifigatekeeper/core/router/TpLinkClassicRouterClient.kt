package com.wifigatekeeper.core.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Client for the classic TP-Link interface used by the WR940N / WR841N family:
 * HTTP Basic authentication on every request plus the classic userRpm page set.
 *
 * The generic form-login client cannot talk to these routers at all - they do
 * not render an HTML login form, they answer every unauthenticated request with
 * HTTP 401. That mismatch is exactly why the old build reported
 * "router rejected the credentials" on a WR940N with correct credentials.
 */
class TpLinkClassicRouterClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    socketFactory: javax.net.SocketFactory? = null,
) : RouterClient {

    override val kind = RouterKind.TP_LINK_CLASSIC

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

    private fun url(path: String) = "$base$path"

    private fun request(path: String, referer: String? = null): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("Authorization", basicHeader)
            .header("User-Agent", USER_AGENT)
        // Classic firmware silently rejects state-changing requests (and some
        // plain reads) unless the Referer matches the gateway page it issued
        // them from - send it on every request, not only on POSTs.
        builder.header("Referer", referer ?: url(path))
        return builder.build()
    }

    /** GETs a page, mapping 401 to a clear auth failure. */
    private suspend fun getHtml(path: String, referer: String? = null): Result<String> =
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request(path, referer)).execute().use { response ->
                    when {
                        response.code == 401 ->
                            Result.failure(RouterAuthException("Router rejected the credentials (HTTP 401)."))
                        !response.isSuccessful ->
                            Result.failure(RouterException("Router answered HTTP ${response.code} for $path."))
                        else ->
                            Result.success(response.body?.string().orEmpty())
                    }
                }
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }

    /** POSTs form fields, mapping 401 / HTTP errors to failures. */
    private suspend fun postForm(path: String, fields: Map<String, String>): Result<Unit> =
        try {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder().apply {
                    for ((name, value) in fields) add(name, value)
                }.build()
                val post = Request.Builder()
                    .url(url(path))
                    .header("Authorization", basicHeader)
                    .header("Referer", url(path))
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()
                http.newCall(post).execute().use { response ->
                    when {
                        response.code == 401 ->
                            Result.failure(RouterAuthException("Router rejected the credentials (HTTP 401)."))
                        !response.isSuccessful ->
                            Result.failure(RouterException("Router answered HTTP ${response.code} for $path."))
                        else -> Result.success(Unit)
                    }
                }
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }

    /**
     * Classic userRpm pages are GET-driven (query parameters carry the form
     * fields); POST stays as a fallback for firmwares that want a body. Trying
     * GET first avoids the "HTTP 500" the old POST-only path produced on
     * several firmware revisions.
     */
    private suspend fun saveWithQuery(path: String, fields: Map<String, String>): Result<Unit> {
        val query = fields.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        getHtml("$path?$query").fold(
            onSuccess = { return Result.success(Unit) },
            onFailure = { },
        )
        return postForm(path, fields)
    }

    private fun mapError(e: Throwable): RouterException = when {
        // Cancelling a poll is not a router failure - rethrow so structured
        // concurrency works and no bogus "Could not reach the router" is logged.
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

    // ---------- RouterClient ----------

    override suspend fun probeConnection(): Result<Int> {
        val login = getHtml(LOGIN_PATH)
        if (login.isFailure) {
            return Result.failure(login.exceptionOrNull() ?: RouterException("Login failed."))
        }
        // The filter page is what DISTINGUISHES this family from the newer
        // WlanMacFilterRpm firmware: login + DHCP pages exist on both, so the
        // probe must confirm the classic filter page really answers.
        val filterPage = getHtml(MAC_FILTER_PATH)
        if (filterPage.isFailure) {
            return Result.failure(
                RouterException("No classic WlanAccessRpm page - try the TP-Link New profile."),
            )
        }
        return fetchConnectedDevices().map { it.size }
    }

    override suspend fun fetchConnectedDevices(): Result<List<DeviceScanEntry>> = guarded {
        // DHCP lease table (wired + wireless clients) is the authoritative source;
        // the wireless station list is a bonus and may be missing on some firmwares.
        val dhcpHtml = getHtml(DHCP_LIST_PATH).getOrThrow()
        val wlanHtml = getHtml(WLAN_STATIONS_PATH).getOrElse { "" }

        val entries = linkedMapOf<String, DeviceScanEntry>()
        for (html in listOf(dhcpHtml, wlanHtml)) {
            if (html.isEmpty()) continue
            val rows = RouterPageParser.parseJsArrayBlocks(html)
            for (entry in RouterPageParser.parseDeviceEntriesFromJsRows(rows)) {
                entries.putIfAbsent(entry.mac, entry)
            }
        }
        entries.values.toList()
    }

    override suspend fun fetchWhitelistedMacAddresses(): Result<List<String>> = guarded {
        RouterPageParser.parseWhitelistedMacs(getHtml(MAC_FILTER_PATH).getOrThrow())
    }

    override suspend fun addToWhitelist(mac: String, label: String): Result<Unit> {
        val normalised = MacAddressValidator.normalise(mac)
            ?: return Result.failure(RouterException("Invalid MAC address: $mac"))

        return guarded {
            val add = saveWithQuery(
                MAC_FILTER_PATH,
                mapOf(
                    "wlanAcEntry_mac" to normalised,
                    "wlanAcEntry_desc" to label.ifBlank { "New device" },
                    "wlanAcEntry_allow" to "1",
                    "Save" to "Save",
                ),
            )
            add.getOrThrow()

            // Verify the entry really landed on the router before reporting success.
            val after = RouterPageParser.parseWhitelistedMacs(getHtml(MAC_FILTER_PATH).getOrThrow())
            if (!after.contains(normalised)) {
                throw RouterException(
                    "The router accepted the request but the entry did not appear in its list. " +
                        "Add it manually from the router UI (Wireless - MAC Filtering).",
                )
            }
        }
    }

    override suspend fun removeFromWhitelist(mac: String): Result<Unit> {
        val normalised = MacAddressValidator.normalise(mac)
            ?: return Result.failure(RouterException("Invalid MAC address: $mac"))

        return guarded {
            val html = getHtml(MAC_FILTER_PATH).getOrThrow()
            // The classic page deletes by row index: find which JS row holds the MAC.
            val rows = RouterPageParser.parseJsArrayBlocks(html)
            var entryIndex = -1
            var seen = -1
            for (row in rows) {
                val rowMac = row.mapNotNull { MacAddressValidator.normalise(it) }.firstOrNull() ?: continue
                seen++
                if (rowMac == normalised && entryIndex < 0) entryIndex = seen
            }
            if (entryIndex < 0) {
                // Already absent - treat as success (idempotent revoke).
                return@guarded
            }
            saveWithQuery(
                MAC_FILTER_PATH,
                mapOf(
                    "wlanAcEntry_sel" to entryIndex.toString(),
                    "Delete" to "Delete",
                ),
            ).getOrThrow()

            val after = RouterPageParser.parseWhitelistedMacs(getHtml(MAC_FILTER_PATH).getOrThrow())
            if (after.contains(normalised)) {
                throw RouterException(
                    "The router did not remove the entry; delete it manually from the router UI " +
                        "(Wireless - MAC Filtering).",
                )
            }
        }
    }

    override suspend fun isMacFilteringEnabled(): Result<Boolean?> = guarded {
        RouterPageParser.parseFilteringEnabled(getHtml(MAC_FILTER_PATH).getOrThrow())
    }

    override suspend fun setMacFilteringEnabled(enabled: Boolean): Result<Unit> = guarded {
        val post = saveWithQuery(
            MAC_FILTER_PATH,
            mapOf(
                "wlanAcEnabled" to if (enabled) "1" else "0",
                "Save" to "Save",
            ),
        )
        post.getOrThrow()
        val after = RouterPageParser.parseFilteringEnabled(getHtml(MAC_FILTER_PATH).getOrThrow())
        if (after != null && after != enabled) {
            throw RouterException("The router did not apply the new filtering state.")
        }
    }

    companion object {
        const val USER_AGENT = "WifiGatekeeper/1.5"
        private const val LOGIN_PATH = "/userRpm/LoginRpm.htm"
        private const val DHCP_LIST_PATH = "/userRpm/AssignedIpAddrListRpm.htm"
        private const val WLAN_STATIONS_PATH = "/userRpm/WlanStationRpm.htm"
        private const val MAC_FILTER_PATH = "/userRpm/WlanAccessRpm.htm"
    }
}
