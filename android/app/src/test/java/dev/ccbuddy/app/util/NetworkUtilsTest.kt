package dev.ccbuddy.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

private fun inet4(vararg octets: Int): Inet4Address {
    val bytes = octets.map { it.toByte() }.toByteArray()
    return InetAddress.getByAddress(bytes) as Inet4Address
}

class NetworkUtilsTest {
    @Test
    fun `labels a Tailscale CGNAT address as Tailscale regardless of interface name`() {
        // 100.64.0.0/10 -- second octet 64..127. Given a made-up interface
        // name (Android's Tailscale VpnService interfaces are just
        // tun0/tunN, no name tying them back to Tailscale) to confirm the
        // address range alone is what's driving the label.
        val addr = inet4(100, 90, 1, 5)
        assertEquals("Tailscale", NetworkUtils.labelFor("tun0", addr))
    }

    @Test
    fun `labels by interface name when it says tailscale even outside the CGNAT range`() {
        val addr = inet4(10, 0, 0, 5)
        assertEquals("Tailscale", NetworkUtils.labelFor("tailscale0", addr))
    }

    @Test
    fun `labels a wlan interface as Wi-Fi`() {
        val addr = inet4(192, 168, 1, 39)
        assertEquals("Wi-Fi", NetworkUtils.labelFor("wlan0", addr))
    }

    @Test
    fun `falls back to the raw interface name otherwise`() {
        val addr = inet4(10, 0, 2, 15)
        assertEquals("eth0", NetworkUtils.labelFor("eth0", addr))
    }

    @Test
    fun `100_64 range is Tailscale`() {
        assertEquals(true, NetworkUtils.isTailscaleAddress(inet4(100, 64, 0, 1)))
    }

    @Test
    fun `100_127 range is Tailscale`() {
        assertEquals(true, NetworkUtils.isTailscaleAddress(inet4(100, 127, 255, 255)))
    }

    @Test
    fun `100_63 is just outside the CGNAT range, not Tailscale`() {
        assertEquals(false, NetworkUtils.isTailscaleAddress(inet4(100, 63, 0, 1)))
    }

    @Test
    fun `100_128 is just outside the CGNAT range, not Tailscale`() {
        assertEquals(false, NetworkUtils.isTailscaleAddress(inet4(100, 128, 0, 1)))
    }

    @Test
    fun `a typical LAN address is not mistaken for Tailscale`() {
        assertEquals(false, NetworkUtils.isTailscaleAddress(inet4(192, 168, 1, 1)))
    }
}
