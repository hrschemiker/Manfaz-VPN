package com.manfaz.vpn.core

import com.manfaz.vpn.data.FragmentMode
import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable snapshot of every setting the VPN core needs for one connection.
 *
 * The core lives in the isolated ":core" process where SharedPreferences caching is not
 * coherent, so the UI process snapshots the settings at connect time and ships them across
 * as a single JSON extra. One value object also keeps every connect entry point (UI, tile,
 * widget, boot receiver) from drifting apart.
 */
data class TunnelOptions(
    val killSwitch: Boolean = false,
    val dnsLeakProtection: Boolean = true,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.BLOCK,
    val remoteDns: String = Prefs.DEFAULT_REMOTE_DNS,
    val dnsBootstrap: String = Prefs.DEFAULT_DNS_BOOTSTRAP,
    val mtu: Int = 0,
    val allowLan: Boolean = true,
    val fragmentMode: FragmentMode = FragmentMode.AUTO,
    val tlsFingerprint: String = "chrome",
    val muxEnabled: Boolean = false,
    val iranDirect: Boolean = true,
    val blockQuic: Boolean = true,
    val showServerInNotification: Boolean = true,
    val showSpeedInNotification: Boolean = true,
    val perAppEnabled: Boolean = false,
    val perAppBypass: Boolean = true,
    val perAppList: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("killSwitch", killSwitch)
        put("dnsLeakProtection", dnsLeakProtection)
        put("ipv6Mode", ipv6Mode.name)
        put("remoteDns", remoteDns)
        put("dnsBootstrap", dnsBootstrap)
        put("mtu", mtu)
        put("allowLan", allowLan)
        put("fragmentMode", fragmentMode.name)
        put("tlsFingerprint", tlsFingerprint)
        put("muxEnabled", muxEnabled)
        put("iranDirect", iranDirect)
        put("blockQuic", blockQuic)
        put("notifyServer", showServerInNotification)
        put("notifySpeed", showSpeedInNotification)
        put("perAppEnabled", perAppEnabled)
        put("perAppBypass", perAppBypass)
        put("perAppList", JSONArray(perAppList))
    }.toString()

    companion object {
        fun from(prefs: Prefs) = TunnelOptions(
            killSwitch = prefs.killSwitch,
            dnsLeakProtection = prefs.dnsLeakProtection,
            ipv6Mode = prefs.ipv6Mode,
            remoteDns = prefs.remoteDns,
            dnsBootstrap = prefs.dnsBootstrap,
            mtu = prefs.mtu,
            allowLan = prefs.allowLan,
            fragmentMode = prefs.fragmentMode,
            tlsFingerprint = prefs.tlsFingerprint,
            muxEnabled = prefs.muxEnabled,
            iranDirect = prefs.iranDirect,
            blockQuic = prefs.blockQuic,
            showServerInNotification = prefs.showServerInNotification,
            showSpeedInNotification = prefs.showSpeedInNotification,
            perAppEnabled = prefs.perAppEnabled,
            perAppBypass = prefs.perAppBypassMode,
            perAppList = prefs.perAppSet.toList(),
        )

        /** Tolerant parse: a missing or malformed payload falls back to safe defaults. */
        fun fromJson(json: String?): TunnelOptions {
            val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return TunnelOptions()
            val defaults = TunnelOptions()
            return TunnelOptions(
                killSwitch = o.optBoolean("killSwitch", defaults.killSwitch),
                dnsLeakProtection = o.optBoolean("dnsLeakProtection", defaults.dnsLeakProtection),
                ipv6Mode = runCatching { Ipv6Mode.valueOf(o.optString("ipv6Mode")) }
                    .getOrDefault(defaults.ipv6Mode),
                remoteDns = o.optString("remoteDns").ifBlank { defaults.remoteDns },
                dnsBootstrap = o.optString("dnsBootstrap").ifBlank { defaults.dnsBootstrap },
                mtu = o.optInt("mtu", defaults.mtu),
                allowLan = o.optBoolean("allowLan", defaults.allowLan),
                fragmentMode = runCatching { FragmentMode.valueOf(o.optString("fragmentMode")) }
                    .getOrDefault(defaults.fragmentMode),
                tlsFingerprint = o.optString("tlsFingerprint").ifBlank { defaults.tlsFingerprint },
                muxEnabled = o.optBoolean("muxEnabled", defaults.muxEnabled),
                iranDirect = o.optBoolean("iranDirect", defaults.iranDirect),
                blockQuic = o.optBoolean("blockQuic", defaults.blockQuic),
                showServerInNotification = o.optBoolean("notifyServer", defaults.showServerInNotification),
                showSpeedInNotification = o.optBoolean("notifySpeed", defaults.showSpeedInNotification),
                perAppEnabled = o.optBoolean("perAppEnabled", defaults.perAppEnabled),
                perAppBypass = o.optBoolean("perAppBypass", defaults.perAppBypass),
                perAppList = o.optJSONArray("perAppList")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList(),
            )
        }
    }
}
