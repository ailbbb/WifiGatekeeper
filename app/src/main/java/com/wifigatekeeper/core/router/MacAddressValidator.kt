package com.wifigatekeeper.core.router

/** Validates and normalises MAC addresses in the common colon notation. */
object MacAddressValidator {

    private val MAC_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun isValid(raw: String): Boolean = MAC_REGEX.matches(raw.trim())

    /** Returns the uppercase colon form, or null when [raw] is not a valid MAC. */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim().uppercase()
        if (!MAC_REGEX.matches(trimmed)) return null
        return trimmed
    }
}
