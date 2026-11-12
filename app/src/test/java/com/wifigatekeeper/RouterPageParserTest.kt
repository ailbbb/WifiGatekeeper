package com.wifigatekeeper

import com.wifigatekeeper.core.router.RouterPageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterPageParserTest {

    @Test
    fun `tp-link dhcp array rows are parsed into device entries`() {
        val html = """
            var DHCPDynList = new Array(
                "192.168.0.100",
                "AA:BB:CC:DD:EE:01",
                "1200",
                "192.168.0.101",
                "AA:BB:CC:DD:EE:02",
                "3600",
            );
        """.trimIndent()
        val rows = RouterPageParser.parseJsArrayBlocks(html)
        assertEquals(1, rows.size)
        val entries = RouterPageParser.parseDeviceEntriesFromJsRows(rows)
        assertEquals(2, entries.size)
        assertEquals("AA:BB:CC:DD:EE:01", entries[0].mac)
        assertEquals("192.168.0.100", entries[0].ip)
        assertEquals("AA:BB:CC:DD:EE:02", entries[1].mac)
        assertEquals("192.168.0.101", entries[1].ip)
    }

    @Test
    fun `wireless station array rows are parsed`() {
        val html = """
            var wlanStaList = new Array(
                "AA:BB:CC:DD:EE:03",
                "phone-livingroom",
                "192.168.0.102",
            );
        """.trimIndent()
        val entries = RouterPageParser.parseDeviceEntriesFromJsRows(RouterPageParser.parseJsArrayBlocks(html))
        assertEquals(1, entries.size)
        assertEquals("AA:BB:CC:DD:EE:03", entries[0].mac)
        assertEquals("phone-livingroom", entries[0].name)
    }

    @Test
    fun `generic html table rows are parsed`() {
        val html = """
            <table>
              <tr><th>Host</th><th>IP</th><th>MAC</th></tr>
              <tr><td>laptop</td><td>192.168.1.50</td><td>AA:BB:CC:DD:EE:04</td></tr>
              <tr><td>tv</td><td>192.168.1.51</td><td>AA:BB:CC:DD:EE:05</td></tr>
            </table>
        """.trimIndent()
        val entries = RouterPageParser.parseDeviceEntriesFromTable(html)
        assertEquals(2, entries.size)
        assertEquals("AA:BB:CC:DD:EE:04", entries[0].mac)
        assertEquals("192.168.1.50", entries[0].ip)
        assertEquals("AA:BB:CC:DD:EE:05", entries[1].mac)
    }

    @Test
    fun `whitelist macs are extracted from tp-link js arrays`() {
        val html = """
            var wlanAcList = new Array(
                "AA:BB:CC:DD:EE:01",
                "phone",
                "1",
                "AA:BB:CC:DD:EE:02",
                "laptop",
                "1",
            );
        """.trimIndent()
        val macs = RouterPageParser.parseWhitelistedMacs(html)
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), macs)
    }

    @Test
    fun `filtering state is read from wlanAcEnabled`() {
        assertEquals(true, RouterPageParser.parseFilteringEnabled("var wlanAcEnabled = 1;"))
        assertEquals(false, RouterPageParser.parseFilteringEnabled("var wlanAcEnabled = 0;"))
        assertEquals(true, RouterPageParser.parseFilteringEnabled("var wlanAcEnabled = 2;"))
        assertNull(RouterPageParser.parseFilteringEnabled("no such var here"))
    }

    @Test
    fun `login pages are detected`() {
        assertTrue(RouterPageParser.looksLikeLoginPage("<html><body><form name=\"login\" name=\"password\"><input></form></body></html>"))
        assertFalse(
            RouterPageParser.looksLikeLoginPage(
                "<html><body><script>var DHCPDynList = new Array(\"AA:BB:CC:DD:EE:01\");</script></body></html>",
            ),
        )
    }
}
