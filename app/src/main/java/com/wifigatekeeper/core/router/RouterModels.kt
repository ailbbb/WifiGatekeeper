package com.wifigatekeeper.core.router

/** Router UI abstractions supported by the app. */
enum class RouterKind(val label: String, val hint: String) {
    GENERIC(
        "Generic",
        "Modern routers with a standard web login (works with many brands).",
    ),
    TP_LINK_CLASSIC(
        "TP-Link Classic",
        "WR940N / WR841N and similar models with the blue-green userRpm interface.",
    ),
}

/** A device observed on the LAN during a scan. */
data class DeviceScanEntry(
    val mac: String,
    val name: String?,
    val ip: String?,
)

/** Base type for all router communication failures. */
open class RouterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Wrong credentials, or the router refused the login. */
class RouterAuthException(message: String, cause: Throwable? = null) : RouterException(message, cause)

/** Network-level failure: timeout, unreachable host, connection reset, and so on. */
class RouterConnectionException(message: String, cause: Throwable? = null) : RouterException(message, cause)

/** The router answered, but the response could not be interpreted. */
class RouterParseException(message: String, cause: Throwable? = null) : RouterException(message, cause)
