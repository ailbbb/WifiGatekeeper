package com.wifigatekeeper.core.network

import com.wifigatekeeper.core.router.DeviceScanEntry
import java.io.File

/**
 * Works out THIS phone's Wi-Fi MAC so the auto-protection can whitelist it
 * before MAC filtering goes on. The sysfs address reflects the MAC actually in
 * use (including Android's per-network randomisation), so it matches what the
 * router sees; matching the local IP against the scan entries is the fallback
 * for exotic network setups.
 */
object SelfMacResolver {

    /** Returns the phone's own MAC, or null when it cannot be determined. */
    fun resolve(localIp: String?, scanEntries: List<DeviceScanEntry>): String? {
        runCatching {
            File("/sys/class/net/wlan0/address").readText().trim().uppercase()
        }.getOrNull()?.let { if (it.length == 17) return it }

        if (localIp.isNullOrBlank()) return null
        return scanEntries.firstOrNull { it.ip == localIp }?.mac
    }
}
