package com.manfaz.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.data.SubscriptionRepository
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.model.Subscription
import com.manfaz.vpn.core.XrayConfig
import com.manfaz.vpn.core.XrayCore
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.parser.ConfigParser
import com.manfaz.vpn.vpn.ConnStatus
import com.manfaz.vpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val connection = VpnController.state
    val servers = ServerRepository.servers
    val subscriptions = SubscriptionRepository.subs
    val freeConfigs = com.manfaz.vpn.data.FreeConfigRepository.list

    private val _freeTesting = MutableStateFlow(false)
    val freeTesting: StateFlow<Boolean> = _freeTesting.asStateFlow()
    private val _fetchingFree = MutableStateFlow(false)
    val fetchingFree: StateFlow<Boolean> = _fetchingFree.asStateFlow()

    private val _selected = MutableStateFlow<ServerConfig?>(null)
    val selected: StateFlow<ServerConfig?> = _selected.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()

    // Offer to auto-connect to the best server after a failed connection.
    private val _failoverPrompt = MutableStateFlow(false)
    val failoverPrompt: StateFlow<Boolean> = _failoverPrompt.asStateFlow()
    private var lastFailedId: String? = null
    private val failedThisRun = mutableSetOf<String>()
    private var automaticFailovers = 0

    private val prefs = Prefs(app)

    init {
        // C#13: restore the last-connected server, else the lowest-ping one.
        val last = servers.value.firstOrNull { it.id == prefs.lastServerId }
        _selected.value = last ?: servers.value.minByOrNull { it.pingMs ?: Int.MAX_VALUE }

        // Watch connection state: failover offer + free-config fetch on connect.
        viewModelScope.launch {
            connection.collect { state ->
                when (state.status) {
                    com.manfaz.vpn.vpn.ConnStatus.FAILED -> {
                        lastFailedId = state.server?.id
                        state.server?.id?.let(failedThisRun::add)
                        val alternative = bestAlternative()
                        if (prefs.autoFailover && automaticFailovers < prefs.failoverRetries && alternative != null) {
                            automaticFailovers++
                            _selected.value = alternative
                            rememberLast(alternative)
                            snack("سرور پاسخ نداد؛ تلاش با ${alternative.displayLabel}")
                            VpnController.connect(getApplication(), alternative)
                        } else if (alternative != null) {
                            _failoverPrompt.value = true
                        }
                    }
                    com.manfaz.vpn.vpn.ConnStatus.CONNECTED -> {
                        automaticFailovers = 0; failedThisRun.clear(); maybeFetchFreeConfigs()
                    }
                    com.manfaz.vpn.vpn.ConnStatus.DISCONNECTED -> {
                        automaticFailovers = 0; failedThisRun.clear()
                    }
                    else -> {}
                }
            }
        }
        // Keep selection valid after an encrypted backup is restored or a server is removed.
        viewModelScope.launch {
            servers.collect { current ->
                val selectedId = _selected.value?.id
                if (selectedId == null || current.none { it.id == selectedId }) {
                    _selected.value = current.firstOrNull { it.id == prefs.lastServerId }
                        ?: current.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
                }
            }
        }
    }

    /** Fetch free configs from Telegram (through the tunnel) every 6h, or on connect if due. */
    private fun maybeFetchFreeConfigs() {
        val sixHours = 6 * 3600_000L
        if (_fetchingFree.value) return
        if (System.currentTimeMillis() - prefs.lastFreeFetch < sixHours &&
            com.manfaz.vpn.data.FreeConfigRepository.list.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _fetchingFree.value = true
            val checkpoints = com.manfaz.vpn.net.FreeConfigFetcher.channels.associateWith {
                prefs.freeChannelCheckpoint(it)
            }
            val result = runCatching {
                com.manfaz.vpn.net.FreeConfigFetcher.fetchAll(XrayConfig.SOCKS_PORT, checkpoints)
            }.getOrNull()
            if (result != null) {
                result.newestPostByChannel.forEach { (channel, postId) ->
                    prefs.setFreeChannelCheckpoint(channel, postId)
                }
                val added = com.manfaz.vpn.data.FreeConfigRepository.merge(result.configs)
                prefs.lastFreeFetch = System.currentTimeMillis()
                if (added.isNotEmpty()) {
                    freeSnack("${added.size} کانفیگ جدید پیدا شد؛ پس از قطع VPN تست می‌شوند.")
                }
            }
            _fetchingFree.value = false
        }
    }

    /** Manual refresh from the Free Configs screen. */
    fun refreshFreeConfigs() {
        if (connection.value.status != ConnStatus.CONNECTED) {
            freeSnack("برای دریافت کانفیگ رایگان ابتدا متصل شوید."); return
        }
        prefs.lastFreeFetch = 0L
        maybeFetchFreeConfigs()
    }

    private fun bestAlternative(): ServerConfig? =
        servers.value
            .filter { it.id !in failedThisRun && !it.address.endsWith("example.com") }
            .filter { it.pingMs != null }
            .minByOrNull { it.pingMs ?: Int.MAX_VALUE }
            ?: servers.value.firstOrNull { it.id !in failedThisRun && !it.address.endsWith("example.com") }

    fun dismissFailover() { _failoverPrompt.value = false }

    /** User accepted failover: select the best server (caller runs the consent-aware connect). */
    fun selectBest() {
        _failoverPrompt.value = false
        val best = bestAlternative() ?: return
        _selected.value = best
        rememberLast(best)
    }

    fun consumeSnack() { _snack.value = null }
    private fun snack(msg: String) { _snack.value = msg }
    private fun freeSnack(msg: String) {
        if (prefs.freeConfigsUnlocked) snack(msg)
    }

    // Feature #4: clipboard config detection
    private val _clipboardPrompt = MutableStateFlow<String?>(null)
    val clipboardPrompt: StateFlow<String?> = _clipboardPrompt.asStateFlow()
    private var lastClipboardSeen: String = ""

    fun checkClipboard(context: android.content.Context) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim() ?: return
        if (text.isBlank() || text == lastClipboardSeen) return
        val looksImportable = Regex("(?i)(vmess|vless|trojan|ss|hysteria2?|tuic)://").containsMatchIn(text) ||
            (text.startsWith("http", true) && text.contains("/"))
        if (looksImportable) { lastClipboardSeen = text; _clipboardPrompt.value = text }
    }

    fun importClipboard() {
        _clipboardPrompt.value?.let { snack(importText(it)) }
        _clipboardPrompt.value = null
    }

    fun dismissClipboard() { lastClipboardSeen = _clipboardPrompt.value ?: ""; _clipboardPrompt.value = null }

    fun select(server: ServerConfig) { _selected.value = server }

    private fun rememberLast(server: ServerConfig) { prefs.lastServerId = server.id }

    fun toggleConnection() {
        val target = _selected.value ?: servers.value.firstOrNull()
        target?.let { rememberLast(it) }
        VpnController.toggle(getApplication(), target)
    }

    /** Quick actions are deliberately restricted to the regular server repository. */
    private fun quickPool(): List<ServerConfig> = servers.value

    /** Select the fastest server WITHOUT connecting (caller triggers the consent-aware connect). */
    fun pickFastest() {
        val fastest = quickPool().minByOrNull { it.pingMs ?: Int.MAX_VALUE } ?: return
        _selected.value = fastest
        rememberLast(fastest)
    }

    fun pickRandom() {
        val s = quickPool().randomOrNull() ?: return
        _selected.value = s
        rememberLast(s)
    }

    fun disconnectNow() = VpnController.disconnect(getApplication())

    /** Connect to the currently selected server (switching if already connected). */
    fun connectSelected() {
        val target = _selected.value ?: return
        rememberLast(target)
        VpnController.connect(getApplication(), target)
    }

    fun toggleFavorite(id: String) = ServerRepository.toggleFavorite(id)

    fun removeServer(id: String) {
        if (selected.value?.id == id) _selected.value = null
        ServerRepository.remove(id)
        snack("سرور حذف شد.")
    }

    // ---- Real end-to-end proxy latency, tested concurrently with bounded native work ----
    fun testAll() = testServers(servers.value)
    fun testOne(server: ServerConfig) = testServers(listOf(server))

    private fun testServers(list: List<ServerConfig>) {
        if (list.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _testing.value = true
            try {
                coroutineScope {
                    val gate = Semaphore(4)
                    val results = list.map { server ->
                        async(Dispatchers.IO) {
                            val latency = gate.withPermit {
                                if (!XrayConfig.isSupportedByXray(server.protocol)) return@withPermit null
                                XrayCore.measureDelay(
                                    app,
                                    XrayConfig.build(
                                        server = server,
                                        remoteDns = prefs.remoteDns,
                                        dnsLeakProtection = prefs.dnsLeakProtection,
                                        allowLan = prefs.allowLan,
                                        ipv6Mode = prefs.ipv6Mode,
                                    ),
                                )
                            }
                            server.id to latency
                        }
                    }.awaitAll()
                    ServerRepository.updatePings(results.toMap())
                }
                snack("تست واقعی سرورها کامل شد.")
            } finally {
                _testing.value = false
            }
        }
    }

    // ---- Free configs (Telegram) ----
    fun testFreeAll() {
        if (connection.value.status != ConnStatus.DISCONNECTED) {
            freeSnack("برای تست واقعی کانفیگ‌های رایگان، ابتدا VPN را قطع کنید.")
            return
        }
        val list = freeConfigs.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            _freeTesting.value = true
            val removed = downloadTestAndFilter(list)
            _freeTesting.value = false
            freeSnack(if (removed > 0) "تست کامل شد؛ $removed کانفیگ بدون دانلود حذف شد."
                  else "تست کانفیگ‌های رایگان کامل شد.")
        }
    }

    /**
     * Real download test (≥1 KB through the config). Configs that connect but don't actually
     * download (upload-only/broken) fail and are REMOVED from the free list. Returns #removed.
     */
    private suspend fun downloadTestAndFilter(list: List<ServerConfig>): Int {
        val app = getApplication<Application>()
        val dead = mutableListOf<String>()
        for (server in list) {
            if (connection.value.status != ConnStatus.DISCONNECTED) break
            val ping = kotlinx.coroutines.withContext(Dispatchers.IO) {
                if (XrayConfig.isSupportedByXray(server.protocol)) {
                    XrayCore.measureDelay(app, XrayConfig.build(server), XrayCore.DOWNLOAD_TEST_URL)
                } else null
            }
            if (ping == null) dead.add(server.id)
            else com.manfaz.vpn.data.FreeConfigRepository.updatePing(server.id, ping)
        }
        com.manfaz.vpn.data.FreeConfigRepository.removeAll(dead)
        return dead.size
    }

    fun toggleFreeFavorite(id: String) = com.manfaz.vpn.data.FreeConfigRepository.toggleFavorite(id)
    fun removeFree(id: String) {
        if (selected.value?.id == id) _selected.value = null
        com.manfaz.vpn.data.FreeConfigRepository.remove(id)
    }
    fun staleFreeCount() = com.manfaz.vpn.data.FreeConfigRepository.staleCount()
    fun clearStaleFree() {
        com.manfaz.vpn.data.FreeConfigRepository.clearStale()
        freeSnack("کانفیگ‌های بدون پینگ حذف شدند.")
    }

    // ---- Import ----
    fun importText(raw: String): String {
        val trimmed = raw.trim()
        // A lone http(s) URL with a path/query is a subscription link, not an HTTP-proxy config.
        if (isSubscriptionUrl(trimmed)) {
            val host = runCatching { android.net.Uri.parse(trimmed).host }.getOrNull() ?: "اشتراک"
            addSubscription(host, trimmed)
            markImported(trimmed)
            return "در حال دریافت اشتراک…"
        }
        val result = ConfigParser.parseMany(raw)
        ServerRepository.addAll(result.servers)
        val msg = when {
            result.servers.isEmpty() && result.errors.isEmpty() -> "هیچ کانفیگ معتبری پیدا نشد."
            result.servers.isEmpty() -> "کانفیگ نامعتبر است. (${result.errors.size} خطا)"
            result.errors.isEmpty() -> "${result.servers.size} سرور با موفقیت اضافه شد."
            else -> "${result.servers.size} سرور اضافه شد، ${result.errors.size} مورد نامعتبر بود."
        }
        if (result.servers.isNotEmpty()) markImported(trimmed)
        return msg
    }

    /** Prevent the clipboard suggestion from repeating immediately after a successful import. */
    private fun markImported(text: String) {
        lastClipboardSeen = text
        _clipboardPrompt.value = null
    }

    /** Single https?:// URL that has a path or query (a subscription), not a bare host:port proxy. */
    private fun isSubscriptionUrl(text: String): Boolean {
        if (text.contains("\n") || text.contains(" ")) return false
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull() ?: return false
        val hasPathOrQuery = !uri.path.isNullOrBlank() && uri.path != "/" || !uri.query.isNullOrBlank()
        return hasPathOrQuery && uri.userInfo == null
    }

    // ---- Subscriptions ----
    fun addSubscription(name: String, url: String) {
        val cleanName = name.ifBlank { "اشتراک" }
        val sub = Subscription(name = cleanName, url = url.trim())
        SubscriptionRepository.add(sub)
        markImported(url.trim())
        viewModelScope.launch { snack(SubscriptionRepository.update(sub.id)) }
    }

    fun updateSubscription(id: String) {
        viewModelScope.launch { snack(SubscriptionRepository.update(id)) }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch { snack(SubscriptionRepository.updateAll()) }
    }

    fun removeSubscription(id: String) = SubscriptionRepository.remove(id)
    fun toggleSubscription(id: String, enabled: Boolean) = SubscriptionRepository.setEnabled(id, enabled)
}
