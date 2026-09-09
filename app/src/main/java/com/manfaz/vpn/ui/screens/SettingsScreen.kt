@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.manfaz.vpn.ui.screens

import android.net.InetAddresses
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import com.manfaz.vpn.BuildConfig
import com.manfaz.vpn.data.FragmentMode
import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.data.NetworkAction
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.backup.EncryptedBackup
import com.manfaz.vpn.ui.components.ManfazScreen
import com.manfaz.vpn.ui.theme.NeutralGray
import com.manfaz.vpn.ui.toFarsiDigits
import com.manfaz.vpn.vpn.ConnStatus
import com.manfaz.vpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

private data class Help(val title: String, val body: String)
private data class DnsPreset(val name: String, val resolver: String, val bootstrap: String)

/**
 * Single source of truth for the help dialog. The previous version passed the handler down
 * *and* published it through a CompositionLocal, so a tap on a NavRow's help button fired the
 * callback twice.
 */
private val LocalHelpHandler = staticCompositionLocalOf<(Help) -> Unit> { {} }

private val dnsPresets = listOf(
    DnsPreset("Cloudflare (DoH)", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
    DnsPreset("Google (DoH)", "https://dns.google/dns-query", "8.8.8.8"),
    DnsPreset("Quad9 (DoH)", "https://dns.quad9.net/dns-query", "9.9.9.9"),
    DnsPreset("AdGuard (DoH)", "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
    DnsPreset("شکن", "178.22.122.100", "178.22.122.100"),
    DnsPreset("الکترو", "78.157.42.100", "78.157.42.100"),
    DnsPreset("رادار گیم", "10.202.10.10", "10.202.10.10"),
)

private val fingerprints = listOf(
    "chrome" to "Chrome", "firefox" to "Firefox", "safari" to "Safari",
    "edge" to "Edge", "ios" to "iOS", "randomized" to "تصادفی",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenPerApp: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember { Prefs(context) }
    val connection by VpnController.state.collectAsState()
    val connected = connection.status in setOf(ConnStatus.CONNECTED, ConnStatus.CONNECTING, ConnStatus.SCANNING)
    var help by remember { mutableStateOf<Help?>(null) }
    var reconnect by remember { mutableStateOf(false) }
    fun changed() { if (connected) reconnect = true }

    var kill by remember { mutableStateOf(prefs.killSwitch) }
    var dnsProtect by remember { mutableStateOf(prefs.dnsLeakProtection) }
    var dns by remember { mutableStateOf(prefs.remoteDns) }
    var bootstrap by remember { mutableStateOf(prefs.dnsBootstrap) }
    var ipv6 by remember { mutableStateOf(prefs.ipv6Mode) }
    var mtu by remember { mutableIntStateOf(prefs.mtu) }
    var lan by remember { mutableStateOf(prefs.allowLan) }
    var fragment by remember { mutableStateOf(prefs.fragmentMode) }
    var fingerprint by remember { mutableStateOf(prefs.tlsFingerprint) }
    var mux by remember { mutableStateOf(prefs.muxEnabled) }
    var iranDirect by remember { mutableStateOf(prefs.iranDirect) }
    var blockQuic by remember { mutableStateOf(prefs.blockQuic) }
    var cf by remember { mutableStateOf(prefs.cloudflareScan) }
    var failover by remember { mutableStateOf(prefs.autoFailover) }
    var retries by remember { mutableIntStateOf(prefs.failoverRetries) }
    var autoOpen by remember { mutableStateOf(prefs.autoConnectOnOpen) }
    var boot by remember { mutableStateOf(prefs.connectOnBoot) }
    var wifi by remember { mutableStateOf(prefs.wifiAction) }
    var mobile by remember { mutableStateOf(prefs.mobileAction) }
    var subAuto by remember { mutableStateOf(prefs.subAutoUpdate) }
    var subHours by remember { mutableIntStateOf(prefs.subUpdateHours) }
    var notifyServer by remember { mutableStateOf(prefs.showServerInNotification) }
    var notifySpeed by remember { mutableStateOf(prefs.showSpeedInNotification) }
    var showDns by remember { mutableStateOf(false) }
    var customDns by remember { mutableStateOf("") }
    var dnsError by remember { mutableStateOf<String?>(null) }
    var backupMode by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val appearance by com.manfaz.vpn.ui.theme.ThemeState.appearance.collectAsState()
    val scope = rememberCoroutineScope()

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val pass = password.toCharArray(); password = ""
            scope.launch {
                message = runCatching {
                    val bytes = withContext(Dispatchers.Default) { EncryptedBackup.export(context, pass) }
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) } }
                    "پشتیبان کامل و رمزنگاری‌شده ذخیره شد."
                }.getOrElse { it.message ?: "ساخت پشتیبان ممکن نشد." }
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            restoreBytes = withContext(Dispatchers.IO) {
                runCatching {
                    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                    require(size < 0 || size <= 10 * 1024 * 1024) { "فایل بیش از حد بزرگ است." }
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (restoreBytes == null) message = "خواندن فایل ممکن نشد." else backupMode = "restore"
        }
    }

    ManfazScreen(title = "تنظیمات") { inner ->
        CompositionLocalProvider(LocalHelpHandler provides { help = it }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(inner)
                    .padding(horizontal = 16.dp),
            ) {
                Group("ظاهر") {
                    Choice(
                        "پوسته",
                        listOf("SYSTEM" to "سیستم", "LIGHT" to "روشن", "DARK" to "تیره", "AMOLED" to "AMOLED"),
                        appearance.mode.name,
                    ) {
                        com.manfaz.vpn.ui.theme.ThemeState.setMode(
                            context, com.manfaz.vpn.ui.theme.ThemeMode.valueOf(it),
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        RowDivider()
                        SwitchRow("رنگ پویا", "هماهنگ با رنگ دستگاه", null, appearance.dynamicColor) {
                            com.manfaz.vpn.ui.theme.ThemeState.setDynamicColor(context, it)
                        }
                    }
                }

                Group("عبور از فیلترینگ") {
                    ChoiceRow(
                        "تکه‌تکه‌سازی TLS",
                        Help(
                            "تکه‌تکه‌سازی TLS",
                            "دست‌دادن اولیهٔ TLS را به چند بستهٔ کوچک می‌شکند تا سامانه‌های تشخیص، نام سایت (SNI) را کنار هم نچینند. " +
                                "این مؤثرترین روش فعلی برای عبور از محدودیت‌های ایران است.\n\n" +
                                "«هوشمند» فقط کانفیگ‌های TLS را تکه می‌کند (REALITY خودش نام را پنهان می‌کند)، " +
                                "«همیشه» روی REALITY هم اعمال می‌شود و «تهاجمی» بسته‌های ریزتر به‌همراه نویز می‌فرستد. " +
                                "حالت تهاجمی ممکن است سرعت را کمی کم کند؛ فقط وقتی لازم است که بقیه جواب نداده باشند.",
                        ),
                        listOf(
                            FragmentMode.OFF to "خاموش",
                            FragmentMode.AUTO to "هوشمند",
                            FragmentMode.ALWAYS to "همیشه",
                            FragmentMode.AGGRESSIVE to "تهاجمی",
                        ),
                        fragment,
                    ) { fragment = it; prefs.fragmentMode = it; changed() }
                    RowDivider()
                    ChoiceRow(
                        "اثر انگشت TLS",
                        Help(
                            "اثر انگشت TLS",
                            "ترافیک شما را شبیه یک مرورگر واقعی نشان می‌دهد. اگر اثر انگشت با مرورگرهای رایج فرق داشته باشد، " +
                                "خودش نشانه‌ای برای شناسایی می‌شود. Chrome برای بیشتر کاربران بهترین انتخاب است.",
                        ),
                        fingerprints,
                        fingerprint,
                    ) { fingerprint = it; prefs.tlsFingerprint = it; changed() }
                    RowDivider()
                    SwitchRow(
                        "سایت‌های ایرانی بدون VPN",
                        "بانک‌ها، دیجی‌کالا، آپارات و دامنه‌های ir.",
                        Help(
                            "سایت‌های ایرانی",
                            "سرویس‌های داخلی معمولاً IP خارجی را می‌بندند. با روشن‌بودن این گزینه، این سایت‌ها مستقیم و سریع باز می‌شوند " +
                                "و ترافیک تونل هم سبک‌تر می‌ماند. اگر می‌خواهید همه‌چیز از تونل عبور کند، خاموشش کنید.",
                        ),
                        iranDirect,
                    ) { iranDirect = it; prefs.iranDirect = it; changed() }
                    RowDivider()
                    SwitchRow(
                        "مسدودسازی QUIC",
                        "مرورگر را به TLS معمولی برمی‌گرداند",
                        Help(
                            "QUIC",
                            "QUIC پروتکل جدید مرورگرهاست که روی UDP کار می‌کند و در بسیاری از شبکه‌های ایران کند یا ناپایدار است. " +
                                "با مسدودکردن آن، مرورگر خودش به TLS معمولی برمی‌گردد که تونل بهتر منتقلش می‌کند.",
                        ),
                        blockQuic,
                    ) { blockQuic = it; prefs.blockQuic = it; changed() }
                    RowDivider()
                    SwitchRow(
                        "Mux (چندتایی‌سازی اتصال)",
                        "اتصال‌های کمتر، مناسب شبکه‌های شلوغ",
                        Help(
                            "Mux",
                            "چند اتصال را روی یک اتصال جمع می‌کند و UDP را از داخل TCP عبور می‌دهد. " +
                                "در شبکه‌هایی که تعداد اتصال یا UDP را محدود می‌کنند کمک می‌کند، اما ممکن است سرعت دانلود را کم کند. " +
                                "با کانفیگ‌های دارای flow نوع Vision به‌طور خودکار غیرفعال می‌ماند.",
                        ),
                        mux,
                    ) { mux = it; prefs.muxEnabled = it; changed() }
                }

                Group("محافظت و تونل") {
                    SwitchRow(
                        "کلید قطع اضطراری",
                        "قطع کامل اینترنت هنگام خرابی تونل",
                        Help(
                            "کلید قطع اضطراری",
                            "اگر تونل خراب شود، منفذ به‌جای برگرداندن ترافیک به مسیر معمولی، اینترنت دستگاه را کاملاً می‌بندد " +
                                "تا هیچ نشتی رخ ندهد.\n\n" +
                                "به‌محض فعال‌شدن، دکمهٔ «آزاد کردن اینترنت» هم در صفحهٔ خانه و هم در اعلان گوشی ظاهر می‌شود " +
                                "تا هر وقت خواستید بتوانید دوباره آنلاین شوید.\n\n" +
                                "برای محافظت کامل حتی پس از بسته‌شدن برنامه، گزینهٔ VPN همیشه‌روشن اندروید را هم فعال کنید.",
                        ),
                        kill,
                        // The service snapshots this at connect time, so an already-running
                        // tunnel has to be rebuilt for the change to mean anything.
                    ) { kill = it; prefs.killSwitch = it; changed() }
                    RowDivider()
                    NavRow(
                        "VPN همیشه‌روشن اندروید",
                        "محافظت کامل در سطح سیستم",
                        Help(
                            "VPN همیشه‌روشن",
                            "این تنظیم خود اندروید است و می‌تواند اینترنت بدون VPN را کاملاً مسدود کند. " +
                                "سرعت را کم نمی‌کند، اما اگر سرور قطع شود تا اتصال دوباره اینترنت نخواهید داشت.",
                        ),
                    ) {
                        runCatching { context.startActivity(android.content.Intent("android.settings.VPN_SETTINGS")) }
                    }
                    RowDivider()
                    SwitchRow(
                        "جلوگیری از نشت DNS",
                        "حل نام سایت‌ها داخل تونل",
                        Help(
                            "نشت DNS",
                            "DNS نام سایت را به IP تبدیل می‌کند. با روشن‌بودن این گزینه، درخواست DNS داخل تونل و به‌صورت رمزنگاری‌شده " +
                                "حل می‌شود، بنابراین اپراتور نه نام سایت‌ها را می‌بیند و نه می‌تواند پاسخ‌ها را دستکاری کند. " +
                                "توصیه می‌شود همیشه روشن باشد.",
                        ),
                        dnsProtect,
                    ) { dnsProtect = it; prefs.dnsLeakProtection = it; changed() }
                    RowDivider()
                    NavRow(
                        "سرور DNS",
                        dnsPresets.find { it.resolver == dns }?.name ?: dns,
                        Help(
                            "سرور DNS",
                            "DoH درخواست‌ها را رمزنگاری می‌کند و جلوی دستکاری پاسخ را می‌گیرد؛ برای شرایط ایران بهترین انتخاب است. " +
                                "DNSهای داخلی «رفع تحریم» فقط برای بعضی سرویس‌ها مناسب‌اند و حریم خصوصی کمتری دارند.",
                        ),
                    ) { showDns = true }
                    RowDivider()
                    ChoiceRow(
                        "رفتار IPv6",
                        Help(
                            "IPv6",
                            "«مسدود» امن‌ترین و پایدارترین حالت است: بیشتر اپراتورهای ایرانی IPv6 می‌دهند که از تونل عبور نمی‌کند " +
                                "و باعث می‌شود اتصال قطع به‌نظر برسد. «تونل» IPv6 را از VPN می‌فرستد و «مستقیم» ممکن است IP واقعی را آشکار کند.",
                        ),
                        listOf(
                            Ipv6Mode.BLOCK to "مسدود", Ipv6Mode.TUNNEL to "تونل", Ipv6Mode.DIRECT to "مستقیم",
                        ),
                        ipv6,
                    ) { ipv6 = it; prefs.ipv6Mode = it; changed() }
                    RowDivider()
                    ChoiceRow(
                        "MTU",
                        Help(
                            "MTU",
                            "اندازهٔ بسته‌های تونل است. مقدار خیلی بزرگ می‌تواند بعضی سایت‌ها را ناقص باز کند و مقدار خیلی کوچک کمی سربار می‌سازد. " +
                                "«خودکار» برای Wi‑Fi مقدار ۱۵۰۰ و برای موبایل ۱۴۰۰ انتخاب می‌کند.",
                        ),
                        listOf(
                            0 to "خودکار", 1280 to "۱۲۸۰", 1360 to "۱۳۶۰",
                            1400 to "۱۴۰۰", 1480 to "۱۴۸۰", 1500 to "۱۵۰۰",
                        ),
                        mtu,
                    ) { mtu = it; prefs.mtu = it; changed() }
                    RowDivider()
                    SwitchRow(
                        "دسترسی به شبکهٔ محلی",
                        "چاپگر، مودم و دستگاه‌های داخل خانه",
                        Help(
                            "شبکهٔ محلی",
                            "با روشن‌بودن، آدرس‌های داخلی مثل پنل مودم مستقیم باز می‌شوند. خاموش‌کردن امنیت بیشتری روی Wi‑Fi عمومی می‌دهد، " +
                                "ولی دسترسی به چاپگر یا تلویزیون محلی را قطع می‌کند. روی سرعت اینترنت اثری ندارد.",
                        ),
                        lan,
                    ) { lan = it; prefs.allowLan = it; changed() }
                }

                Group("پایداری اتصال") {
                    SwitchRow(
                        "یافتن IP تمیز Cloudflare",
                        "فقط برای کانفیگ‌های واقعاً CDN",
                        Help(
                            "IP تمیز",
                            "برای کانفیگ‌هایی که پشت Cloudflare هستند چند IP را کوتاه آزمایش می‌کند و کم‌تأخیرترین را برمی‌گزیند. " +
                                "شروع اتصال ممکن است چند ثانیه طولانی‌تر شود، اما کیفیت اتصال CDN بهتر می‌شود.",
                        ),
                        cf,
                    ) { cf = it; prefs.cloudflareScan = it }
                    RowDivider()
                    SwitchRow(
                        "جایگزینی خودکار سرور",
                        "پس از شکست اتصال، سرور بعدی را امتحان کند",
                        Help(
                            "جایگزینی خودکار",
                            "اگر سرور انتخابی وصل نشود، منفذ چند سرور سالم‌تر را به‌ترتیب امتحان می‌کند و زمان پیدا کردن اتصال سالم را کم می‌کند.",
                        ),
                        failover,
                    ) { failover = it; prefs.autoFailover = it }
                    if (failover) {
                        RowDivider()
                        ChoiceRow(
                            "تعداد تلاش",
                            Help(
                                "تعداد تلاش",
                                "تعداد سرورهای جایگزینی است که پس از شکست امتحان می‌شوند. عدد بیشتر شانس اتصال را بالا می‌برد، " +
                                    "اما در شبکهٔ کاملاً قطع، انتظار را طولانی‌تر می‌کند.",
                            ),
                            (1..5).map { it to it.toString().toFarsiDigits() },
                            retries,
                        ) { retries = it; prefs.failoverRetries = it }
                    }
                }

                Group("اتصال خودکار") {
                    SwitchRow(
                        "هنگام بازشدن برنامه",
                        "اتصال به آخرین سرور",
                        Help(
                            "اتصال هنگام اجرا",
                            "با بازکردن منفذ، آخرین سرور انتخابی وصل می‌شود. روی کیفیت اتصال اثر ندارد و فقط یک مرحله را خودکار می‌کند.",
                        ),
                        autoOpen,
                    ) { autoOpen = it; prefs.autoConnectOnOpen = it }
                    RowDivider()
                    SwitchRow(
                        "پس از روشن‌شدن تلفن",
                        "نیازمند مجوز قبلی VPN",
                        Help(
                            "اتصال پس از روشن‌شدن",
                            "بعد از راه‌اندازی گوشی، منفذ تلاش می‌کند آخرین اتصال را برگرداند.",
                        ),
                        boot,
                    ) { boot = it; prefs.connectOnBoot = it }
                    RowDivider()
                    NetworkChoice("رفتار روی Wi‑Fi", wifi) { wifi = it; prefs.wifiAction = it }
                    RowDivider()
                    NetworkChoice("رفتار روی دیتای موبایل", mobile) { mobile = it; prefs.mobileAction = it }
                }

                Group("اعلان اتصال") {
                    SwitchRow(
                        "نمایش نام سرور",
                        "نام کانفیگ در اعلان",
                        Help(
                            "نام سرور در اعلان",
                            "خاموش‌کردن فقط حریم خصوصی صفحهٔ قفل را بیشتر می‌کند و هیچ اثری روی اتصال ندارد.",
                        ),
                        notifyServer,
                    ) { notifyServer = it; prefs.showServerInNotification = it; changed() }
                    RowDivider()
                    SwitchRow(
                        "نمایش سرعت زنده",
                        "دانلود و آپلود لحظه‌ای",
                        Help(
                            "سرعت زنده",
                            "هر ثانیه آمار هسته را در اعلان به‌روز می‌کند. اثر آن روی باتری بسیار کم است؛ برای اعلان ثابت می‌توانید خاموشش کنید.",
                        ),
                        notifySpeed,
                    ) { notifySpeed = it; prefs.showSpeedInNotification = it; changed() }
                }

                Group("اشتراک‌ها") {
                    SwitchRow(
                        "به‌روزرسانی خودکار",
                        "تازه‌سازی دوره‌ای در پس‌زمینه",
                        Help(
                            "به‌روزرسانی اشتراک",
                            "لینک‌های اشتراک را در فاصلهٔ انتخابی بررسی می‌کند تا سرورها تازه بمانند. مصرف داده کم است.",
                        ),
                        subAuto,
                    ) {
                        subAuto = it; prefs.subAutoUpdate = it
                        com.manfaz.vpn.work.SubscriptionWorkScheduler.sync(context)
                    }
                    RowDivider()
                    ChoiceRow(
                        "بازهٔ بررسی",
                        Help(
                            "بازهٔ به‌روزرسانی",
                            "بازهٔ کوتاه‌تر سرورها را تازه‌تر نگه می‌دارد ولی دفعات استفاده از شبکه و باتری را کمی بیشتر می‌کند.",
                        ),
                        listOf(6, 12, 24, 48).map { it to "$it ساعت".toFarsiDigits() },
                        subHours,
                    ) {
                        subHours = it; prefs.subUpdateHours = it
                        com.manfaz.vpn.work.SubscriptionWorkScheduler.sync(context)
                    }
                }

                Group("تونل هر برنامه") {
                    NavRow(
                        "انتخاب برنامه‌ها",
                        "عبور یا عدم عبور برنامه‌های مشخص",
                        Help(
                            "تونل هر برنامه",
                            "می‌توانید بعضی برنامه‌ها را مستقیم نگه دارید یا فقط برنامه‌های انتخابی را از VPN عبور دهید. " +
                                "سبک‌تر شدن ترافیک VPN می‌تواند کیفیت را بهتر کند؛ انتخاب اشتباه ممکن است IP واقعی یک برنامه را آشکار کند.",
                        ),
                        onClick = onOpenPerApp,
                    )
                }

                Group("پشتیبان امن") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "سرورها، اشتراک‌ها، علاقه‌مندی‌ها و تمام تنظیمات در فایل رمزنگاری‌شده ذخیره می‌شوند.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button({ backupMode = "export"; password = "" }, Modifier.weight(1f)) {
                                Text("ساخت پشتیبان")
                            }
                            OutlinedButton({ openBackup.launch(arrayOf("*/*")) }, Modifier.weight(1f)) {
                                Text("بازیابی")
                            }
                        }
                        message?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 12.sp) }
                    }
                }

                Group("درباره") {
                    NavRow(
                        "گزارش و عیب‌یابی",
                        "گزارش قابل کپی برای رفع مشکل",
                        Help(
                            "گزارش عیب‌یابی",
                            "رویدادهای فنی اتصال را نشان می‌دهد. ارسال آن برای پشتیبانی می‌تواند علت قطعی را روشن کند؛ " +
                                "بهتر است قبل از ارسال، اطلاعات حساس احتمالی را بررسی کنید.",
                        ),
                        onClick = onOpenDiagnostics,
                    )
                    RowDivider()
                    InfoRow("نسخه برنامه", BuildConfig.VERSION_NAME.toFarsiDigits())
                    RowDivider()
                    InfoRow("هسته", "Xray + hev-socks5-tunnel")
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Made with ♥ by ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(
                            "Hamidreza",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable(role = Role.Button) {
                                uriHandler.openUri("https://github.com/hrschemiker")
                            },
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    help?.let { h ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(h.title) },
            text = { Text(h.body, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ help = null }) { Text("متوجه شدم") } },
        )
    }
    if (reconnect) AlertDialog(
        onDismissRequest = { reconnect = false },
        title = { Text("اعمال تنظیم جدید") },
        text = { Text("این تغییر فنی پس از اتصال مجدد اعمال می‌شود. همین حالا اتصال با همان سرور دوباره برقرار شود؟") },
        confirmButton = {
            TextButton({
                reconnect = false
                connection.server?.let { VpnController.connect(context, it) }
            }) { Text("اتصال مجدد") }
        },
        dismissButton = { TextButton({ reconnect = false }) { Text("بعداً") } },
    )
    if (showDns) DnsDialog(
        selected = dns, custom = customDns, error = dnsError,
        onCustom = { customDns = it; dnsError = null },
        onDismiss = { showDns = false; dnsError = null },
        onSelect = { p ->
            dns = p.resolver; bootstrap = p.bootstrap
            prefs.remoteDns = dns; prefs.dnsBootstrap = bootstrap
            showDns = false; changed()
        },
        onSaveCustom = {
            val value = customDns.trim()
            scope.launch {
                val result = withContext(Dispatchers.IO) { validateCustomDns(value) }
                if (result == null) dnsError = "فقط IP معتبر یا آدرس DoH با https پذیرفته می‌شود."
                else {
                    dns = value; bootstrap = result
                    prefs.remoteDns = value; prefs.dnsBootstrap = result
                    showDns = false; changed()
                }
            }
        },
    )
    backupMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { backupMode = null; password = ""; restoreBytes = null },
            title = { Text(if (mode == "export") "گذرواژه پشتیبان" else "گذرواژه بازیابی") },
            text = {
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("حداقل ۸ نویسه") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = password.length >= 8, onClick = {
                    if (mode == "export") { backupMode = null; saveBackup.launch("manfaz-backup.mnfz") }
                    else {
                        val pass = password.toCharArray(); val bytes = restoreBytes
                        backupMode = null; password = ""; restoreBytes = null
                        scope.launch {
                            message = runCatching {
                                val n = withContext(Dispatchers.Default) {
                                    EncryptedBackup.restore(context, bytes ?: error("فایل موجود نیست"), pass)
                                }
                                "${n.toString().toFarsiDigits()} سرور و تنظیمات بازیابی شد. برای هماهنگی کامل، برنامه را یک‌بار باز و بسته کنید."
                            }.getOrElse { "بازیابی ناموفق بود؛ فایل یا گذرواژه را بررسی کنید." }
                        }
                    }
                }) { Text(if (mode == "export") "انتخاب محل" else "بازیابی") }
            },
            dismissButton = {
                TextButton({ backupMode = null; password = ""; restoreBytes = null }) { Text("انصراف") }
            },
        )
    }
}

