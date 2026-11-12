package com.wifigatekeeper.core.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects the router profile by probing with the credentials the user typed in
 * the quick-link browser. The two TP-Link generations are told apart by their
 * distinct MAC-filter pages (WlanAccessRpm vs WlanMacFilterRpm); if neither
 * answers, the tolerant generic client gets a shot.
 */
object RouterKindDetector {

    /**
     * Returns the first profile whose probe succeeds, or null when none work.
     * [probe] is injectable so unit tests can fake the router.
     */
    suspend fun detect(
        baseUrl: String,
        username: String,
        password: String,
        probe: suspend (RouterKind) -> Result<Int> = { kind ->
            withContext(Dispatchers.IO) {
                RouterClientFactory.create(kind, baseUrl, username, password).probeConnection()
            }
        },
    ): RouterKind? {
        // Classic first: its probe fails on newer firmware (no WlanAccessRpm).
        probe(RouterKind.TP_LINK_CLASSIC).onSuccess { return RouterKind.TP_LINK_CLASSIC }
        // Newer 11N firmware: WlanMacFilterRpm pages, GET-driven saves.
        probe(RouterKind.TP_LINK_NEW).onSuccess { return RouterKind.TP_LINK_NEW }
        probe(RouterKind.GENERIC).onSuccess { return RouterKind.GENERIC }
        return null
    }
}
