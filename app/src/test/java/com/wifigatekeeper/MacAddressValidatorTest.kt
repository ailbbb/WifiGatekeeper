package com.wifigatekeeper

import com.wifigatekeeper.core.router.MacAddressValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAddressValidatorTest {

    @Test
    fun `valid colon notation is accepted`() {
        assertTrue(MacAddressValidator.isValid("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `lowercase mac is accepted`() {
        assertTrue(MacAddressValidator.isValid("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(MacAddressValidator.isValid("  AA:BB:CC:DD:EE:FF  "))
    }

    @Test
    fun `normalise uppercases the address`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddressValidator.normalise("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `dash notation is normalised to colons`() {
        assertTrue(MacAddressValidator.isValid("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddressValidator.normalise("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun `mixed separators are normalised`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddressValidator.normalise("AA-BB:CC-DD:EE-FF"))
    }

    @Test
    fun `too short address is rejected`() {
        assertFalse(MacAddressValidator.isValid("AA:BB:CC:DD:EE"))
        assertNull(MacAddressValidator.normalise("AA:BB:CC:DD:EE"))
    }

    @Test
    fun `non hex characters are rejected`() {
        assertFalse(MacAddressValidator.isValid("GG:BB:CC:DD:EE:FF"))
        assertNull(MacAddressValidator.normalise("GG:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `empty string is rejected`() {
        assertFalse(MacAddressValidator.isValid(""))
        assertNull(MacAddressValidator.normalise(""))
    }

    @Test
    fun `plain digits without separators are rejected`() {
        assertFalse(MacAddressValidator.isValid("AABBCCDDEEFF"))
        assertNull(MacAddressValidator.normalise("AABBCCDDEEFF"))
    }

    @Test
    fun `leading zeros are preserved`() {
        assertEquals("01:02:03:04:05:06", MacAddressValidator.normalise("01:02:03:04:05:06"))
    }
}