private suspend fun validateCustomDns(value: String): String? {
    if (value.isBlank()) return null
    if (Build.VERSION.SDK_INT >= 29 && InetAddresses.isNumericAddress(value)) return value
    if (value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) &&
        value.split('.').all { it.toIntOrNull() in 0..255 }
    ) return value
    val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) return null
    return runCatching { InetAddress.getByName(uri.host).hostAddress }.getOrNull()
}

@Composable private fun DnsDialog(
    selected: String, custom: String, error: String?, onCustom: (String) -> Unit,
    onDismiss: () -> Unit, onSelect: (DnsPreset) -> Unit, onSaveCustom: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("انتخاب DNS") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            dnsPresets.forEach { p ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.RadioButton) { onSelect(p) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(p.resolver == selected, onClick = { onSelect(p) })
                    Column {
                        Text(p.name, fontWeight = FontWeight.Medium)
                        Text(p.resolver, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedTextField(
                custom, onCustom, Modifier.fillMaxWidth(),
                label = { Text("IP یا آدرس DoH سفارشی") },
                supportingText = { Text(error ?: "نمونه: https://dns.example/dns-query") },
                isError = error != null, singleLine = true,
            )
        }
    }, confirmButton = {
        TextButton(onSaveCustom, enabled = custom.isNotBlank()) { Text("ذخیره سفارشی") }
    }, dismissButton = { TextButton(onDismiss) { Text("انصراف") } })
}

