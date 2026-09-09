package com.manfaz.vpn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Ipv6Mode { BLOCK, TUNNEL, DIRECT }
enum class NetworkAction { NONE, CONNECT, DISCONNECT, FASTEST }

/**
 * TLS ClientHello fragmentation, the most effective countermeasure against SNI-based
 * DPI blocking on Iranian networks.
 *
 * - [OFF]        never fragment.
 * - [AUTO]       fragment plain-TLS handshakes only; REALITY already hides its SNI.
 * - [ALWAYS]     fragment every TLS-like handshake, REALITY included.
 * - [AGGRESSIVE] tiny packets plus padding noise, for the hardest filtering conditions.
 */
enum class FragmentMode { OFF, AUTO, ALWAYS, AGGRESSIVE }

/**
 * Simple SharedPreferences-backed settings, read in the UI process and passed to the
 * ":core" VPN service via a single intent extra (multi-process SharedPreferences is
 * unreliable, so the values are snapshotted at connect time).
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("manfaz_prefs", Context.MODE_PRIVATE)

    var killSwitch: Boolean
        get() = sp.getBoolean(KILL_SWITCH, false)
        set(v) = sp.edit().putBoolean(KILL_SWITCH, v).apply()

    /**
     * DNS leak protection defaults to ON: on Iranian networks a plain resolver is both a
     * privacy leak and the single most common cause of DNS poisoning.
     */
    var dnsLeakProtection: Boolean
        get() = sp.getBoolean(DNS_PROTECT, true)
        set(v) = sp.edit().putBoolean(DNS_PROTECT, v).apply()

    /**
     * IPv6 defaults to BLOCK: most Iranian mobile carriers hand out an IPv6 address whose
     * traffic never reaches the proxy, so leaving it enabled looks like a dead connection.
     */
    var ipv6Mode: Ipv6Mode
        get() = runCatching {
            Ipv6Mode.valueOf(sp.getString(IPV6_MODE, Ipv6Mode.BLOCK.name) ?: Ipv6Mode.BLOCK.name)
        }.getOrDefault(Ipv6Mode.BLOCK)
        set(v) = sp.edit().putString(IPV6_MODE, v.name).apply()

    var remoteDns: String
        get() = sp.getString(REMOTE_DNS, DEFAULT_REMOTE_DNS) ?: DEFAULT_REMOTE_DNS
        set(v) = sp.edit().putString(REMOTE_DNS, v).apply()

    var dnsBootstrap: String
        get() = sp.getString(DNS_BOOTSTRAP, DEFAULT_DNS_BOOTSTRAP) ?: DEFAULT_DNS_BOOTSTRAP
        set(v) = sp.edit().putString(DNS_BOOTSTRAP, v).apply()

    var mtu: Int
        get() = sp.getInt(MTU, 0).takeIf { it == 0 || it in 1280..1500 } ?: 0
        set(v) = sp.edit().putInt(MTU, v).apply()

    var allowLan: Boolean
        get() = sp.getBoolean(ALLOW_LAN, true)
        set(v) = sp.edit().putBoolean(ALLOW_LAN, v).apply()

    // ---- Censorship-circumvention tuning ----

    var fragmentMode: FragmentMode
        get() = runCatching {
            FragmentMode.valueOf(sp.getString(FRAGMENT_MODE, FragmentMode.AUTO.name) ?: FragmentMode.AUTO.name)
        }.getOrDefault(FragmentMode.AUTO)
        set(v) = sp.edit().putString(FRAGMENT_MODE, v.name).apply()

    /** uTLS ClientHello fingerprint used when a config does not pin one itself. */
    var tlsFingerprint: String
        get() = sp.getString(TLS_FINGERPRINT, "chrome") ?: "chrome"
        set(v) = sp.edit().putString(TLS_FINGERPRINT, v).apply()

    /** Mux.Cool: fewer TCP handshakes and UDP-over-TCP, at the cost of some throughput. */
    var muxEnabled: Boolean
        get() = sp.getBoolean(MUX_ENABLED, false)
        set(v) = sp.edit().putBoolean(MUX_ENABLED, v).apply()

    /** Keep Iranian sites (`.ir` and known domestic services) outside the tunnel. */
    var iranDirect: Boolean
        get() = sp.getBoolean(IRAN_DIRECT, true)
        set(v) = sp.edit().putBoolean(IRAN_DIRECT, v).apply()

    /** Block QUIC so browsers fall back to TCP+TLS, which the tunnel carries far better. */
    var blockQuic: Boolean
        get() = sp.getBoolean(BLOCK_QUIC, true)
        set(v) = sp.edit().putBoolean(BLOCK_QUIC, v).apply()

    var autoFailover: Boolean
        get() = sp.getBoolean(AUTO_FAILOVER, false)
        set(v) = sp.edit().putBoolean(AUTO_FAILOVER, v).apply()

    var failoverRetries: Int
        get() = sp.getInt(FAILOVER_RETRIES, 2).coerceIn(1, 5)
        set(v) = sp.edit().putInt(FAILOVER_RETRIES, v.coerceIn(1, 5)).apply()

    var showServerInNotification: Boolean
        get() = sp.getBoolean(NOTIFY_SERVER, true)
        set(v) = sp.edit().putBoolean(NOTIFY_SERVER, v).apply()

    var showSpeedInNotification: Boolean
        get() = sp.getBoolean(NOTIFY_SPEED, true)
        set(v) = sp.edit().putBoolean(NOTIFY_SPEED, v).apply()

    var perAppEnabled: Boolean
        get() = sp.getBoolean(PER_APP_ENABLED, false)
        set(v) = sp.edit().putBoolean(PER_APP_ENABLED, v).apply()

    /** true = bypass mode (selected apps go direct); false = proxy-only mode (only selected apps proxied). */
    var perAppBypassMode: Boolean
        get() = sp.getBoolean(PER_APP_BYPASS, true)
        set(v) = sp.edit().putBoolean(PER_APP_BYPASS, v).apply()

    var perAppSet: Set<String>
        get() = sp.getStringSet(PER_APP_SET, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(PER_APP_SET, v).apply()

    fun togglePerApp(pkg: String) {
        val cur = perAppSet.toMutableSet()
        if (!cur.add(pkg)) cur.remove(pkg)
        perAppSet = cur
    }

    // ---- Connection UX ----
    var lastServerId: String
        get() = sp.getString(LAST_SERVER, "") ?: ""
        set(v) = sp.edit().putString(LAST_SERVER, v).apply()

    var autoConnectOnOpen: Boolean
        get() = sp.getBoolean(AUTO_ON_OPEN, false)
        set(v) = sp.edit().putBoolean(AUTO_ON_OPEN, v).apply()

    var connectOnBoot: Boolean
        get() = sp.getBoolean(CONNECT_ON_BOOT, false)
        set(v) = sp.edit().putBoolean(CONNECT_ON_BOOT, v).apply()

    /** Foreground network rules; they never request extra location/SSID permissions. */
    var connectOnWifi: Boolean
        get() = sp.getBoolean(CONNECT_ON_WIFI, false)
        set(v) = sp.edit().putBoolean(CONNECT_ON_WIFI, v).apply()

    var disconnectOnMobile: Boolean
        get() = sp.getBoolean(DISCONNECT_ON_MOBILE, false)
        set(v) = sp.edit().putBoolean(DISCONNECT_ON_MOBILE, v).apply()

    var wifiAction: NetworkAction
        get() = readNetworkAction(WIFI_ACTION, if (connectOnWifi) NetworkAction.CONNECT else NetworkAction.NONE)
        set(v) = sp.edit().putString(WIFI_ACTION, v.name).apply()

    var mobileAction: NetworkAction
        get() = readNetworkAction(MOBILE_ACTION, if (disconnectOnMobile) NetworkAction.DISCONNECT else NetworkAction.NONE)
        set(v) = sp.edit().putString(MOBILE_ACTION, v.name).apply()

    private fun readNetworkAction(key: String, fallback: NetworkAction): NetworkAction =
        runCatching { NetworkAction.valueOf(sp.getString(key, fallback.name) ?: fallback.name) }
            .getOrDefault(fallback)

    var subAutoUpdate: Boolean
        get() = sp.getBoolean(SUB_AUTO, false)
        set(v) = sp.edit().putBoolean(SUB_AUTO, v).apply()

    var subUpdateHours: Int
        get() = sp.getInt(SUB_HOURS, 12)
        set(v) = sp.edit().putInt(SUB_HOURS, v).apply()

    // Appearance
    var themeMode: String   // SYSTEM | LIGHT | DARK | AMOLED
        get() = sp.getString(THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(v) = sp.edit().putString(THEME_MODE, v).apply()

    var dynamicColor: Boolean
        get() = sp.getBoolean(DYNAMIC_COLOR, false)
        set(v) = sp.edit().putBoolean(DYNAMIC_COLOR, v).apply()

    /** Auto-scan for a clean Cloudflare IP before connecting CDN configs. */
    var cloudflareScan: Boolean
        get() = sp.getBoolean(CF_SCAN, false)
        set(v) = sp.edit().putBoolean(CF_SCAN, v).apply()

    /** All preferences are included only inside the password-encrypted backup. */
    fun exportJson(): JSONObject = JSONObject().apply {
        sp.all.forEach { (key, value) ->
            when (value) {
                is Set<*> -> put(key, JSONArray(value.filterIsInstance<String>()))
                is Boolean, is Int, is Long, is Float, is String -> put(key, value)
            }
        }
    }

    fun restoreJson(json: JSONObject) {
        val e = sp.edit().clear()
        json.keys().forEach { key ->
            when (val value = json.get(key)) {
                is Boolean -> e.putBoolean(key, value)
                is Int -> e.putInt(key, value)
                is Long -> e.putLong(key, value)
                is Double -> e.putFloat(key, value.toFloat())
                is String -> e.putString(key, value)
                is JSONArray -> e.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
            }
        }
        e.commit()
    }

    /** Drops settings that belonged to features removed from the app. */
    fun pruneObsoleteKeys() {
        val obsolete = sp.all.keys.filter { key ->
            key.startsWith("free_") || key in OBSOLETE_KEYS
        }
        if (obsolete.isEmpty()) return
        val e = sp.edit()
        obsolete.forEach(e::remove)
        e.apply()
    }

    companion object {
        const val DEFAULT_REMOTE_DNS = "https://cloudflare-dns.com/dns-query"
        const val DEFAULT_DNS_BOOTSTRAP = "1.1.1.1"

        private const val LAST_SERVER = "last_server_id"
        private const val AUTO_ON_OPEN = "auto_on_open"
        private const val CONNECT_ON_BOOT = "connect_on_boot"
        private const val CONNECT_ON_WIFI = "connect_on_wifi"
        private const val DISCONNECT_ON_MOBILE = "disconnect_on_mobile"
        private const val SUB_AUTO = "sub_auto_update"
        private const val SUB_HOURS = "sub_update_hours"
        private const val THEME_MODE = "theme_mode"
        private const val DYNAMIC_COLOR = "dynamic_color"
        private const val CF_SCAN = "cloudflare_scan"
        private const val KILL_SWITCH = "kill_switch"
        private const val DNS_PROTECT = "dns_protect"
        private const val IPV6_MODE = "ipv6_mode"
        private const val REMOTE_DNS = "remote_dns"
        private const val DNS_BOOTSTRAP = "dns_bootstrap"
        private const val MTU = "tunnel_mtu"
        private const val ALLOW_LAN = "allow_lan"
        private const val FRAGMENT_MODE = "fragment_mode"
        private const val TLS_FINGERPRINT = "tls_fingerprint"
        private const val MUX_ENABLED = "mux_enabled"
        private const val IRAN_DIRECT = "iran_direct"
        private const val BLOCK_QUIC = "block_quic"
        private const val AUTO_FAILOVER = "auto_failover"
        private const val FAILOVER_RETRIES = "failover_retries"
        private const val NOTIFY_SERVER = "notify_server"
        private const val NOTIFY_SPEED = "notify_speed"
        private const val WIFI_ACTION = "wifi_action"
        private const val MOBILE_ACTION = "mobile_action"
        private const val PER_APP_ENABLED = "per_app_enabled"
        private const val PER_APP_BYPASS = "per_app_bypass"
        private const val PER_APP_SET = "per_app_set"

        private val OBSOLETE_KEYS = setOf("last_free_fetch", "block_ipv6")
    }
}
