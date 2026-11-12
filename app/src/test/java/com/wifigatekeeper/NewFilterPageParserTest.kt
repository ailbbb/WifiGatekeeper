package com.wifigatekeeper

import com.wifigatekeeper.core.router.RouterPageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser fixtures mirror the official TP-Link WR940N v6 emulator page
 * (WlanMacFilterRpm.htm) with its wlanFilterPara / wlanFilterList arrays.
 */
class NewFilterPageParserTest {

    private val enabledPage = """
        <SCRIPT language="javascript">
        var wlanFilterPara = new Array(
        1, 1, 0, 1, 0, 2, 5, 8, 0, 0, 7,
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript">
        var wlanFilterList = new Array(
        "AA:BB:CC:DD:EE:01",1,1,"","Phone",
        "AA:BB:CC:DD:EE:02",0,2,"","Laptop",
        0,0 );
        </SCRIPT>
    """

    private val disabledEmptyPage = """
        <SCRIPT language="javascript">
        var wlanFilterPara = new Array(
        0, 0, 0, 1, 0, 0, 5, 8, 0, 0, 7,
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript">
        var wlanFilterList = new Array(
        0,0 );
        </SCRIPT>
    """

    @Test
    fun `reads filtering state rule and list stride`() {
        val params = RouterPageParser.parseNewFilterParams(enabledPage)
        assertEquals(1, params?.get(0)) // enabled
        assertEquals(1, params?.get(1)) // rule = allow the listed
        assertEquals(5, params?.get(6)) // row stride
    }

    @Test
    fun `extracts the allow-listed entries in order`() {
        val rows = RouterPageParser.parseNewFilterEntries(enabledPage)
        assertEquals(2, rows.size)
        assertEquals("AA:BB:CC:DD:EE:01", rows[0].first())
        assertEquals("Phone", rows[0].last())
        assertEquals("AA:BB:CC:DD:EE:02", rows[1].first())
    }

    @Test
    fun `reports disabled and empty for the stock page`() {
        assertEquals(false, RouterPageParser.parseNewFilteringEnabled(disabledEmptyPage))
        assertTrue(RouterPageParser.parseNewFilterEntries(disabledEmptyPage).isEmpty())
        assertEquals(true, RouterPageParser.parseNewFilteringEnabled(enabledPage))
    }

    @Test
    fun `returns null for pages without the v6 state marker`() {
        assertNull(RouterPageParser.parseNewFilteringEnabled("<html>some other router</html>"))
        assertFalse(RouterPageParser.looksLikeNewFilterPage("<html>some other router</html>"))
        assertTrue(RouterPageParser.looksLikeNewFilterPage(enabledPage))
    }
}
