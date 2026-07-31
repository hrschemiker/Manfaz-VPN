package com.manfaz.vpn.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.theme.BrandAmber
import com.manfaz.vpn.ui.theme.BrandOrange
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(
    vm: MainViewModel,
    onConnect: () -> Unit,
    onOpenFree: () -> Unit,
    onEditServer: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuFor by remember { mutableStateOf<com.manfaz.vpn.data.model.ServerConfig?>(null) }
    val servers by vm.servers.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val testing by vm.testing.collectAsState()
    val connection by vm.connection.collectAsState()
    val activeId = connection.server?.id
    var query by remember { mutableStateOf("") }
    var sortByPing by remember { mutableStateOf(true) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.manfaz.vpn.data.model.ServerConfig?>(null) }
    var pendingQr by remember { mutableStateOf<com.manfaz.vpn.data.model.ServerConfig?>(null) }
    val prefs = remember { com.manfaz.vpn.data.Prefs(context) }
    var freeUnlocked by remember { mutableStateOf(prefs.freeConfigsUnlocked) }
    var secretTapCount by remember { mutableStateOf(0) }
    var lastSecretTap by remember { mutableStateOf(0L) }

    val filtered = servers
        .filter { it.name.contains(query, ignoreCase = true) }
        .filter { !favoritesOnly || it.favorite }
        .let { list -> if (sortByPing) list.sortedBy { it.pingMs ?: Int.MAX_VALUE } else list.sortedBy { it.name } }
    val grouped = filtered.groupBy { it.group.ifBlank { "دستی" } }
    val subscriptionByName = subscriptions.associateBy { it.name }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "سرورها",
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {
                if (!freeUnlocked) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    secretTapCount = if (now - lastSecretTap <= 1_200L) secretTapCount + 1 else 1
                    lastSecretTap = now
                    if (secretTapCount >= 7) {
                        prefs.freeConfigsUnlocked = true
                        freeUnlocked = true
                        secretTapCount = 0
                        android.widget.Toast.makeText(
                            context, "بخش مخفی کانفیگ‌های رایگان فعال شد.", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
        )
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("جستجوی سرور") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { sortByPing = !sortByPing }) {
                Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (sortByPing) "مرتب‌سازی: پینگ" else "مرتب‌سازی: نام")
            }
            TextButton(onClick = { vm.testAll() }, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(6.dp))
                Text(if (testing) "در حال تست…" else "تست همه")
            }
            TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                Icon(
                    if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "فقط علاقه‌مندی‌ها", modifier = Modifier.size(18.dp),
                    tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else NeutralGray,
                )
            }
        }
        Text("${filtered.size} سرور".toFarsiDigits(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 2.dp))

        if (freeUnlocked) {
            androidx.compose.material3.Button(
                onClick = onOpenFree,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("کانفیگ‌های رایگان")
            }
        }
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PingLegend(ConnectedGreen, "سریع")
            PingLegend(BrandAmber, "متوسط")
            PingLegend(FailedRed, "کند")
            PingLegend(NeutralGray, "تست‌نشده")
        }
        Spacer(Modifier.size(6.dp))

        if (filtered.isEmpty()) {
            Spacer(Modifier.size(32.dp))
            Text(
                if (servers.isEmpty()) "هنوز سروری اضافه نکرده‌اید. از تب «افزودن» یک کانفیگ یا اشتراک اضافه کنید."
                else "سروری با این جستجو پیدا نشد.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            grouped.forEach { (group, groupServers) ->
                subscriptionByName[group]?.let { sub ->
                    item(key = "subscription-${sub.id}") { SubscriptionUsageStrip(sub) }
                }
                items(groupServers, key = { it.id }) { server ->
                    val isActive = server.id == activeId
                    CountryServerCard(
                        server = server,
                        isActive = isActive,
                        onConnect = { vm.select(server); onConnect() },
                        onLongClick = { menuFor = server },
                        onFavorite = { vm.toggleFavorite(server.id) },
                        onDelete = { pendingDelete = server },
                    )
                }
            }
        }
    }

    val menuServer = menuFor
    if (menuServer != null) {
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(menuServer.name, maxLines = 1) },
            text = {
                Column {
                    MenuItem("تست این سرور") { vm.testOne(menuServer); menuFor = null }
                    if (menuServer.rawUri.isNotBlank()) {
                        MenuItem("نمایش QR") { pendingQr = menuServer; menuFor = null }
                        MenuItem("کپی لینک") { copyText(context, menuServer.rawUri); menuFor = null }
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
            text = { Text("«${toDelete.name}» حذف شود؟") },
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
            title = { Text(qrServer.name, maxLines = 1) },
            text = {
                if (qr != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qr,
                        contentDescription = "QR",
                        modifier = Modifier.fillMaxWidth().size(260.dp),
                    )
                } else {
                    Text("امکان ساخت QR برای این سرور وجود ندارد.")
                }
            },
            confirmButton = { TextButton(onClick = { pendingQr = null }) { Text("بستن") } },
            dismissButton = {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, qrServer.rawUri),
                            "اشتراک‌گذاری کانفیگ"))
                    }
                }) { Text("اشتراک‌گذاری") }
            },
        )
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
            .height(116.dp)
            .border(
                if (isActive) 2.dp else 1.dp,
                if (isActive) ConnectedGreen else warmBorder,
                RoundedCornerShape(18.dp),
            )
            .combinedClickable(onClick = onConnect, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
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
                    .height(96.dp)
                    .absoluteOffset(x = (-76).dp, y = 4.dp)
                    .alpha(if (dark) 0.22f else 0.22f),
            )
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        server.name,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(country.faName, color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        ltr(server.protocol.label + if (isActive) "  •  متصل" else ""),
                        color = if (isActive) ConnectedGreen else muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val ping = server.pingMs
                    Box(
                        Modifier.width(58.dp).height(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (ping != null) "$ping ms".toFarsiDigits() else "—",
                            color = when {
                                ping == null -> muted
                                ping < 100 -> ConnectedGreen
                                ping < 180 -> BrandAmber
                                else -> FailedRed
                            },
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (server.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "علاقه‌مندی",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "حذف",
                            tint = FailedRed,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PingLegend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.size(4.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MenuItem(label: String, color: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = color ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
    }
}

private fun copyText(context: android.content.Context, text: String) {
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
        ?.setPrimaryClip(android.content.ClipData.newPlainText("config", text))
}

private fun shareText(context: android.content.Context, text: String) {
    runCatching {
        context.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
            "اشتراک‌گذاری کانفیگ").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
