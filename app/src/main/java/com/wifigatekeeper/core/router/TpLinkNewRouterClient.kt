package com.wifigatekeeper.core.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Client for the newer TP-Link 11N firmware family (WR940N v6+, WR841N v13+):
 * the MAC filter lives on /userRpm/WlanMacFilterRpm.htm and EVERY operation is
 * a GET with query parameters - unlike the classic WlanAccessRpm pages that the
 * older firmware drives with form fields. Sending the classic POST to these
 * firmwares is what produced the "HTTP 500" reports on the filter toggle.
 *
 * The protocol (Enfilter/Disfilter, exclusive, Del, Add+Save, and the
 * wlanFilterPara / wlanFilterList state arrays) was extracted from the official
 * TP-Link WR940N v6 web emulator.
 */
class TpLinkNewRouterClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    socketFactory: javax.net.SocketFactory? = null,
) : RouterClient {

    override val kind = RouterKind.TP_LINK_NEW

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

    private fun request(path: String): Request = Request.Builder()
        .url("$base$path")
        .header("Authorization", basicHeader)
        .header("Referer", "$base$MAC_FILTER_PATH")
        .header("User-Agent", TpLinkClassicRouterClient.USER_AGENT)
        .build()

    /** GETs a page, mapping 401 / HTTP errors to failures. */
    private suspend fun getHtml(path: String): Result<String> =
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request(path)).execute().use { response ->
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

    /**
     * Performs a state-changing GET (this firmware family saves through query
     * parameters, not POST bodies). Non-2xx answers are surfaced verbatim so
     * the user sees e.g. "HTTP 500" instead of a bare "failed".
     */
    private suspend fun saveWithQuery(params: Map<String, String>): Result<Unit> {
        val builder = "$base$MAC_FILTER_PATH".toHttpUrlOrNull()?.newBuilder() ?: return Result.failure(
            RouterException("Invalid router address."),
        )
        for ((name, value) in params) builder.addQueryParameter(name, value)
        return try {
            withContext(Dispatchers.IO) {
                val call = Request.Builder()
                    .url(builder.build())
                    .header("Authorization", basicHeader)
                    .header("Referer", "$base$MAC_FILTER_PATH")
                    .header("User-Agent", TpLinkClassicRouterClient.USER_AGENT)
                    .build()
                http.newCall(call).execute().use { response ->
                    when {
                        response.code == 401 ->
                            Result.failure(RouterAuthException("Router rejected the credentials (HTTP 401)."))
                        !response.isSuccessful ->
                            Result.failure(RouterException("Router answered HTTP ${response.code} for the change."))
                        else -> Result.success(Unit)
                    }
                }
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }
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

    // ---------- RouterClient ----------

    override suspend fun probeConnection(): Result<Int> {
        val filterPage = getHtml(MAC_FILTER_PATH)
        if (filterPage.isFailure) {
            return Result.failure(
                RouterException("No WlanMacFilterRpm page on this firmware - not a TP-Link New profile."),
            )
        }
        if (!RouterPageParser.looksLikeNewFilterPage(filterPage.getOrThrow())) {
            return Result.failure(RouterException("The filter page did not report its state."))
        }
        return fetchConnectedDevices().map { it.size }
    }

    override suspend fun fetchConnectedDevices(): Result<List<DeviceScanEntry>> = guarded {
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
        val html = getHtml(MAC_FILTER_PATH).getOrThrow()
        RouterPageParser.parseNewFilterEntries(html)
            .mapNotNull { MacAddressValidator.normalise(it.firstOrNull().orEmpty()) }
            .distinct()
    }

    override suspend fun addToWhitelist(mac: String, label: String): Result<Unit> {
        val normalised = MacAddressValidator.normalise(mac)
            ?: return Result.failure(RouterException("Invalid MAC address: $mac"))

        return guarded {
            val page = RouterPageParser.parseNewFilterParams(getHtml(MAC_FILTER_PATH).getOrThrow())
            val curPage = page?.get(3)?.coerceIn(1, 100) ?: 1

            saveWithQuery(
                mapOf(
                    "Mac" to normalised,
                    "Desc" to label.ifBlank { "New device" },
                    "Type" to "1", // privilege 1 = allow (2 = deny, per the v6 page JS)
                    "key" to "",
                    "entryEnabled" to "1",
                    "Changed" to "1",
                    "SelIndex" to "0",
                    "Page" to curPage.toString(),
                    "vapIdx" to "1",
                    "Save" to "Save",
                ),
            ).getOrThrow()

            val after = RouterPageParser.parseNewFilterEntries(getHtml(MAC_FILTER_PATH).getOrThrow())
            if (after.none { MacAddressValidator.normalise(it.firstOrNull().orEmpty()) == normalised }) {
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
            // The v6 list is paginated (8 rows per page) and deletes by absolute
            // row id, so walk the pages until the MAC shows up.
            for (pageNo in 1..5) {
                val html = getHtml("$MAC_FILTER_PATH?Page=$pageNo&vapIdx=1").getOrElse { return@guarded }
                val params = RouterPageParser.parseNewFilterParams(html) ?: return@guarded
                val entries = RouterPageParser.parseNewFilterEntries(html)
                val rowIndex = entries.indexOfFirst {
                    MacAddressValidator.normalise(it.firstOrNull().orEmpty()) == normalised
                }
                if (rowIndex >= 0) {
                    val absoluteId = (pageNo - 1) * 8 + rowIndex
                    saveWithQuery(
                        mapOf(
                            "Del" to absoluteId.toString(),
                            "Page" to pageNo.toString(),
                            "vapIdx" to "1",
                        ),
                    ).getOrThrow()
                    break
                }
                if ((params.getOrNull(4) ?: 0) == 0) break // no next page
            }
            val after = RouterPageParser.parseNewFilterEntries(getHtml(MAC_FILTER_PATH).getOrThrow())
            if (after.any { MacAddressValidator.normalise(it.firstOrNull().orEmpty()) == normalised }) {
                throw RouterException(
                    "The router did not remove the entry; delete it manually from the router UI " +
                        "(Wireless - MAC Filtering).",
                )
            }
        }
    }

    override suspend fun isMacFilteringEnabled(): Result<Boolean?> = guarded {
        RouterPageParser.parseNewFilteringEnabled(getHtml(MAC_FILTER_PATH).getOrThrow())
    }

    override suspend fun setMacFilteringEnabled(enabled: Boolean): Result<Unit> = guarded {
        val html = getHtml(MAC_FILTER_PATH).getOrThrow()
        val params = RouterPageParser.parseNewFilterParams(html)
            ?: throw RouterException("The filter page did not report its state.")
        val curPage = params.get(3).coerceIn(1, 100)

        if (enabled) {
            // Rule first, THEN the switch: exclusive=1 means "allow the listed
            // stations" (whitelist). Enabling while the rule is still deny-listed
            // would invert the whole protection model.
            if (params.get(1) != 1) {
                runCatching {
                    saveWithQuery(
                        mapOf("Page" to curPage.toString(), "exclusive" to "1", "vapIdx" to "1"),
                    )
                }
            }
            saveWithQuery(
                mapOf("Page" to curPage.toString(), "Enfilter" to "1", "vapIdx" to "1"),
            ).getOrThrow()
        } else {
            saveWithQuery(
                mapOf("Page" to curPage.toString(), "Disfilter" to "1", "vapIdx" to "1"),
            ).getOrThrow()
        }

        val after = RouterPageParser.parseNewFilteringEnabled(getHtml(MAC_FILTER_PATH).getOrThrow())
        if (after != null && after != enabled) {
            throw RouterException("The router did not apply the new filtering state.")
        }
        if (enabled) {
            val ruleAfter = RouterPageParser.parseNewFilterParams(getHtml(MAC_FILTER_PATH).getOrThrow())?.get(1)
            if (ruleAfter == 0) {
                throw RouterException(
                    "Filtering is on but the rule stayed 'deny the listed' - switch the rule to " +
                        "'Allow the stations listed' manually from the router page.",
                )
            }
        }
    }

    companion object {
        private const val DHCP_LIST_PATH = "/userRpm/AssignedIpAddrListRpm.htm"
        private const val WLAN_STATIONS_PATH = "/userRpm/WlanStationRpm.htm"
        private const val MAC_FILTER_PATH = "/userRpm/WlanMacFilterRpm.htm"
    }
}
