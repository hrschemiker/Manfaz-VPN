package com.manfaz.vpn.data

import android.content.Context
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.store.Persistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Store for free configs scraped from Telegram channels. Keeps at most [MAX] entries,
 * preferring the best-ping ones. Tracks how long each has had no ping so the UI can
 * offer to clear the stale ones after [STALE_DAYS].
 */
object FreeConfigRepository {

    const val MAX = 400
    const val STALE_DAYS = 3
    private const val STALE_MS = STALE_DAYS * 24L * 3600_000L

    private var persistence: Persistence? = null
    private val seenKeys = LinkedHashSet<String>()
    private val _list = MutableStateFlow<List<ServerConfig>>(emptyList())
    val list: StateFlow<List<ServerConfig>> = _list.asStateFlow()

    fun init(context: Context) {
        val p = Persistence(context.applicationContext)
        persistence = p
        _list.value = p.loadFreeConfigs() ?: emptyList()
        seenKeys.clear()
        seenKeys.addAll(p.loadSeenFreeKeys())
        // Upgrade compatibility: everything already in the list has already been processed.
        seenKeys.addAll(_list.value.map(::key))
        persistSeen()
    }

    private fun persist() = persistence?.saveFreeConfigs(_list.value)
    private fun persistSeen() = persistence?.saveSeenFreeKeys(seenKeys.takeLast(MAX_SEEN))
    private fun key(s: ServerConfig) = "${s.protocol}|${s.address}|${s.port}|${s.uuid}${s.password}"

    /** Merge freshly-scraped configs, de-dup, keep best [MAX] by ping. Returns the new entries. */
    fun merge(fresh: List<ServerConfig>): List<ServerConfig> {
        val newlyAdded = mutableListOf<ServerConfig>()
        _list.update { current ->
            val byKey = current.associateBy { key(it) }.toMutableMap()
            fresh.forEach { f ->
                val fingerprint = key(f)
                if (seenKeys.add(fingerprint) && !byKey.containsKey(fingerprint)) {
                    byKey[fingerprint] = f
                    newlyAdded += f
                }
            }
            byKey.values
                .sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Int.MAX_VALUE }))
                .take(MAX)
        }
        persist()
        persistSeen()
        return newlyAdded
    }

    /** Remove configs that failed the real-download test. */
    fun removeAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        _list.update { it.filterNot { s -> s.id in set } }
        persist()
    }

    fun updatePing(id: String, ping: Int?) {
        _list.update { list ->
            list.map {
                if (it.id != id) it
                else if (ping != null) it.copy(pingMs = ping, noPingSinceMs = 0L)
                else it.copy(pingMs = null,
                    noPingSinceMs = if (it.noPingSinceMs == 0L) System.currentTimeMillis() else it.noPingSinceMs)
            }
        }
        persist()
    }

    fun toggleFavorite(id: String) {
        _list.update { l -> l.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }
        persist()
    }

    fun remove(id: String) { _list.update { it.filterNot { s -> s.id == id } }; persist() }

    /** Configs that have had no ping for longer than [STALE_DAYS]. */
    fun staleCount(): Int {
        val now = System.currentTimeMillis()
        return _list.value.count { it.pingMs == null && it.noPingSinceMs in 1 until (now - STALE_MS) }
    }

    fun clearStale() {
        val now = System.currentTimeMillis()
        _list.update { it.filterNot { s -> s.pingMs == null && s.noPingSinceMs in 1 until (now - STALE_MS) } }
        persist()
    }

    private fun <T> Collection<T>.takeLast(count: Int): List<T> =
        if (size <= count) toList() else drop(size - count)

    private const val MAX_SEEN = 5_000
}
