package com.manfaz.vpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.R
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.components.ManfazScreen
import com.manfaz.vpn.ui.formatBytes
import com.manfaz.vpn.ui.ltr
import com.manfaz.vpn.ui.formatDuration
import com.manfaz.vpn.ui.formatSpeed
import com.manfaz.vpn.ui.theme.ConnectedGreen
import com.manfaz.vpn.ui.theme.FailedRed
import com.manfaz.vpn.ui.theme.NeutralGray
import com.manfaz.vpn.ui.toFarsiDigits
import com.manfaz.vpn.vpn.ConnStatus

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onToggle: () -> Unit,
    onConnectServer: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val state by vm.connection.collectAsState()
    val selected by vm.selected.collectAsState()
    val servers by vm.servers.collectAsState()
    val server = state.server ?: selected
    val showFailover by vm.failoverPrompt.collectAsState()

    if (showFailover) {
        AlertDialog(
            onDismissRequest = { vm.dismissFailover() },
            title = { Text("اتصال ناموفق بود") },
            text = { Text("اتصال به این سرور برقرار نشد. آیا می‌خواهید به‌طور خودکار به بهترین سرور متصل شوید؟") },
            confirmButton = {
                TextButton(onClick = { vm.selectBest(); onConnectServer() }) {
                    Text("بله، بهترین سرور")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissFailover() }) { Text("خیر") }
            },
        )
    }

    val clip by vm.clipboardPrompt.collectAsState()
    if (clip != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissClipboard() },
            title = { Text("کانفیگ در کلیپ‌بورد") },
            text = { Text("یک کانفیگ یا لینک اشتراک در کلیپ‌بورد پیدا شد. افزوده شود؟") },
            confirmButton = {
                TextButton(onClick = { vm.importClipboard() }) { Text("افزودن") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissClipboard() }) { Text("خیر") }
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

    ManfazScreen(title = "منفذ") { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The kill switch is the one state the user must be able to escape from, so it
            // gets the first and most prominent slot on the screen.
            AnimatedVisibility(visible = state.status == ConnStatus.BLOCKED) {
                KillSwitchBanner(
                    reason = state.error,
                    onRelease = vm::releaseKillSwitch,
                    onRetry = { onConnectServer() },
                )
            }

            StatusPill(status = state.status)

            Spacer(Modifier.height(24.dp))

            ConnectButton(status = state.status, onClick = onToggle)

            Spacer(Modifier.height(16.dp))

            Text(
                state.statusFa,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = statusColor(state.status),
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
            // A BLOCKED reason is already spelled out by the banner above.
            if (state.status != ConnStatus.BLOCKED) {
                state.error?.let {
                    Text(
                        it,
                        color = FailedRed,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            ServerCard(
                server = server,
                exitCountry = state.exitCountry,
                ip = state.ip,
                pingMs = state.pingMs,
                hasServers = servers.isNotEmpty(),
                onClick = onOpenServers,
            )

            Spacer(Modifier.height(16.dp))

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

            // Quick connect: switching while already connected must connect the new server,
            // not disconnect (onToggle would tear the tunnel down).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickButton(
                    label = "سریع‌ترین",
                    icon = Icons.Filled.Bolt,
                    enabled = servers.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { vm.pickFastest(); onConnectServer() }
                QuickButton(
                    label = "تصادفی",
                    icon = Icons.Filled.Casino,
                    enabled = servers.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { vm.pickRandom(); onConnectServer() }
            }

            Spacer(Modifier.height(20.dp))

            TelegramBanner()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun statusColor(status: ConnStatus) = when (status) {
    ConnStatus.CONNECTED -> ConnectedGreen
    ConnStatus.FAILED, ConnStatus.BLOCKED -> FailedRed
    ConnStatus.CONNECTING, ConnStatus.SCANNING -> MaterialTheme.colorScheme.primary
    ConnStatus.DISCONNECTED -> NeutralGray
}

/**
 * Shown the moment the kill switch cuts traffic. Without an in-app way back online the user
 * is stranded with a phone that looks broken, so the release action lives right here as well
 * as in the ongoing notification.
 */
@Composable
private fun KillSwitchBanner(reason: String?, onRelease: () -> Unit, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FailedRed.copy(alpha = 0.13f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = FailedRed, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    "کلید قطع اضطراری فعال است",
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = FailedRed,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                reason?.takeIf { it.isNotBlank() }
                    ?: "برای جلوگیری از نشت اطلاعات، همهٔ ترافیک دستگاه متوقف شده است.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "تا زمانی که اینترنت را آزاد نکنید یا دوباره وصل نشوید، دستگاه به اینترنت دسترسی ندارد.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRelease,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FailedRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("آزاد کردن اینترنت", fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("تلاش دوباره") }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: com.manfaz.vpn.data.model.ServerConfig?,
    exitCountry: String,
    ip: String,
    pingMs: Int,
    hasServers: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        server?.displayLabel
                            ?: if (hasServers) "سروری انتخاب نشده است" else "هنوز سروری اضافه نکرده‌اید",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (server != null) "برای تغییر سرور لمس کنید" else "برای افزودن سرور لمس کنید",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = NeutralGray,
                )
            }
            Spacer(Modifier.height(12.dp))
            InfoRow("پروتکل", server?.transportLabel ?: "—")
            InfoRow("کشور", exitCountry.ifBlank { server?.displayCountry ?: "—" })
            InfoRow("آدرس IP خروجی", ltr(ip))
            InfoRow("پینگ", if (pingMs > 0) "$pingMs ms".toFarsiDigits() else "—")
        }
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
            .clickable(role = Role.Button) {
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
    val text = when (status) {
        ConnStatus.CONNECTED -> "متصل هستید"
        ConnStatus.SCANNING -> "در حال یافتن IP تمیز…"
        ConnStatus.CONNECTING -> "در حال اتصال…"
        ConnStatus.FAILED -> "اتصال ناموفق"
        ConnStatus.BLOCKED -> "اینترنت مسدود است"
        ConnStatus.DISCONNECTED -> "متصل نیستید"
    }
    val color = statusColor(status)
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
    val connected = status == ConnStatus.CONNECTED
    val connecting = status == ConnStatus.CONNECTING || status == ConnStatus.SCANNING
    val animate = connected || connecting
    val ringColor = statusColor(status)

    // The infinite transition is only created while something is actually animating, so an
    // idle home screen does not keep a frame callback (and the GPU) busy forever.
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 1f, targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
        ).value
    } else {
        1f
    }

    // Logo: full color when connected, desaturated (gray) when not
    val logoFilter = if (connected) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    val label = when (status) {
        ConnStatus.CONNECTED -> "قطع اتصال"
        ConnStatus.CONNECTING, ConnStatus.SCANNING -> "لغو اتصال"
        ConnStatus.BLOCKED -> "آزاد کردن اینترنت"
        else -> "اتصال"
    }

    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(230.dp)
                .scale(pulse)
                .background(ringColor.copy(alpha = if (connected) 0.20f else 0.12f), CircleShape)
        )
        Box(
            Modifier
                .size(184.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.manfaz_logo),
                contentDescription = label,
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
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun QuickButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (enabled) MaterialTheme.colorScheme.primary else NeutralGray
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                label,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else NeutralGray,
            )
        }
    }
}
