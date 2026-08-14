package dev.ccbuddy.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalAddress(val label: String, val ip: String)

/**
 * Every non-loopback IPv4 address currently on the device, labeled by
 * interface — so the pairing screen can show both the LAN Wi-Fi IP and,
 * if Tailscale is active, its 100.64.0.0/10 CGNAT-range IP (interface
 * name `tailscale0` on Android).
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
                val label = when {
                    iface.name.contains("tailscale") -> "Tailscale"
                    iface.name.startsWith("wlan") -> "Wi-Fi"
                    else -> iface.name
                }
                results.add(LocalAddress(label, addr.hostAddress ?: continue))
            }
        }
        return results
    }
}
