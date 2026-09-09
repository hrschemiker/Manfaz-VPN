package com.manfaz.vpn.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.components.ManfazScreen
import com.manfaz.vpn.ui.theme.BrandAmber
import com.manfaz.vpn.ui.theme.ConnectedGreen
import com.manfaz.vpn.ui.theme.FailedRed
import com.manfaz.vpn.ui.theme.NeutralGray
import com.manfaz.vpn.ui.ltr
import com.manfaz.vpn.ui.toFarsiDigits
import com.manfaz.vpn.ui.formatBytes
import com.manfaz.vpn.ui.landmarkRes
import com.manfaz.vpn.data.model.Countries
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.model.Subscription

@Composable
fun ServersScreen(
    vm: MainViewModel,
    onConnect: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
) {
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<ServerConfig?>(null) }
    val servers by vm.servers.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val progress by vm.testProgress.collectAsState()
    val connection by vm.connection.collectAsState()
    val activeId = connection.server?.id
    var query by remember { mutableStateOf("") }
    var sortByPing by remember { mutableStateOf(true) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }
    var pendingQr by remember { mutableStateOf<ServerConfig?>(null) }

    // Searching only the display name was useless for subscription servers, whose names are
    // often identical; address, group and protocol are what actually distinguish them.
    val filtered = servers
        .filter { server ->
            query.isBlank() || listOf(
                server.name, server.address, server.group, server.protocol.label,
            ).any { it.contains(query, ignoreCase = true) }
        }
        .filter { !favoritesOnly || it.favorite }
        .let { list ->
            if (sortByPing) {
                // Untested and unreachable servers sink to the bottom instead of pretending
                // to be the fastest thing in the list.
                list.sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Int.MAX_VALUE }))
            } else {
                list.sortedBy { it.name }
            }
        }
    val grouped = filtered.groupBy { it.group.ifBlank { "دستی" } }
    val subscriptionByName = subscriptions.associateBy { it.name }

    ManfazScreen(
        title = "سرورها",
        actions = {
            IconButton(onClick = { favoritesOnly = !favoritesOnly }) {
                Icon(
                    if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favoritesOnly) "نمایش همه" else "فقط علاقه‌مندی‌ها",
                    tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else NeutralGray,
                )
            }
            IconButton(onClick = onAddServer) {
                Icon(Icons.Filled.Add, contentDescription = "افزودن سرور")
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("جستجو در نام، آدرس یا اشتراک") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "پاک کردن جستجو")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { sortByPing = !sortByPing }) {
                    Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (sortByPing) "مرتب‌سازی: تأخیر" else "مرتب‌سازی: نام")
                }
                if (progress.running) {
                    TextButton(onClick = vm::cancelTest) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("توقف تست")
                    }
                } else {
                    TextButton(onClick = vm::testAll, enabled = servers.isNotEmpty()) {
                        Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("تست تأخیر")
                    }
                }
            }

            // An indeterminate spinner said nothing about a run that can touch hundreds of
            // servers; a determinate bar with a phase label sets an honest expectation.
            if (progress.running) {
                Text(
                    (if (progress.deepPhase) "تست دقیق سریع‌ترین‌ها" else "بررسی دسترس‌پذیری") +
                        "  ${progress.done} از ${progress.total}".toFarsiDigits(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp),
                )
            }

            Text(
                "${filtered.size} سرور".toFarsiDigits(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PingLegend(ConnectedGreen, "سریع")
                PingLegend(BrandAmber, "متوسط")
                PingLegend(FailedRed, "کند")
                PingLegend(NeutralGray, "تست‌نشده")
            }
            Spacer(Modifier.size(6.dp))

            if (filtered.isEmpty()) {
                EmptyState(hasAnyServer = servers.isNotEmpty(), onAddServer = onAddServer)
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // Without bottom padding the last row hides behind the navigation bar.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                grouped.forEach { (group, groupServers) ->
                    subscriptionByName[group]?.let { sub ->
                        item(key = "subscription-${sub.id}") { SubscriptionUsageStrip(sub) }
                    }
                    items(groupServers, key = { it.id }) { server ->
                        CountryServerCard(
                            server = server,
                            isActive = server.id == activeId,
                            onConnect = { vm.select(server); onConnect() },
                            onLongClick = { menuFor = server },
                            onFavorite = { vm.toggleFavorite(server.id) },
                        )
                    }
                }
            }
        }
    }

    val menuServer = menuFor
    if (menuServer != null) {
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(menuServer.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        ltr("${menuServer.address}:${menuServer.port}"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    MenuItem("تست دقیق این سرور") { vm.testOne(menuServer); menuFor = null }
                    if (menuServer.rawUri.isNotBlank()) {
                        MenuItem("نمایش QR") { pendingQr = menuServer; menuFor = null }
                        MenuItem("کپی لینک") {
                            copyText(context, menuServer.rawUri); menuFor = null
                        }
                        MenuItem("اشتراک‌گذاری") { shareText(context, menuServer.rawUri); menuFor = null }
                    }
                    MenuItem("ویرایش") { onEditServer(menuServer.id); menuFor = null }
                    MenuItem("حذف", FailedRed) { pendingDelete = menuServer; menuFor = null }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("بستن") } },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف سرور") },
            text = { Text("«${toDelete.displayLabel}» حذف شود؟") },
            confirmButton = {
                TextButton(onClick = { vm.removeServer(toDelete.id); pendingDelete = null }) {
                    Text("حذف", color = FailedRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("انصراف") }
            },
        )
    }

    val qrServer = pendingQr
    if (qrServer != null) {
        val qr = remember(qrServer.id) { com.manfaz.vpn.ui.QrGen.encode(qrServer.rawUri) }
        AlertDialog(
            onDismissRequest = { pendingQr = null },
            title = { Text(qrServer.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (qr != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qr,
                        contentDescription = "کد QR کانفیگ",
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                    )
                } else {
                    Text("امکان ساخت QR برای این سرور وجود ندارد.")
                }
            },
            confirmButton = { TextButton(onClick = { pendingQr = null }) { Text("بستن") } },
            dismissButton = {
                TextButton(onClick = { shareText(context, qrServer.rawUri) }) { Text("اشتراک‌گذاری") }
            },
        )
    }
}

