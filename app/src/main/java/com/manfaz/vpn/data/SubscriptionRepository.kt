package com.manfaz.vpn.data

import android.content.Context
import com.manfaz.vpn.data.model.Subscription
import com.manfaz.vpn.data.store.Persistence
import com.manfaz.vpn.net.SubscriptionFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SubscriptionRepository {

    private var persistence: Persistence? = null

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()

    fun init(context: Context) {
        val p = Persistence(context.applicationContext)
        persistence = p
        _subs.value = p.loadSubs() ?: emptyList()
    }

    private fun persist() = persistence?.saveSubs(_subs.value)

    fun add(sub: Subscription) { _subs.update { it + sub }; persist() }

    fun remove(id: String) {
        val sub = _subs.value.firstOrNull { it.id == id }
        _subs.update { it.filterNot { s -> s.id == id } }
        persist()
        // Also drop that subscription's servers
        sub?.let { ServerRepository.replaceGroup(it.name, emptyList()) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _subs.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        persist()
    }

    /** Fetch one subscription over the network, replace its servers, update usage. */
    suspend fun update(id: String): String {
        val sub = _subs.value.firstOrNull { it.id == id } ?: return "اشتراک یافت نشد."
        val result = SubscriptionFetcher.fetch(sub)
        if (result.error != null && result.servers.isEmpty()) {
            _subs.update { list -> list.map { if (it.id == id) it.copy(lastError = result.error) else it } }
            persist()
            return result.error
        }
        ServerRepository.replaceGroup(sub.name, result.servers)
        _subs.update { list ->
            list.map {
                if (it.id == id) it.copy(
                    serverCount = result.servers.size,
                    lastUpdated = System.currentTimeMillis(),
                    uploadBytes = result.upload,
                    downloadBytes = result.download,
                    totalBytes = result.total,
                    expireEpoch = result.expire,
                    lastError = null,
                ) else it
            }
        }
        persist()
        return "${result.servers.size} سرور به‌روزرسانی شد."
    }

    /** C#14: refresh subscriptions whose last update is older than [maxAgeHours]. */
    suspend fun autoUpdateIfDue(maxAgeHours: Int) {
        val cutoff = System.currentTimeMillis() - maxAgeHours * 3600_000L
        _subs.value.filter { it.enabled && it.lastUpdated < cutoff }.forEach { update(it.id) }
    }

    suspend fun updateAll(): String {
        val ids = _subs.value.filter { it.enabled }.map { it.id }
        var total = 0
        ids.forEach { id ->
            update(id)
            total += _subs.value.firstOrNull { it.id == id }?.serverCount ?: 0
        }
        return "به‌روزرسانی ${ids.size} اشتراک انجام شد."
    }

    fun replaceAll(list: List<Subscription>) {
        _subs.value = list
        persist()
    }
}
