package com.manfaz.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manfaz.vpn.core.TunnelOptions
import com.manfaz.vpn.core.XrayConfig
import com.manfaz.vpn.core.XrayCore
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.data.SubscriptionRepository
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.model.Subscription
import com.manfaz.vpn.data.parser.ConfigParser
import com.manfaz.vpn.net.Pinger
import com.manfaz.vpn.vpn.ConnStatus
import com.manfaz.vpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** Outcome of an import attempt, so callers never have to pattern-match a message. */
    data class ImportResult(val success: Boolean, val message: String)

    /** Live progress of a latency run: `done` of `total`, plus the phase being executed. */
    data class TestProgress(val done: Int = 0, val total: Int = 0, val deepPhase: Boolean = false) {
        val running: Boolean get() = total > 0
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    val connection = VpnController.state
    val servers = ServerRepository.servers
    val subscriptions = SubscriptionRepository.subs

    private val _selected = MutableStateFlow<ServerConfig?>(null)
    val selected: StateFlow<ServerConfig?> = _selected.asStateFlow()

    private val _testProgress = MutableStateFlow(TestProgress())
    val testProgress: StateFlow<TestProgress> = _testProgress.asStateFlow()

    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()

    private val _updatingSubscriptions = MutableStateFlow(false)
    val updatingSubscriptions: StateFlow<Boolean> = _updatingSubscriptions.asStateFlow()

    // Offer to auto-connect to the best server after a failed connection.
    private val _failoverPrompt = MutableStateFlow(false)
    val failoverPrompt: StateFlow<Boolean> = _failoverPrompt.asStateFlow()
    private val failedThisRun = mutableSetOf<String>()
    private var automaticFailovers = 0

    private var testJob: Job? = null
    private val prefs = Prefs(app)

    /**
     * How many of the fastest reachable servers get a full end-to-end probe after the quick
     * handshake sweep. Every deep probe starts an isolated native core, so the budget is what
     * keeps a 300-server subscription from taking ten minutes to rank.
     */
    private val deepProbeBudget = 12

    init {
        // Restore the last-connected server, else the lowest-ping one.
        val last = servers.value.firstOrNull { it.id == prefs.lastServerId }
        _selected.value = last ?: servers.value.minByOrNull { it.pingMs ?: Int.MAX_VALUE }

        viewModelScope.launch {
            connection.collect { state ->
                when (state.status) {
                    ConnStatus.FAILED -> {
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
                    ConnStatus.CONNECTED, ConnStatus.DISCONNECTED -> {
                        automaticFailovers = 0
                        failedThisRun.clear()
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

    private fun bestAlternative(): ServerConfig? =
        servers.value.filter { it.id !in failedThisRun }
            .let { candidates ->
                candidates.filter { it.pingMs != null }.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
                    ?: candidates.firstOrNull()
            }

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

    // ---- Clipboard config detection ----
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
        _clipboardPrompt.value?.let { snack(importText(it).message) }
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

    /** Kill-switch escape hatch, mirrored by the ongoing notification's action. */
    fun releaseKillSwitch() {
        VpnController.releaseKillSwitch(getApplication())
        snack("اینترنت آزاد شد. محافظت VPN غیرفعال است.")
    }

    /** Select the fastest server WITHOUT connecting (caller triggers the consent-aware connect). */
    fun pickFastest() {
        val fastest = servers.value.filter { it.pingMs != null }.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
            ?: servers.value.firstOrNull() ?: return
        _selected.value = fastest
        rememberLast(fastest)
    }

    fun pickRandom() {
        val s = servers.value.randomOrNull() ?: return
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

    // ---------------------------------------------------------------- latency

    /**
     * Two-phase latency run.
     *
     * Phase 1 opens a plain TCP handshake to every endpoint at high concurrency. It costs
     * almost nothing, finishes in seconds even for a large subscription, and immediately
     * separates reachable servers from dead ones.
     *
     * Phase 2 then spends the expensive end-to-end probe — a throwaway Xray instance that
     * fetches a real URL through the config — only on the fastest handful. That is the number
     * that actually predicts browsing quality, and restricting it to good candidates is what
     * keeps the whole run fast on a mid-range phone.
     */
    fun testAll() = runLatencyTest(servers.value)

    /** Full end-to-end probe for one server, from the row's context menu. */
    fun testOne(server: ServerConfig) = runLatencyTest(listOf(server), deepOnly = true)

    fun cancelTest() {
        testJob?.cancel()
        testJob = null
        _testProgress.value = TestProgress()
        snack("تست تأخیر متوقف شد.")
    }

    private fun runLatencyTest(list: List<ServerConfig>, deepOnly: Boolean = false) {
        if (list.isEmpty() || testJob?.isActive == true) return
        val app = getApplication<Application>()
        val options = TunnelOptions.from(prefs)
        testJob = viewModelScope.launch {
            try {
                val quick: Map<String, Int?> = if (deepOnly) {
                    emptyMap()
                } else {
                    _testProgress.value = TestProgress(0, list.size, deepPhase = false)
                    Pinger.tcpPingAll(list) { done, total ->
                        _testProgress.value = TestProgress(done, total, deepPhase = false)
                    }.also(ServerRepository::updatePings)
                }

                val deepCandidates = if (deepOnly) {
                    list
                } else {
                    list.filter { quick[it.id] != null }
                        .sortedBy { quick[it.id] ?: Int.MAX_VALUE }
                        .take(deepProbeBudget)
                }.filter { XrayConfig.isSupportedByXray(it.protocol) }

                if (deepCandidates.isNotEmpty()) {
                    _testProgress.value = TestProgress(0, deepCandidates.size, deepPhase = true)
                    val deep = deepProbe(app, options, deepCandidates)
                    ServerRepository.updatePings(deep)
                }

                val reachable = if (deepOnly) {
                    list.count { ServerRepository.get(it.id)?.pingMs != null }
                } else {
                    quick.count { it.value != null }
                }
                snack(
                    when {
                        deepOnly -> "تست کامل شد."
                        reachable == 0 -> "هیچ سروری پاسخ نداد؛ اتصال شبکه را بررسی کنید."
                        else -> "$reachable سرور از ${list.size} سرور در دسترس است."
                    },
                )
            } finally {
                _testProgress.value = TestProgress()
                testJob = null
            }
        }
    }

    private suspend fun deepProbe(
        app: Application,
        options: TunnelOptions,
        candidates: List<ServerConfig>,
    ): Map<String, Int?> = coroutineScope {
        // Each probe starts an isolated native Xray instance. A high fan-out starves DNS/CPU
        // on mid-range phones and turns healthy servers into false timeouts.
        val gate = Semaphore(3)
        var done = 0
        val lock = Any()
        candidates.map { server ->
            async(Dispatchers.IO) {
                val latency = gate.withPermit {
                    runCatching {
                        XrayCore.measureDelayResilient(
                            app,
                            XrayConfig.build(server, options, forLatencyProbe = true),
                        )
                    }.getOrNull()
                }
                synchronized(lock) {
                    _testProgress.value = TestProgress(++done, candidates.size, deepPhase = true)
                }
                // A config that fails the end-to-end probe is unusable even when its port
                // answers, so the handshake number must not survive as a false promise.
                server.id to latency
            }
        }.awaitAll().toMap()
    }

    // ----------------------------------------------------------------- import

    fun importText(raw: String): ImportResult {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ImportResult(false, "چیزی برای افزودن وارد نشده است.")
        // A lone http(s) URL with a path/query is a subscription link, not an HTTP-proxy config.
        if (isSubscriptionUrl(trimmed)) {
            val host = runCatching { android.net.Uri.parse(trimmed).host }.getOrNull() ?: "اشتراک"
            addSubscription(host, trimmed)
            markImported(trimmed)
            return ImportResult(true, "در حال دریافت اشتراک…")
        }
        val result = ConfigParser.parseMany(raw)
        ServerRepository.addAll(result.servers)
        if (result.servers.isNotEmpty()) markImported(trimmed)
        return when {
            result.servers.isEmpty() && result.errors.isEmpty() ->
                ImportResult(false, "هیچ کانفیگ معتبری پیدا نشد.")
            result.servers.isEmpty() ->
                ImportResult(false, "کانفیگ نامعتبر است. (${result.errors.size} خطا)")
            result.errors.isEmpty() ->
                ImportResult(true, "${result.servers.size} سرور با موفقیت اضافه شد.")
            else -> ImportResult(
                true,
                "${result.servers.size} سرور اضافه شد، ${result.errors.size} مورد نامعتبر بود.",
            )
        }
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

    // ---------------------------------------------------------- subscriptions

    fun addSubscription(name: String, url: String) {
        val cleanName = name.ifBlank { "اشتراک" }
        val sub = Subscription(name = cleanName, url = url.trim())
        SubscriptionRepository.add(sub)
        markImported(url.trim())
        viewModelScope.launch {
            _updatingSubscriptions.value = true
            try { snack(SubscriptionRepository.update(sub.id)) }
            finally { _updatingSubscriptions.value = false }
        }
    }

    fun updateSubscription(id: String) {
        viewModelScope.launch {
            _updatingSubscriptions.value = true
            try { snack(SubscriptionRepository.update(id)) }
            finally { _updatingSubscriptions.value = false }
        }
    }

    fun updateAllSubscriptions() {
        if (_updatingSubscriptions.value) return
        viewModelScope.launch {
            _updatingSubscriptions.value = true
            try { snack(SubscriptionRepository.updateAll()) }
            finally { _updatingSubscriptions.value = false }
        }
    }

    fun removeSubscription(id: String) = SubscriptionRepository.remove(id)
    fun toggleSubscription(id: String, enabled: Boolean) = SubscriptionRepository.setEnabled(id, enabled)
}
