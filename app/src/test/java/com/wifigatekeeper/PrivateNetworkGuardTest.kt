package com.wifigatekeeper

import com.wifigatekeeper.core.network.PrivateNetworkGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateNetworkGuardTest {

    @Test
    fun `accepts RFC 1918 addresses`() {
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("192.168.0.1"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("192.168.1.254"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("10.0.0.5"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("172.16.0.1"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("172.31.255.254"))
    }

    @Test
    fun `rejects public and wrong-range addresses`() {
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("8.8.8.8"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("172.32.0.1"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("172.15.0.1"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("192.169.1.1"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("11.0.0.1"))
    }

    @Test
    fun `accepts localhost and mDNS names`() {
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("localhost"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("LOCALHOST"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("router.local"))
    }

    @Test
    fun `rejects plain hostnames that could be phishing sites`() {
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("example.com"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("192-168-0-1.example.com"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("wifi.home"))
    }

    @Test
    fun `tolerates URL and port forms defensively`() {
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("http://192.168.1.1/login"))
        assertTrue(PrivateNetworkGuard.isPrivateLanHost("192.168.0.1:8080"))
    }

    @Test
    fun `fails closed on blank malformed or null input`() {
        assertFalse(PrivateNetworkGuard.isPrivateLanHost(null))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost(""))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("   "))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("192.168"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("192.168.0.999"))
        assertFalse(PrivateNetworkGuard.isPrivateLanHost("a.b.c.d"))
    }
}
