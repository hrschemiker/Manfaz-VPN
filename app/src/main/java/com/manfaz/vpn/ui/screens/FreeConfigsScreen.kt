package com.manfaz.vpn.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.theme.BrandAmber
import com.manfaz.vpn.ui.theme.BrandOrange
import com.manfaz.vpn.ui.theme.ConnectedGreen
import com.manfaz.vpn.ui.theme.FailedRed
import com.manfaz.vpn.ui.theme.NeutralGray
import com.manfaz.vpn.ui.toFarsiDigits

@Composable
fun FreeConfigsScreen(vm: MainViewModel, onConnect: () -> Unit, onBack: () -> Unit) {
    val list by vm.freeConfigs.collectAsState()
    val testing by vm.freeTesting.collectAsState()
    val fetching by vm.fetchingFree.collectAsState()
    val connection by vm.connection.collectAsState()
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }
    var askCleanup by remember { mutableStateOf(vm.staleFreeCount() > 0) }
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    val activeId = connection.server?.id
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { com.manfaz.vpn.data.Prefs(ctx) }
    var showWarning by remember { mutableStateOf(!prefs.freeWarningDismissed) }

    // No-ping ones always fade to the bottom; ping-ranked at the top.
    val sorted = list
        .filter { query.isBlank() || it.freeAlias.contains(query) || it.address.contains(query, true) }
        .filter { !favoritesOnly || it.favorite }
        .sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Int.MAX_VALUE }))

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
            }
            Text("کانفیگ‌های رایگان", fontWeight = FontWeight.Black, fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
        Text("دریافت فقط هنگام اتصال؛ تست واقعی فقط پس از قطع VPN انجام می‌شود.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))

        if (showWarning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = FailedRed.copy(alpha = 0.12f)),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "⚠️ کانفیگ‌های رایگان امنیت کافی ندارند و تنها برای عبور از شرایط سخت اینترنت ایران فراهم شده‌اند. " +
                            "خطر نشت اطلاعات (Data Leak) وجود دارد و استفاده از آن‌ها اصلاً توصیه نمی‌شود.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showWarning = false; prefs.freeWarningDismissed = true },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "بستن", tint = NeutralGray)
                    }
                }
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("جستجو") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { vm.testFreeAll() },
                enabled = !testing && connection.status == com.manfaz.vpn.vpn.ConnStatus.DISCONNECTED,
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text(if (testing) "در حال تست…" else "تست همه")
            }
            TextButton(
                onClick = { vm.refreshFreeConfigs() },
                enabled = !fetching && connection.status == com.manfaz.vpn.vpn.ConnStatus.CONNECTED,
            ) {
                if (fetching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text(if (fetching) "دریافت…" else "به‌روزرسانی")
            }
            TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                Icon(
                    if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "فقط علاقه‌مندی‌ها", modifier = Modifier.size(18.dp),
                    tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else NeutralGray,
                )
            }
        }
        Text("${sorted.size} کانفیگ".toFarsiDigits(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp))

        if (sorted.isEmpty()) {
            Spacer(Modifier.size(40.dp))
            Text("هنوز کانفیگ رایگانی دریافت نشده است. پس از اتصال، به‌طور خودکار پر می‌شود.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sorted, key = { it.id }) { server ->
                val faded = server.pingMs == null
                val isActive = server.id == activeId
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .alpha(if (faded) 0.45f else 1f)
                        .then(
                            if (isActive) Modifier.border(2.dp, ConnectedGreen, RoundedCornerShape(16.dp))
                            else Modifier
                        )
                        .clickable { vm.select(server); onConnect() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.freeAlias, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                server.protocol.label + if (isActive) "  •  متصل" else "",
                                color = if (isActive) ConnectedGreen else NeutralGray, fontSize = 12.sp, maxLines = 1)
                        }
                        val ping = server.pingMs
                        Text(
                            if (ping != null) "$ping ms".toFarsiDigits() else "—",
                            color = when {
                                ping == null -> NeutralGray
                                ping < 100 -> ConnectedGreen
                                ping < 180 -> BrandAmber
                                else -> FailedRed
                            },
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                        IconButton(onClick = { vm.toggleFreeFavorite(server.id) }) {
                            Icon(
                                if (server.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "علاقه‌مندی",
                                tint = if (server.favorite) MaterialTheme.colorScheme.primary else NeutralGray,
                            )
                        }
                        IconButton(onClick = { pendingDelete = server }) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = FailedRed)
                        }
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف کانفیگ") },
            text = { Text("«${toDelete.name}» حذف شود؟") },
            confirmButton = {
                TextButton(onClick = { vm.removeFree(toDelete.id); pendingDelete = null }) {
                    Text("حذف", color = FailedRed)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("انصراف") } },
        )
    }

    if (askCleanup) {
        val n = vm.staleFreeCount()
        AlertDialog(
            onDismissRequest = { askCleanup = false },
            title = { Text("پاک‌سازی کانفیگ‌های بی‌پینگ") },
            text = { Text("${n} کانفیگ بیش از ۳ روز بدون پینگ بوده‌اند. حذف شوند؟".toFarsiDigits()) },
            confirmButton = {
                TextButton(onClick = { vm.clearStaleFree(); askCleanup = false }) {
                    Text("حذف", color = FailedRed)
                }
            },
            dismissButton = { TextButton(onClick = { askCleanup = false }) { Text("نه فعلاً") } },
        )
    }
}
