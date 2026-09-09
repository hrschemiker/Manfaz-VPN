package com.manfaz.vpn.core

import com.manfaz.vpn.data.FragmentMode
import com.manfaz.vpn.data.Ipv6Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The options object crosses a process boundary, so a field that fails to survive the round
 * trip silently downgrades the tunnel (for example by losing the kill switch) with no error.
 */
class TunnelOptionsTest {

    @Test
    fun everyFieldSurvivesTheProcessBoundary() {
        val original = TunnelOptions(
            killSwitch = true,
            dnsLeakProtection = false,
            ipv6Mode = Ipv6Mode.TUNNEL,
            remoteDns = "https://dns.example/dns-query",
            dnsBootstrap = "9.9.9.9",
            mtu = 1400,
            allowLan = false,
            fragmentMode = FragmentMode.AGGRESSIVE,
            tlsFingerprint = "firefox",
            muxEnabled = true,
            iranDirect = false,
            blockQuic = false,
            showServerInNotification = false,
            showSpeedInNotification = false,
            perAppEnabled = true,
            perAppBypass = false,
            perAppList = listOf("com.example.a", "com.example.b"),
        )
        assertEquals(original, TunnelOptions.fromJson(original.toJson()))
    }

    @Test
    fun aMissingOrCorruptPayloadFallsBackToSafeDefaults() {
        val defaults = TunnelOptions()
        assertEquals(defaults, TunnelOptions.fromJson(null))
        assertEquals(defaults, TunnelOptions.fromJson(""))
        assertEquals(defaults, TunnelOptions.fromJson("not json at all"))
        // An unknown enum value must not crash the core process either.
        assertEquals(
            defaults.ipv6Mode,
            TunnelOptions.fromJson("""{"ipv6Mode":"SOMETHING_ELSE"}""").ipv6Mode,
        )
    }
}
