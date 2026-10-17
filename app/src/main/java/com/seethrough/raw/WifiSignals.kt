package com.seethrough.raw

import java.util.Locale

/** Access points the phone redacts, and blank addresses, are not rows in a vector. */
internal fun canonicalBssid(raw: String): String? {
    val bssid = raw.trim().lowercase(Locale.ROOT)
    if (bssid.isEmpty() || bssid == "02:00:00:00:00:00") return null
    return bssid
}

/** Strongest reading for each access point in this scan. */
internal fun strengthsByBssid(networks: List<WifiNetwork>): Map<String, Int> {
    val strongest = LinkedHashMap<String, Int>()
    for (network in networks) {
        val bssid = canonicalBssid(network.bssid) ?: continue
        val signal = network.signal.coerceIn(1, 1000)
        val previous = strongest[bssid]
        if (previous == null || signal > previous) {
            strongest[bssid] = signal
        }
    }
    return strongest
}
