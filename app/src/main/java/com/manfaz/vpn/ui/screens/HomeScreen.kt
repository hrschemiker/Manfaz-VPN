package com.manfaz.vpn.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.R
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.formatBytes
import com.manfaz.vpn.ui.ltr
import com.manfaz.vpn.ui.formatDuration
import com.manfaz.vpn.ui.formatSpeed
import com.manfaz.vpn.ui.theme.BrandOrange
import com.manfaz.vpn.ui.theme.BrandOrangeDeep
import com.manfaz.vpn.ui.theme.BrandOrangeLight
import com.manfaz.vpn.ui.theme.ConnectedGreen
import com.manfaz.vpn.ui.theme.FailedRed
import com.manfaz.vpn.ui.theme.NeutralGray
import com.manfaz.vpn.ui.toFarsiDigits
import com.manfaz.vpn.vpn.ConnStatus

@Composable
fun HomeScreen(vm: MainViewModel, onToggle: () -> Unit) {
    val state by vm.connection.collectAsState()
    val selected by vm.selected.collectAsState()
    val server = state.server ?: selected
    val showFailover by vm.failoverPrompt.collectAsState()

    if (showFailover) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissFailover() },
            title = { Text("اتصال ناموفق بود") },
            text = { Text("اتصال به این سرور برقرار نشد. آیا می‌خواهید به‌طور خودکار به بهترین سرور متصل شوید؟") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.selectBest(); onToggle() }) {
                    Text("بله، بهترین سرور")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissFailover() }) { Text("خیر") }
            },
        )
    }

    val clip by vm.clipboardPrompt.collectAsState()
    if (clip != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissClipboard() },
            title = { Text("کانفیگ در کلیپ‌بورد") },
            text = { Text("یک کانفیگ یا لینک اشتراک در کلیپ‌بورد پیدا شد. افزوده شود؟") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.importClipboard() }) { Text("افزودن") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissClipboard() }) { Text("خیر") }
            },
        )
    }

    // 1-second ticker so the duration updates live while connected
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.status) {
        while (state.status == ConnStatus.CONNECTED) {
            tick++
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top connection-condition pill (replaces the app name)
        StatusPill(status = state.status)

        Spacer(Modifier.height(28.dp))

        ConnectButton(status = state.status, onClick = onToggle)

        Spacer(Modifier.height(16.dp))

        Text(
            state.statusFa,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = when (state.status) {
                ConnStatus.CONNECTED -> ConnectedGreen
                ConnStatus.FAILED -> FailedRed
                ConnStatus.CONNECTING -> MaterialTheme.colorScheme.primary
                else -> NeutralGray
            },
        )

        if (state.status == ConnStatus.CONNECTED) {
            key(tick) {
                Text(
                    formatDuration(state.connectedSinceMs),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        state.error?.let {
            Text(it, color = FailedRed, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(24.dp))

        // Server info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    server?.displayLabel ?: "سروری انتخاب نشده است",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                InfoRow("پروتکل", server?.protocol?.label ?: "—")
                InfoRow("کشور", state.exitCountry.ifBlank { server?.displayCountry ?: "—" })
                InfoRow("آدرس IP خروجی", ltr(state.ip))
                InfoRow("پینگ", if (state.pingMs > 0) "${state.pingMs} ms".toFarsiDigits() else "—")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Live traffic stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("سرعت دانلود", formatSpeed(state.downloadSpeedBps), Modifier.weight(1f))
            StatTile("سرعت آپلود", formatSpeed(state.uploadSpeedBps), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("کل دانلود", formatBytes(state.totalDownloaded), Modifier.weight(1f))
            StatTile("کل آپلود", formatBytes(state.totalUploaded), Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        // Quick connect
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickButton("سریع‌ترین", Icons.Filled.Bolt, Modifier.weight(1f)) { vm.pickFastest(); onToggle() }
            QuickButton("تصادفی", Icons.Filled.Casino, Modifier.weight(1f)) { vm.pickRandom(); onToggle() }
        }

        Spacer(Modifier.height(20.dp))

        // Telegram banner
        TelegramBanner()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TelegramBanner() {
    val context = LocalContext.current
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.banner),
        contentDescription = "کانال تلگرام منفذ",
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/manfazvpn"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
    )
}

@Composable
private fun StatusPill(status: ConnStatus) {
    val (text, color) = when (status) {
        ConnStatus.CONNECTED -> "متصل هستید" to ConnectedGreen
        ConnStatus.SCANNING -> "در حال یافتن IP تمیز…" to MaterialTheme.colorScheme.primary
        ConnStatus.CONNECTING -> "در حال اتصال…" to MaterialTheme.colorScheme.primary
        ConnStatus.FAILED -> "اتصال ناموفق" to FailedRed
        ConnStatus.DISCONNECTED -> "متصل نیستید" to NeutralGray
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(text, color = color, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
private fun ConnectButton(status: ConnStatus, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
    )
    val connected = status == ConnStatus.CONNECTED
    val connecting = status == ConnStatus.CONNECTING || status == ConnStatus.SCANNING
    // Green highlight ring when connected, orange while connecting, neutral otherwise
    val ringColor = when (status) {
        ConnStatus.CONNECTED -> ConnectedGreen
        ConnStatus.FAILED -> FailedRed
        ConnStatus.CONNECTING, ConnStatus.SCANNING -> MaterialTheme.colorScheme.primary
        else -> NeutralGray
    }
    // Logo: full color when connected, desaturated (gray) when not
    val logoFilter = if (connected) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    val animate = connected || connecting

    Box(contentAlignment = Alignment.Center) {
        // Outer highlight ring
        Box(
            Modifier
                .size(230.dp)
                .scale(if (animate) pulse else 1f)
                .background(ringColor.copy(alpha = if (connected) 0.20f else 0.12f), CircleShape)
        )
        // Button surface
        Box(
            Modifier
                .size(184.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.manfaz_logo),
                contentDescription = if (connected) "قطع اتصال" else "اتصال",
                colorFilter = logoFilter,
                modifier = Modifier.size(150.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun QuickButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
