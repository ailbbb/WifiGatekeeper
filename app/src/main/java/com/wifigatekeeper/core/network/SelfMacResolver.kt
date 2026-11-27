package com.wifigatekeeper.core.network

import com.wifigatekeeper.core.router.DeviceScanEntry
import com.wifigatekeeper.core.router.MacAddressValidator
import java.io.File

/**
 * Works out THIS phone's Wi-Fi MAC so auto-protection can whitelist it before
 * MAC filtering goes on.
 *
 * Order of trust matters - getting this wrong locks the phone out of its own
 * network the moment the filter switches on:
 *
 * 1. MATCH-BY-IP (authoritative): the phone's current LAN IPv4 (from
 *    LinkProperties) is looked up in the router's own DHCP/station list. The
 *    router can only report MACs it actually sees on the wireless link, so a
 *    match is the randomized-MAC-correct answer, not just the hardware one.
 * 2. sysfs `/sys/class/net/wlan0/address` (UNRELIABLE): shows the HARDWARE MAC
 *    and is SELinux-blocked on many Android 11+ devices. It is only accepted
 *    when the SAME address also appears in the router's scan - otherwise it is
 *    exactly the hardware-vs-randomized mismatch that must never be whitelisted.
 *
 * [readSysfsMac] is injectable so unit tests can fake both failure and the
 * hardware-vs-randomized mismatch.
 */
object SelfMacResolver {

    /** The resolved MAC plus whether the router itself vouches for it. */
    data class ResolvedSelfMac(val mac: String, val confirmed: Boolean)

    /** Reads the wlan0 hardware address, or null when blocked/absent. */
    private val defaultSysfsReader: (String) -> String? = { path ->
        runCatching { File(path).readText().trim().uppercase() }.getOrNull()
            ?.takeIf { it.length == 17 }
    }

    /**
     * Resolves the phone's MAC against [scanEntries] (the router's live view).
     * Returns null when nothing trustworthy was found; [ResolvedSelfMac.confirmed]
     * is false when only an uncorroborated source produced the MAC.
     */
    fun resolveWithConfirmation(
        localIp: String?,
        scanEntries: List<DeviceScanEntry>,
        readSysfsMac: (String) -> String? = defaultSysfsReader,
    ): ResolvedSelfMac? {
        // 1) The router's own table is the single source of truth.
        if (!localIp.isNullOrBlank()) {
            val byIp = scanEntries.firstOrNull { it.ip == localIp }?.mac
                ?.let { MacAddressValidator.normalise(it) }
            if (byIp != null) return ResolvedSelfMac(byIp, confirmed = true)
        }

        // 2) The hardware address is only usable when the router reports the
        //    same one (true when randomization is off). Never trust it blind.
        val sysfsMac = readSysfsMac(WLAN0_ADDRESS)
            ?.let { MacAddressValidator.normalise(it) }
        if (sysfsMac != null && scanEntries.any { it.mac == sysfsMac }) {
            return ResolvedSelfMac(sysfsMac, confirmed = true)
        }
        if (sysfsMac != null) {
            // Present but not visible to the router: almost certainly the
            // hardware-vs-randomized mismatch. Surface it as UNCONFIRMED so
            // callers refuse to enable filtering on top of it.
            return ResolvedSelfMac(sysfsMac, confirmed = false)
        }
        return null
    }

    /** Backwards-compatible helper: the best-guess MAC, or null. */
    fun resolve(
        localIp: String?,
        scanEntries: List<DeviceScanEntry>,
        readSysfsMac: (String) -> String? = defaultSysfsReader,
    ): String? = resolveWithConfirmation(localIp, scanEntries, readSysfsMac)?.mac

    private const val WLAN0_ADDRESS = "/sys/class/net/wlan0/address"
}
