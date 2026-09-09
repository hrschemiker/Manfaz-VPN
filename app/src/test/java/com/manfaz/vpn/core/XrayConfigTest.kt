package com.manfaz.vpn.core

import com.manfaz.vpn.data.FragmentMode
import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigTest {

    private val server = ServerConfig(
        name = "Test",
        protocol = Protocol.SHADOWSOCKS,
        address = "example.org",
        port = 443,
        password = "secret",
        method = "aes-256-gcm",
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun rules(root: JSONObject) = root.getJSONObject("routing").getJSONArray("rules").objects()

    private fun outbound(root: JSONObject, tag: String): JSONObject? =
        root.getJSONArray("outbounds").objects().firstOrNull { it.optString("tag") == tag }

    @Test
    fun delayProbeHasIndependentFallbackEndpoint() {
        assertTrue(XrayCore.DELAY_TEST_URLS.size >= 2)
        assertEquals(XrayCore.DELAY_TEST_URLS.size, XrayCore.DELAY_TEST_URLS.distinct().size)
        assertTrue(
            XrayCore.DELAY_TEST_URLS.all {
                it.startsWith("https://") || it.startsWith("http://")
            },
        )
    }

    /**
     * geoip.dat/geosite.dat are not bundled with the app. Xray refuses an entire configuration
     * that references a missing geo file, so a single `geoip:private` rule would stop every
     * connection from starting.
     */
    @Test
    fun configNeverReferencesUnbundledGeoAssets() {
        val json = XrayConfig.build(server, TunnelOptions(iranDirect = true, blockQuic = true))
        assertFalse(json.contains("geoip:"))
        assertFalse(json.contains("geosite:"))
    }

    @Test
    fun privateRangesAreMatchedByExplicitCidrs() {
        val root = JSONObject(XrayConfig.build(server))
        val lanRule = rules(root).first { it.has("ip") }
        val cidrs = lanRule.getJSONArray("ip").strings()
        assertTrue(cidrs.contains("192.168.0.0/16"))
        assertTrue(cidrs.contains("10.0.0.0/8"))
        assertTrue(cidrs.contains("127.0.0.0/8"))
        assertEquals("direct", lanRule.getString("outboundTag"))
    }

    /**
     * The DNS module's own upstream queries must be routed before the catch-all port-53 rule,
     * otherwise Xray hands its own lookups back to the DNS outbound and never resolves anything.
     */
    @Test
    fun dnsModuleTrafficIsRoutedBeforeThePortFiftyThreeRule() {
        val all = rules(JSONObject(XrayConfig.build(server)))
        val firstGenericDnsRule = all.indexOfFirst {
            !it.has("inboundTag") && it.optString("port") == "53"
        }
        val moduleRules = all.withIndex().filter { (_, rule) -> rule.has("inboundTag") }
        assertTrue(moduleRules.isNotEmpty())
        assertTrue(moduleRules.all { (index, _) -> index < firstGenericDnsRule })
        // Plain DNS goes out directly so it answers before the tunnel exists.
        val plain = moduleRules.first { (_, rule) -> rule.optString("port") == "53" }.value
        assertEquals("direct", plain.getString("outboundTag"))
        // Everything else the module does (DoH) travels inside the tunnel.
        val encrypted = moduleRules.first { (_, rule) -> !rule.has("port") }.value
        assertEquals("proxy", encrypted.getString("outboundTag"))
    }

    @Test
    fun dnsLeakProtectionSendsDeviceQueriesToTheDnsModule() {
        val protectedRoot = JSONObject(XrayConfig.build(server, TunnelOptions(dnsLeakProtection = true)))
        val leaky = JSONObject(XrayConfig.build(server, TunnelOptions(dnsLeakProtection = false)))
        fun deviceDnsTag(root: JSONObject) = rules(root)
            .first { !it.has("inboundTag") && it.optString("port") == "53" }
            .getString("outboundTag")
        assertEquals("dns-out", deviceDnsTag(protectedRoot))
        assertEquals("direct", deviceDnsTag(leaky))
    }

    /** Resolving the proxy's own hostname through the proxy would be circular. */
    @Test
    fun proxyHostnameAndDohHostResolveViaTheNumericBootstrap() {
        val root = JSONObject(
            XrayConfig.build(
                server,
                TunnelOptions(
                    remoteDns = "https://dns.example/dns-query",
                    dnsBootstrap = "9.9.9.9",
                ),
            ),
        )
        val dns = root.getJSONObject("dns")
        val pinned = dns.getJSONArray("servers").getJSONObject(0)
        assertEquals("9.9.9.9", pinned.getString("address"))
        val domains = pinned.getJSONArray("domains").strings()
        assertTrue(domains.contains("full:example.org"))
        assertTrue(domains.contains("full:dns.example"))
        assertTrue(pinned.getBoolean("skipFallback"))
        assertEquals("9.9.9.9", dns.getJSONObject("hosts").getString("dns.example"))
        // The remote resolver stays the catch-all for everything else.
        val servers = dns.getJSONArray("servers")
        assertEquals("https://dns.example/dns-query", servers.getString(servers.length() - 1))
    }

    @Test
    fun iranianSitesGoDirectWhenEnabledAndThroughTheTunnelWhenNot() {
        val on = JSONObject(XrayConfig.build(server, TunnelOptions(iranDirect = true)))
        val iranRule = rules(on).first { it.has("domain") }
        assertEquals("direct", iranRule.getString("outboundTag"))
        assertTrue(iranRule.getJSONArray("domain").strings().any { it.endsWith("\\.ir$") })

        val off = JSONObject(XrayConfig.build(server, TunnelOptions(iranDirect = false)))
        assertTrue(rules(off).none { it.has("domain") })
    }

    @Test
    fun quicIsBlockedOnlyWhenRequested() {
        fun quicRule(options: TunnelOptions) = rules(JSONObject(XrayConfig.build(server, options)))
            .firstOrNull { it.optString("network") == "udp" && it.optString("port") == "443" }
        assertEquals("block", quicRule(TunnelOptions(blockQuic = true))?.getString("outboundTag"))
        assertNull(quicRule(TunnelOptions(blockQuic = false)))
    }

    @Test
    fun regularRoutingDoesNotForceSecondDnsLookup() {
        val root = JSONObject(XrayConfig.build(server))
        assertEquals("AsIs", root.getJSONObject("routing").getString("domainStrategy"))
        assertTrue(
            root.getJSONArray("inbounds").getJSONObject(0)
                .getJSONObject("sniffing").getBoolean("routeOnly"),
        )
    }

    @Test
    fun latencyProbeConfigOmitsTheLocalInbound() {
        val probe = JSONObject(XrayConfig.build(server, TunnelOptions(), forLatencyProbe = true))
        assertFalse(probe.has("inbounds"))
        assertNotNull(outbound(probe, "proxy"))
    }

    // ---------------------------------------------------------------- fragment

    private val tlsServer = server.copy(
        protocol = Protocol.VLESS,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = "ws",
        security = "tls",
        host = "edge.example.com",
    )

    private val realityServer = server.copy(
        protocol = Protocol.VLESS,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = "tcp",
        security = "reality",
        sni = "www.microsoft.com",
        publicKey = "abc",
        shortId = "ff",
    )

    private fun dialerProxyOf(root: JSONObject): String? =
        outbound(root, "proxy")!!.getJSONObject("streamSettings")
            .getJSONObject("sockopt").optString("dialerProxy").takeIf { it.isNotEmpty() }

    @Test
    fun autoFragmentSplitsPlainTlsButLeavesRealityAlone() {
        val tls = JSONObject(XrayConfig.build(tlsServer, TunnelOptions(fragmentMode = FragmentMode.AUTO)))
        assertNotNull(outbound(tls, "fragment"))
        assertEquals("fragment", dialerProxyOf(tls))
        assertEquals(
            "tlshello",
            outbound(tls, "fragment")!!.getJSONObject("settings")
                .getJSONObject("fragment").getString("packets"),
        )

        val reality = JSONObject(
            XrayConfig.build(realityServer, TunnelOptions(fragmentMode = FragmentMode.AUTO)),
        )
        assertNull(outbound(reality, "fragment"))
        assertNull(dialerProxyOf(reality))
    }

    @Test
    fun alwaysFragmentCoversRealityToo() {
        val reality = JSONObject(
            XrayConfig.build(realityServer, TunnelOptions(fragmentMode = FragmentMode.ALWAYS)),
        )
        assertNotNull(outbound(reality, "fragment"))
        assertEquals("fragment", dialerProxyOf(reality))
    }

    @Test
    fun aggressiveFragmentAddsNoisePackets() {
        val root = JSONObject(
            XrayConfig.build(tlsServer, TunnelOptions(fragmentMode = FragmentMode.AGGRESSIVE)),
        )
        val settings = outbound(root, "fragment")!!.getJSONObject("settings")
        assertEquals("1-3", settings.getJSONObject("fragment").getString("packets"))
        assertTrue(settings.getJSONArray("noises").length() > 0)
    }

    @Test
    fun fragmentIsAbsentWhenDisabled() {
        val root = JSONObject(XrayConfig.build(tlsServer, TunnelOptions(fragmentMode = FragmentMode.OFF)))
        assertNull(outbound(root, "fragment"))
        assertNull(dialerProxyOf(root))
    }

    // ------------------------------------------------------------------- misc

    @Test
    fun blockedIpv6UsesIpv4ForDnsAndProxyEndpoint() {
        val root = JSONObject(XrayConfig.build(server, TunnelOptions(ipv6Mode = Ipv6Mode.BLOCK)))
        assertEquals("UseIPv4", root.getJSONObject("dns").getString("queryStrategy"))
        assertEquals(
            "UseIPv4",
            outbound(root, "proxy")!!.getJSONObject("streamSettings")
                .getJSONObject("sockopt").getString("domainStrategy"),
        )
    }

    @Test
    fun tunneledIpv6KeepsDualStackResolution() {
        val root = JSONObject(XrayConfig.build(server, TunnelOptions(ipv6Mode = Ipv6Mode.TUNNEL)))
        assertEquals("UseIP", root.getJSONObject("dns").getString("queryStrategy"))
        assertEquals(
            "UseIPv4v6",
            outbound(root, "proxy")!!.getJSONObject("streamSettings")
                .getJSONObject("sockopt").getString("domainStrategy"),
        )
    }

    @Test
    fun tlsUsesHostAsSniFallbackAndTheConfiguredFingerprint() {
        val tls = JSONObject(XrayConfig.build(tlsServer, TunnelOptions(tlsFingerprint = "firefox")))
            .let { outbound(it, "proxy")!! }
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("edge.example.com", tls.getString("serverName"))
        assertEquals("firefox", tls.getString("fingerprint"))
        assertFalse(tls.getBoolean("allowInsecure"))
    }

    @Test
    fun aConfigsOwnFingerprintWinsOverThePreference() {
        val pinned = tlsServer.copy(fingerprint = "safari")
        val tls = JSONObject(XrayConfig.build(pinned, TunnelOptions(tlsFingerprint = "firefox")))
            .let { outbound(it, "proxy")!! }
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("safari", tls.getString("fingerprint"))
    }

    @Test
    fun realityCarriesPostQuantumVerificationWhenTheLinkProvidesIt() {
        val pq = realityServer.copy(mldsa65Verify = "pq-key", spiderX = "/probe")
        val reality = JSONObject(XrayConfig.build(pq))
            .let { outbound(it, "proxy")!! }
            .getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("pq-key", reality.getString("mldsa65Verify"))
        assertEquals("/probe", reality.getString("spiderX"))

        val plain = JSONObject(XrayConfig.build(realityServer))
            .let { outbound(it, "proxy")!! }
            .getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertFalse(plain.has("mldsa65Verify"))
    }

    @Test
    fun muxIsSkippedForVisionFlowWhichTheCoreRejects() {
        val vision = realityServer.copy(flow = "xtls-rprx-vision")
        val withVision = JSONObject(XrayConfig.build(vision, TunnelOptions(muxEnabled = true)))
        assertFalse(outbound(withVision, "proxy")!!.has("mux"))

        val withoutVision = JSONObject(XrayConfig.build(tlsServer, TunnelOptions(muxEnabled = true)))
        assertTrue(outbound(withoutVision, "proxy")!!.getJSONObject("mux").getBoolean("enabled"))
    }

    @Test
    fun vmessCompatibilityFieldsReachXrayConfig() {
        val vmess = server.copy(
            protocol = Protocol.VMESS,
            uuid = "11111111-1111-1111-1111-111111111111",
            alterId = 64,
            encryption = "aes-128-gcm",
        )
        val user = JSONObject(XrayConfig.build(vmess))
            .let { outbound(it, "proxy")!! }
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals(64, user.getInt("alterId"))
        assertEquals("aes-128-gcm", user.getString("security"))
    }

    @Test
    fun unknownTransportFallsBackToTcpInsteadOfBreakingTheCore() {
        val exotic = tlsServer.copy(network = "quic")
        val stream = JSONObject(XrayConfig.build(exotic))
            .let { outbound(it, "proxy")!! }
            .getJSONObject("streamSettings")
        assertEquals("tcp", stream.getString("network"))
    }
}
