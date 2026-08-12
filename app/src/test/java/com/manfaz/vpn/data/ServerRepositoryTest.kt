package com.manfaz.vpn.data

import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {

    @Test
    fun replaceGroupPreservesIdFavoriteAndPingForSurvivingServers() {
        ServerRepository.clear()
        val old = ServerConfig(
            id = "stable-id", name = "Edge 1", group = "sub",
            protocol = Protocol.VLESS, address = "1.2.3.4", port = 443,
            favorite = true, pingMs = 42,
        )
        ServerRepository.addAll(listOf(old))

        val fresh = listOf(
            ServerConfig(name = "Edge 1", group = "sub", protocol = Protocol.VLESS, address = "1.2.3.4", port = 443),
            ServerConfig(name = "New Server", group = "sub", protocol = Protocol.VLESS, address = "9.9.9.9", port = 443),
        )
        ServerRepository.replaceGroup("sub", fresh)

        val servers = ServerRepository.servers.value
        assertEquals(2, servers.size)
        // The surviving server keeps its id so "last used server", the current
        // selection, and the active highlight survive a subscription refresh.
        val surviving = servers.first { it.address == "1.2.3.4" }
        assertEquals("stable-id", surviving.id)
        assertTrue(surviving.favorite)
        assertEquals(42, surviving.pingMs)
        // A genuinely new server gets its own fresh id.
        val added = servers.first { it.address == "9.9.9.9" }
        assertNotEquals("stable-id", added.id)
    }

    @Test
    fun replaceGroupDoesNotCollideIdsOnDuplicateLines() {
        ServerRepository.clear()
        ServerRepository.addAll(listOf(
            ServerConfig(name = "dup", group = "sub", protocol = Protocol.VLESS, address = "5.6.7.8", port = 443),
        ))
        // A malformed subscription payload containing the same line twice must not
        // produce two servers sharing one id (LazyColumn key collision).
        val line = ServerConfig(name = "dup", group = "sub", protocol = Protocol.VLESS, address = "5.6.7.8", port = 443)
        ServerRepository.replaceGroup("sub", listOf(line, line))

        val ids = ServerRepository.servers.value.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun replaceGroupDoesNotReuseIdentityWhenCredentialsChanged() {
        ServerRepository.clear()
        ServerRepository.addAll(listOf(ServerConfig(
            id = "old-id", name = "old", group = "sub", protocol = Protocol.VLESS,
            address = "edge.example.com", port = 443, uuid = "old-uuid", favorite = true,
        )))

        ServerRepository.replaceGroup("sub", listOf(ServerConfig(
            name = "new", group = "sub", protocol = Protocol.VLESS,
            address = "edge.example.com", port = 443, uuid = "new-uuid",
        )))

        val refreshed = ServerRepository.servers.value.single()
        assertNotEquals("old-id", refreshed.id)
        assertTrue(!refreshed.favorite)
    }
}
