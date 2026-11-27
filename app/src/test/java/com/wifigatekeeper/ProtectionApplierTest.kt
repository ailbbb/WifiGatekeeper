package com.wifigatekeeper

import com.wifigatekeeper.core.router.ProtectionApplier
import com.wifigatekeeper.core.router.RouterClient
import com.wifigatekeeper.core.router.RouterException
import com.wifigatekeeper.core.router.RouterKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionApplierTest {

    /** Records every router-side mutation in order, and can fail either one. */
    private class FakeClient(
        private val failAdd: Boolean = false,
        private val failEnable: Boolean = false,
        private val whitelistAfterAdd: List<String> = emptyList(),
    ) : RouterClient {
        val calls = mutableListOf<String>()
        override val kind = RouterKind.TP_LINK_NEW
        override suspend fun probeConnection(): Result<Int> = Result.success(0)
        override suspend fun fetchConnectedDevices(): Result<List<com.wifigatekeeper.core.router.DeviceScanEntry>> =
            Result.success(emptyList())
        override suspend fun fetchWhitelistedMacAddresses(): Result<List<String>> =
            Result.success(whitelistAfterAdd)
        override suspend fun addToWhitelist(mac: String, label: String): Result<Unit> {
            calls += "add"
            if (failAdd) return Result.failure(RouterException("add refused"))
            return Result.success(Unit)
        }
        override suspend fun removeFromWhitelist(mac: String): Result<Unit> = Result.success(Unit)
        override suspend fun isMacFilteringEnabled(): Result<Boolean?> = Result.success(false)
        override suspend fun setMacFilteringEnabled(enabled: Boolean): Result<Unit> {
            calls += "enable"
            if (failEnable) return Result.failure(RouterException("HTTP 500"))
            return Result.success(Unit)
        }
    }

    private val steps = mutableListOf<ProtectionApplier.Step>()

    @Test
    fun `whitelists the phone BEFORE enabling filtering`() = runTest {
        val client = FakeClient()
        val outcome = ProtectionApplier.apply(client, "AA:BB:CC:DD:EE:FF", "Pixel 8", onStep = steps::add)
        assertEquals(listOf("add", "enable"), client.calls)
        assertEquals(listOf(ProtectionApplier.Step.WHITELIST, ProtectionApplier.Step.ENABLE), steps)
        assertTrue(outcome is ProtectionApplier.Outcome.FullyApplied)
    }

    @Test
    fun `reports whitelist-only when enabling the filter fails`() = runTest {
        val client = FakeClient(failEnable = true)
        val outcome = ProtectionApplier.apply(client, "AA:BB:CC:DD:EE:FF", "Pixel 8")
        assertEquals(listOf("add", "enable"), client.calls) // enable was attempted after the add
        assertTrue(outcome is ProtectionApplier.Outcome.WhitelistOnly)
        assertTrue((outcome as ProtectionApplier.Outcome.WhitelistOnly).reason.contains("500"))
    }

    @Test
    fun `does not touch the filter switch when the whitelist entry is refused`() = runTest {
        val client = FakeClient(failAdd = true)
        val outcome = ProtectionApplier.apply(client, "AA:BB:CC:DD:EE:FF", "Pixel 8")
        assertEquals(listOf("add"), client.calls) // enabling never attempted
        assertTrue(outcome is ProtectionApplier.Outcome.Failed)
    }

    @Test
    fun `unconfirmed mac vetoes EVERYTHING - nothing is sent to the router`() = runTest {
        // Randomized-MAC risk: enabling the filter before the whitelist entry
        // is certain locks the phone out of its own network.
        val client = FakeClient()
        val outcome = ProtectionApplier.apply(
            client, "AA:BB:CC:DD:EE:FF", "Pixel 8", macConfirmed = false,
        )
        assertTrue(client.calls.isEmpty())
        assertTrue(outcome is ProtectionApplier.Outcome.Failed)
        assertTrue((outcome as ProtectionApplier.Outcome.Failed).message.contains("confirmed"))
    }

    @Test
    fun `whitelist re-read passes when the router shows the phone`() = runTest {
        val client = FakeClient(whitelistAfterAdd = listOf("AA:BB:CC:DD:EE:FF"))
        val outcome = ProtectionApplier.apply(client, "AA:BB:CC:DD:EE:FF", "Pixel 8")
        assertTrue(outcome is ProtectionApplier.Outcome.FullyApplied)
    }

    @Test
    fun `whitelist re-read vetoes when the phone is missing from the router list`() = runTest {
        val client = FakeClient(whitelistAfterAdd = listOf("AA:BB:CC:DD:EE:11"))
        val outcome = ProtectionApplier.apply(client, "AA:BB:CC:DD:EE:FF", "Pixel 8")
        assertEquals(listOf("add"), client.calls) // enable never attempted
        assertTrue(outcome is ProtectionApplier.Outcome.Failed)
        assertTrue((outcome as ProtectionApplier.Outcome.Failed).message.contains("does not show"))
    }
}
