package com.manfaz.vpn.core

import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test
    fun advancedNetworkPoliciesAreOptInByDefault() {
        val root = JSONObject(XrayConfig.build(server))
        val dnsRule = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("direct", dnsRule.getString("outboundTag"))
        assertEquals("UseIP", root.getJSONObject("dns").getString("queryStrategy"))
        assertEquals(
            "UseIPv4v6",
            root.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("sockopt")
                .getString("domainStrategy"),
        )
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
    fun blockedIpv6UsesIpv4ForDnsAndProxyEndpoint() {
        val root = JSONObject(XrayConfig.build(server, ipv6Mode = Ipv6Mode.BLOCK))
        assertEquals("UseIPv4", root.getJSONObject("dns").getString("queryStrategy"))
        assertEquals(
            "UseIPv4",
            root.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("sockopt")
                .getString("domainStrategy"),
        )
    }

    @Test
    fun tunneledIpv6KeepsDualStackResolution() {
        val root = JSONObject(XrayConfig.build(server, ipv6Mode = Ipv6Mode.TUNNEL))
        assertEquals("UseIP", root.getJSONObject("dns").getString("queryStrategy"))
        assertEquals(
            "UseIPv4v6",
            root.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("sockopt")
                .getString("domainStrategy"),
        )
    }
}
