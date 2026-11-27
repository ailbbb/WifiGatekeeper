package com.wifigatekeeper.core.router

/**
 * Validates and normalises MAC addresses. Accepts colon and dash notations
 * (some DHCP/station tables print `AA-BB-CC-DD-EE-FF`) and normalises
 * everything to the uppercase colon form used by the router profiles.
 */
object MacAddressValidator {

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2})[:\\-]([0-9A-Fa-f]{2})[:\\-]([0-9A-Fa-f]{2})[:\\-]([0-9A-Fa-f]{2})[:\\-]([0-9A-Fa-f]{2})[:\\-]([0-9A-Fa-f]{2})$")

    fun isValid(raw: String): Boolean = MAC_REGEX.matches(raw.trim())

    /** Returns the uppercase colon form, or null when [raw] is not a valid MAC. */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim().uppercase()
        val match = MAC_REGEX.find(trimmed) ?: return null
        return match.groupValues.drop(1).joinToString(":")
    }
}
