package dev.ccbuddy.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalAddress(val label: String, val ip: String)

/**
 * Every non-loopback IPv4 address currently on the device, labeled by
 * interface — so the pairing screen can show both the LAN Wi-Fi IP and,
 * if Tailscale is active, its address.
 */
object NetworkUtils {
    fun localAddresses(): List<LocalAddress> {
        val results = mutableListOf<LocalAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val addresses = iface.inetAddresses?.toList() ?: continue
            for (addr in addresses) {
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress) continue
                results.add(LocalAddress(labelFor(iface.name, addr), addr.hostAddress ?: continue))
            }
        }
        return results
    }

    /**
     * Tailscale assigns addresses from the 100.64.0.0/10 CGNAT range. That's
     * the reliable signal — Android's Tailscale app runs as a generic
     * VpnService, so the interface it creates is just a `tun*`/`tunN` device
     * with no name tying it back to Tailscale, unlike desktop platforms'
     * `tailscale0`. Checking the address range instead of the interface name
     * is what actually works here, and is also what still finds Tailscale
     * when mDNS can't (mDNS doesn't route across the VPN).
     */
    internal fun isTailscaleAddress(addr: Inet4Address): Boolean {
        val bytes = addr.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    // Extracted from localAddresses() above so the labeling logic itself
    // (not the real NetworkInterface enumeration, which needs a device/
    // emulator) is unit-testable directly.
    internal fun labelFor(ifaceName: String, addr: Inet4Address): String = when {
        isTailscaleAddress(addr) -> "Tailscale"
        ifaceName.contains("tailscale", ignoreCase = true) -> "Tailscale"
        ifaceName.startsWith("wlan") -> "Wi-Fi"
        else -> ifaceName
    }
}