@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val icon = when (title) {
        "ظاهر" -> Icons.Filled.Palette
        "عبور از فیلترینگ" -> Icons.Filled.Shield
        "محافظت و تونل" -> Icons.Filled.Security
        "پایداری اتصال" -> Icons.Filled.Link
        "اتصال خودکار" -> Icons.Filled.AutoAwesome
        "اعلان اتصال" -> Icons.Filled.Notifications
        "اشتراک‌ها" -> Icons.Filled.Sync
        "تونل هر برنامه" -> Icons.Filled.Apps
        "پشتیبان امن" -> Icons.Filled.Backup
        else -> Icons.Filled.Info
    }
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(7.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
    Card(
        Modifier.fillMaxWidth(),
        RoundedCornerShape(16.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
        CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(4.dp), content = content)
    }
}

/** Help affordance. It reports through the single [LocalHelpHandler], never twice. */
@Composable private fun HelpButton(help: Help) {
    val onHelp = LocalHelpHandler.current
    Surface(
        Modifier.size(24.dp).clickable(role = Role.Button) { onHelp(help) },
        CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.QuestionMark,
                "راهنمای ${help.title}",
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable private fun SwitchRow(
    title: String,
    subtitle: String,
    help: Help?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (help != null) { HelpButton(help); Spacer(Modifier.width(9.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable private fun NavRow(title: String, subtitle: String, help: Help, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HelpButton(help); Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(22.dp), tint = NeutralGray)
    }
}

@Composable private fun <T> ChoiceRow(
    title: String,
    help: Help,
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HelpButton(help)
            Spacer(Modifier.width(9.dp))
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        FlowRow(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            choices.forEach { (v, l) -> FilterChip(v == selected, { onSelect(v) }, { Text(l) }) }
        }
    }
}

@Composable private fun Choice(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) = Column(Modifier.padding(14.dp)) {
    Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    FlowRow(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        choices.forEach { (v, l) -> FilterChip(v == selected, { onSelect(v) }, { Text(l) }) }
    }
}

@Composable private fun NetworkChoice(
    title: String,
    selected: NetworkAction,
    onSelect: (NetworkAction) -> Unit,
) = ChoiceRow(
    title,
    Help(
        title,
        "رفتار منفذ هنگام فعال‌شدن شبکهٔ اصلی دستگاه را تعیین می‌کند. «سریع‌ترین» ابتدا کم‌پینگ‌ترین سرور موجود را انتخاب می‌کند. " +
            "این قانون وقتی برنامه در حال اجراست فوراً اعمال می‌شود.",
    ),
    listOf(
        NetworkAction.NONE to "هیچ‌کار", NetworkAction.CONNECT to "اتصال",
        NetworkAction.DISCONNECT to "قطع", NetworkAction.FASTEST to "سریع‌ترین",
    ),
    selected,
    onSelect,
)

@Composable private fun InfoRow(title: String, value: String) = Row(
    Modifier.fillMaxWidth().padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun RowDivider() =
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = NeutralGray.copy(alpha = .15f))
