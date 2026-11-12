package com.wifigatekeeper.core.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects the router profile by probing with the credentials the user typed in
 * the quick-link browser. TP-Link Classic is tried first because it is the
 * strictest signature (its /userRpm pages answer unauthenticated requests with
 * a Basic-auth 401); if that fails the tolerant generic client gets a shot.
 */
object RouterKindDetector {

    /**
     * Returns the first profile whose probe succeeds, or null when neither
     * works. [probe] is injectable so unit tests can fake the router.
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
        // TP-Link Classic first: a 401 Basic challenge is its signature.
        probe(RouterKind.TP_LINK_CLASSIC).onSuccess { return RouterKind.TP_LINK_CLASSIC }
        probe(RouterKind.GENERIC).onSuccess { return RouterKind.GENERIC }
        return null
    }
}
