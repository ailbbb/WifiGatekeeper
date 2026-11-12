package com.wifigatekeeper.core.router

import org.jsoup.Jsoup

/**
 * Parsers for the two router page formats the app understands:
 * classic TP-Link JavaScript-array pages and generic HTML tables.
 */
object RouterPageParser {

    private val MAC_IN_TEXT =
        Regex("[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}")

    /** Splits every `var X = new Array("a", "b", ...)` block found in a classic TP-Link page. */
    fun parseJsArrayBlocks(html: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        val blockRegex = Regex("new\\s*Array\\s*\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
        val itemRegex = Regex("\"([^\"]*)\"")
        for (match in blockRegex.findAll(html)) {
            out.add(itemRegex.findAll(match.groupValues[1]).map { it.groupValues[1] }.toList())
        }
        return out
    }

    /**
     * Scans JS-array rows and pairs every MAC with its nearest neighbours
     * (typically the IP before it and the host name / lease after it).
     */
    fun parseDeviceEntriesFromJsRows(rows: List<List<String>>): List<DeviceScanEntry> {
        val entries = mutableListOf<DeviceScanEntry>()
        for (row in rows) {
            row.forEachIndexed { index, cell ->
                val mac = MacAddressValidator.normalise(cell)
                if (mac != null && entries.none { it.mac == mac }) {
                    val ip = row.take(index).lastOrNull { it.contains('.') && !it.contains(':') }
                    val name = row.drop(index + 1)
                        .firstOrNull { it.isNotBlank() && !it.contains(':') && !it.contains('.') }
                    entries.add(DeviceScanEntry(mac, name?.takeIf { it.isNotBlank() }, ip))
                }
            }
        }
        return entries
    }

    /** Extracts device rows from an HTML table (generic routers). */
    fun parseDeviceEntriesFromTable(html: String): List<DeviceScanEntry> {
        val doc = Jsoup.parse(html)
        val entries = mutableListOf<DeviceScanEntry>()
        for (row in doc.select("tr")) {
            val cells = row.select("td, th").map { it.text().trim() }
            val macCell = cells.firstOrNull { MacAddressValidator.isValid(it) } ?: continue
            val mac = MacAddressValidator.normalise(macCell) ?: continue
            if (entries.any { it.mac == mac }) continue
            val macIndex = cells.indexOf(macCell)
            val ip = cells.take(macIndex).lastOrNull { it.contains('.') && !it.contains(':') }
            val name = cells.drop(macIndex + 1).firstOrNull { it.isNotBlank() && it != macCell }
            entries.add(DeviceScanEntry(mac, name, ip))
        }
        return entries
    }

    /** All MACs found in classic TP-Link page JS rows (whitelist / DHCP lists). */
    fun parseWhitelistedMacs(html: String): List<String> {
        val macs = mutableListOf<String>()
        for (row in parseJsArrayBlocks(html)) {
            for (cell in row) {
                val mac = MacAddressValidator.normalise(cell)
                if (mac != null && macs.none { it == mac }) macs.add(mac)
            }
        }
        return macs
    }

    /**
     * Reads the wireless MAC filtering state from a classic TP-Link page.
     * 0 = disabled, 1 = allow-list mode, 2 = forbid-list mode; null = unknown.
     */
    fun parseFilteringEnabled(html: String): Boolean? {
        val match = Regex("wlanAcEnabled\\s*=\\s*([0-2])").find(html) ?: return null
        return match.groupValues[1] != "0"
    }

    /** True when the page looks like a login form or redirect instead of real content. */
    fun looksLikeLoginPage(html: String): Boolean {
        return html.contains("LoginRpm") ||
            (html.contains("parent.location") && html.contains("login")) ||
            html.contains("<title>Log In") ||
            (html.contains("name=\"password\"") && html.contains("<form"))
    }

    /** Every MAC-shaped token in the document, uppercase, de-duplicated. */
    fun extractMacAddresses(html: String): List<String> =
        MAC_IN_TEXT.findAll(html).map { it.value.uppercase() }.distinct().toList()
}