@Composable
private fun EmptyState(hasAnyServer: Boolean, onAddServer: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (!hasAnyServer) "هنوز سروری اضافه نکرده‌اید."
            else "سروری با این جستجو پیدا نشد.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        if (!hasAnyServer) {
            Spacer(Modifier.size(6.dp))
            Text(
                "یک لینک کانفیگ یا آدرس اشتراک اضافه کنید تا شروع شود.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = onAddServer) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("افزودن کانفیگ")
            }
        }
    }
}

@Composable
private fun SubscriptionUsageStrip(sub: Subscription) {
    val hasQuota = sub.totalBytes > 0L
    val fraction = if (hasQuota) (sub.usedBytes.toFloat() / sub.totalBytes).coerceIn(0f, 1f) else 0f
    val days = sub.remainingDays
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                sub.name,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DataUsage, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (hasQuota) "باقی‌مانده ${formatBytes(sub.remainingBytes)}" else "حجم نامشخص",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        days?.let { "${it.toString().toFarsiDigits()} روز باقی‌مانده" } ?: "زمان نامشخص",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasQuota) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CountryServerCard(
    server: ServerConfig,
    isActive: Boolean,
    onConnect: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val context = LocalContext.current
    val initial = remember(server.name, server.group, server.address) {
        Countries.detect("${server.name} ${server.group} ${server.address}")
    }
    val country by produceState(initialValue = initial, server.id, server.name, server.address) {
        if (initial.iso.isBlank()) {
            value = com.manfaz.vpn.net.ServerCountryResolver.resolve(context, server)
        }
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val cardColor = if (dark) Color(0xFF151C24) else Color(0xFFFFFCF8)
    val ink = if (dark) Color(0xFFF0F6FC) else Color(0xFF2A1A0E)
    val muted = if (dark) Color(0xFF9FB0C0) else Color(0xFF765F50)
    val warmBorder = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.42f else 0.24f)

    Card(
        modifier = Modifier.fillMaxWidth()
            .height(80.dp)
            .border(
                if (isActive) 2.dp else 1.dp,
                if (isActive) ConnectedGreen else warmBorder,
                RoundedCornerShape(14.dp),
            )
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "اتصال به ${server.displayLabel}",
                onLongClickLabel = "گزینه‌های بیشتر",
                onClick = onConnect,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.Image(
                painter = painterResource(country.landmarkRes()),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = if (dark) androidx.compose.ui.graphics.ColorFilter.tint(
                    MaterialTheme.colorScheme.primary,
                    androidx.compose.ui.graphics.BlendMode.SrcIn,
                ) else null,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(0.62f)
                    .height(70.dp)
                    .absoluteOffset(x = (-72).dp, y = 3.dp)
                    .alpha(0.22f),
            )
            Row(
                Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        server.displayLabel,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(country.faName, color = muted, fontSize = 10.sp)
                    Text(
                        ltr(server.transportLabel + if (isActive) "  •  متصل" else ""),
                        color = if (isActive) ConnectedGreen else muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    PingBadge(server, muted)
                }
                // Delete used to sit here as a bare icon on an 80dp row, one mis-tap away from
                // losing a config. It now lives behind the long-press menu with a confirmation.
                IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (server.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (server.favorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها",
                        tint = if (server.favorite) MaterialTheme.colorScheme.primary else muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PingBadge(server: ServerConfig, muted: Color) {
    val ping = server.pingMs
    Text(
        when {
            ping != null -> "$ping ms".toFarsiDigits()
            server.latencyTested -> "ناموفق"
            else -> "—"
        },
        color = when {
            ping == null && server.latencyTested -> FailedRed
            ping == null -> muted
            ping < 100 -> ConnectedGreen
            ping < 180 -> BrandAmber
            else -> FailedRed
        },
        fontWeight = FontWeight.Black,
        fontSize = if (ping == null && server.latencyTested) 10.sp else 12.sp,
        maxLines = 1,
    )
}

@Composable
private fun PingLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.size(4.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MenuItem(label: String, color: Color? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text(label, color = color ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
    }
}

private fun copyText(context: android.content.Context, text: String) {
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
        ?.setPrimaryClip(android.content.ClipData.newPlainText("config", text))
}

private fun shareText(context: android.content.Context, text: String) {
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
                "اشتراک‌گذاری کانفیگ",
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
