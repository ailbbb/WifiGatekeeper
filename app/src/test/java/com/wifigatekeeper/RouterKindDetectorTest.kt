package com.wifigatekeeper

import com.wifigatekeeper.core.router.RouterKind
import com.wifigatekeeper.core.router.RouterKindDetector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterKindDetectorTest {

    private fun probeFor(
        classic: Result<Int>? = null,
        new: Result<Int>? = null,
        generic: Result<Int>? = null,
    ): suspend (RouterKind) -> Result<Int> = { kind ->
        when (kind) {
            RouterKind.TP_LINK_CLASSIC -> classic ?: Result.failure(IllegalStateException("no case"))
            RouterKind.TP_LINK_NEW -> new ?: Result.failure(IllegalStateException("no case"))
            RouterKind.GENERIC -> generic ?: Result.failure(IllegalStateException("no case"))
        }
    }

    @Test
    fun `falls back to TP-Link New when the classic filter page is missing`() = runTest {
        val kind = RouterKindDetector.detect(
            "192.168.0.1", "admin", "pass",
            probeFor(
                classic = Result.failure(RuntimeException("no WlanAccessRpm page")),
                new = Result.success(4),
            ),
        )
        assertEquals(RouterKind.TP_LINK_NEW, kind)
    }

    @Test
    fun `prefers TP-Link Classic when its probe succeeds`() = runTest {
        val kind = RouterKindDetector.detect(
            "192.168.0.1", "admin", "pass",
            probeFor(classic = Result.success(5), generic = Result.success(5)),
        )
        assertEquals(RouterKind.TP_LINK_CLASSIC, kind)
    }

    @Test
    fun `falls back to Generic when the classic probe is rejected`() = runTest {
        val kind = RouterKindDetector.detect(
            "192.168.1.1", "admin", "pass",
            probeFor(
                classic = Result.failure(RuntimeException("401")),
                generic = Result.success(3),
            ),
        )
        assertEquals(RouterKind.GENERIC, kind)
    }

    @Test
    fun `returns null when neither profile accepts the credentials`() = runTest {
        val kind = RouterKindDetector.detect(
            "192.168.1.1", "admin", "pass",
            probeFor(
                classic = Result.failure(RuntimeException("401")),
                generic = Result.failure(RuntimeException("timeout")),
            ),
        )
        assertNull(kind)
    }
}
