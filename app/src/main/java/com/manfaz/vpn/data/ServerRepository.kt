package com.manfaz.vpn.data

import android.content.Context
import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.store.Persistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Server store backed by JSON-file persistence. Loads on [init]; every mutation
 * re-persists. Seeded with sample servers only on first run (no saved file).
 */
object ServerRepository {

    private var persistence: Persistence? = null

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    fun init(context: Context) {
        val p = Persistence(context.applicationContext)
        persistence = p
        _servers.value = p.loadServers() ?: emptyList()
    }

    private fun persist() = persistence?.saveServers(_servers.value)

    fun addAll(list: List<ServerConfig>) {
        if (list.isEmpty()) return
        _servers.update { it + list }
        persist()
    }

    /** Replace all servers belonging to a subscription group with a fresh set. */
    fun replaceGroup(group: String, fresh: List<ServerConfig>) {
        _servers.update { current ->
            val kept = current.filterNot { it.group == group }
            // preserve favorites/ping for servers that still exist (match by address:port)
            val old = current.filter { it.group == group }.associateBy { "${it.address}:${it.port}" }
            val merged = fresh.map { s ->
                old["${s.address}:${s.port}"]?.let { s.copy(favorite = it.favorite, pingMs = it.pingMs) } ?: s
            }
            kept + merged
        }
        persist()
    }

    fun remove(id: String) { _servers.update { it.filterNot { s -> s.id == id } }; persist() }

    fun update(server: ServerConfig) {
        _servers.update { list -> list.map { if (it.id == server.id) server else it } }
        persist()
    }

    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    fun toggleFavorite(id: String) {
        _servers.update { list -> list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }
        persist()
    }

    fun updatePing(id: String, ping: Int?) {
        _servers.update { list -> list.map { if (it.id == id) it.copy(pingMs = ping) else it } }
        persist()
    }

    fun clear() { _servers.update { emptyList() }; persist() }

    fun replaceAll(list: List<ServerConfig>) {
        _servers.value = list
        persist()
    }
}
