package com.worldexecute.viewer

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the address another device on the same network should dial.
 *
 * Deliberately built on [NetworkInterface] rather than `WifiManager`: that one
 * wants `ACCESS_WIFI_STATE`, reports nothing when the phone is sharing its own
 * connection over a hotspot, and has been deprecated in pieces. Enumerating
 * interfaces needs no permission at all and covers Wi-Fi, hotspot and USB
 * tethering with the same code.
 *
 * "The" LAN address is not a well-defined thing — a phone can hold several at
 * once, and the one that works depends on which network the other device is on.
 * So this ranks them by how likely each is to be the one a human means, and
 * [all] is there for when the ranking is wrong.
 */
object LanAddress {

    /**
     * Interface names by descending likelihood, as a prefix match. `wlan` is
     * ordinary Wi-Fi, `ap` is a phone running its own hotspot, `eth`/`rndis`/
     * `usb` are tethering over a cable. Anything left over still counts, just
     * later.
     */
    private val RANKED_PREFIXES = listOf("wlan", "ap", "eth", "rndis", "usb")

    /** The best guess, or null when this device has no usable IPv4 at all. */
    fun find(): String? = all().firstOrNull()

    /**
     * Every plausible LAN IPv4, best first. Link-local addresses (169.254.x.x,
     * what an interface holds when DHCP failed) are excluded: they cannot be
     * reached from another device.
     */
    fun all(): List<String> {
        val found = ArrayList<Pair<Int, String>>(4)
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            Diag.add("lan", "枚举网卡失败: " + e.message)
            return emptyList()
        }

        for (nif in interfaces) {
            // isUp and isLoopback both throw on a vanishing interface, and an
            // interface that is disappearing is exactly not the one we want.
            val usable = try {
                nif.isUp && !nif.isLoopback && !nif.isVirtual
            } catch (e: Exception) {
                false
            }
            if (!usable) continue

            val name = nif.name.orEmpty().lowercase()
            val rank = RANKED_PREFIXES.indexOfFirst { name.startsWith(it) }
                .let { if (it < 0) RANKED_PREFIXES.size else it }

            for (address in nif.inetAddresses) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val text = address.hostAddress ?: continue
                found.add(rank to text)
            }
        }

        return found.sortedBy { it.first }.map { it.second }.distinct()
    }
}
