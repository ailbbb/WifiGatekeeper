package com.wifigatekeeper

import com.wifigatekeeper.core.network.SelfMacResolver
import com.wifigatekeeper.core.router.DeviceScanEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lockout-critical resolution order: the router's own device list (matched
 * by IP) wins; the hardware MAC from sysfs is ONLY trusted when the router
 * reports the same address, because Android 10+ presents a randomized MAC to
 * the network while sysfs shows the real hardware one.
 */
class SelfMacResolverTest {

    private val scan = listOf(
        DeviceScanEntry("AA:BB:CC:DD:EE:01", "laptop", "192.168.0.101"),
        DeviceScanEntry("AA:BB:CC:DD:EE:02", "this phone", "192.168.0.102"),
        DeviceScanEntry("AA:BB:CC:DD:EE:03", "tv", "192.168.0.103"),
    )

    private fun reader(returnValue: String?): (String) -> String? = { returnValue }

    @Test
    fun `ip match against the router scan wins and is confirmed`() {
        val resolved = SelfMacResolver.resolveWithConfirmation(
            "192.168.0.102", scan, reader("AA:BB:CC:DD:EE:FF"),
        )
        assertEquals("AA:BB:CC:DD:EE:02", resolved?.mac)
        assertTrue(resolved!!.confirmed)
    }

    @Test
    fun `sysfs hardware mac is confirmed when the router reports the same one`() {
        val resolved = SelfMacResolver.resolveWithConfirmation(
            "192.168.0.102", scan, reader("AA:BB:CC:DD:EE:02"),
        )
        // IP match still wins; the cross-check path is exercised without it.
        assertEquals("AA:BB:CC:DD:EE:02", resolved?.mac)
        assertTrue(resolved!!.confirmed)
    }

    @Test
    fun `sysfs mac absent from the router scan is UNCONFIRMED`() {
        // Randomized-MAC scenario: no IP match possible, hardware address is
        // real but invisible to the router - whitelisting it would lock the
        // phone out, so it must surface as confirmed=false.
        val resolved = SelfMacResolver.resolveWithConfirmation(
            null, scan, reader("AA:BB:CC:DD:EE:FF"),
        )
        assertEquals("AA:BB:CC:DD:EE:FF", resolved?.mac)
        assertFalse(resolved!!.confirmed)
    }

    @Test
    fun `ip match beats an unconfirmed sysfs read`() {
        val resolved = SelfMacResolver.resolveWithConfirmation(
            "192.168.0.102", scan, reader("AA:BB:CC:DD:EE:FF"),
        )
        assertEquals("AA:BB:CC:DD:EE:02", resolved?.mac)
        assertTrue(resolved!!.confirmed)
    }

    @Test
    fun `sysfs failure with no ip match yields null`() {
        assertNull(SelfMacResolver.resolveWithConfirmation("10.0.0.99", scan, reader(null)))
    }

    @Test
    fun `blocked sysfs read falls back to ip match`() {
        val resolved = SelfMacResolver.resolveWithConfirmation(
            "192.168.0.102", scan, reader(null),
        )
        assertEquals("AA:BB:CC:DD:EE:02", resolved?.mac)
        assertTrue(resolved!!.confirmed)
    }

    @Test
    fun `malformed sysfs mac is ignored`() {
        assertNull(SelfMacResolver.resolveWithConfirmation(null, scan, reader("junk")))
        assertNull(SelfMacResolver.resolveWithConfirmation(null, scan, reader("AA:BB:CC")))
    }

    @Test
    fun `dash-form macs in the scan are normalised`() {
        val dashScan = listOf(DeviceScanEntry("AA-BB-CC-DD-EE-02", "phone", "192.168.0.102"))
        val resolved = SelfMacResolver.resolveWithConfirmation("192.168.0.102", dashScan, reader(null))
        assertEquals("AA:BB:CC:DD:EE:02", resolved?.mac)
        assertTrue(resolved!!.confirmed)
    }
}
