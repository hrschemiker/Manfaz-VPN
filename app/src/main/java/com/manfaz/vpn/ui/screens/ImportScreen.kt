package com.manfaz.vpn.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.manfaz.vpn.data.model.Subscription
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.formatBytes
import com.manfaz.vpn.ui.theme.BrandOrange
import com.manfaz.vpn.ui.theme.ConnectedGreen
import com.manfaz.vpn.ui.theme.FailedRed
import com.manfaz.vpn.ui.theme.NeutralGray
import com.manfaz.vpn.ui.toFarsiDigits

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ImportScreen(vm: MainViewModel, onImported: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var subName by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Subscription?>(null) }

    val subscriptions by vm.subscriptions.collectAsState()
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val scope = rememberCoroutineScope()

    // Feature: import a QR from a gallery image
    val galleryPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.launch {
            val decoded = QrImage.decode(context, uri)
            if (decoded != null) {
                val result = vm.importText(decoded)
                if (importSucceeded(result)) onImported() else message = result
            } else message = "کدی در تصویر پیدا نشد."
        }
    }

    // Feature B15: import configs from a text/JSON file
    val filePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.launch {
            val txt = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (txt.isNullOrBlank()) message = "خواندن فایل ممکن نشد."
            else {
                val result = vm.importText(txt)
                if (importSucceeded(result)) onImported() else message = result
            }
        }
    }

    if (scanning) {
        QrScanner { code ->
            scanning = false
            val result = vm.importText(code)
            if (importSucceeded(result)) onImported() else message = result
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("افزودن کانفیگ", fontWeight = FontWeight.Black, fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(6.dp))
        Text(
            "vless:// vmess:// trojan:// ss:// socks:// hysteria2:// tuic:// و لینک اشتراک (Base64).",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Spacer(Modifier.size(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("لینک کانفیگ را وارد کنید") },
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        Spacer(Modifier.size(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { readClipboard(context)?.let { text = it } },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("کلیپ‌بورد")
            }
            OutlinedButton(
                onClick = {
                    if (cameraPermission.status.isGranted) scanning = true
                    else cameraPermission.launchPermissionRequest()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("اسکن QR")
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { galleryPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("QR از تصویر")
            }
            OutlinedButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("از فایل")
            }
        }
        Spacer(Modifier.size(12.dp))
        Button(
            onClick = {
                val result = vm.importText(text)
                if (importSucceeded(result)) onImported() else message = result
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("افزودن", fontWeight = FontWeight.Bold)
        }
        message?.let {
            Spacer(Modifier.size(12.dp))
            val isError = Regex("نامعتبر|پیدا نشد|هیچ|خطا|یافت نشد").containsMatchIn(it)
            val tint = if (isError) FailedRed else ConnectedGreen
            Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }

        // ---- Subscriptions ----
        Spacer(Modifier.size(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("اشتراک‌ها", fontWeight = FontWeight.Black, fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (subscriptions.isNotEmpty()) {
                TextButton(onClick = { vm.updateAllSubscriptions() }) { Text("به‌روزرسانی همه") }
            }
        }
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(value = subName, onValueChange = { subName = it },
            label = { Text("نام اشتراک") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(value = subUrl, onValueChange = { subUrl = it },
            label = { Text("آدرس لینک اشتراک (https://…)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(8.dp))
        Button(
            onClick = {
                if (subUrl.isNotBlank()) {
                    vm.addSubscription(subName, subUrl)
                    subName = ""; subUrl = ""; onImported()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("افزودن اشتراک", fontWeight = FontWeight.Bold) }

        Spacer(Modifier.size(12.dp))
        subscriptions.forEach { sub -> SubscriptionCard(sub, vm) { pendingDelete = sub } }
        Spacer(Modifier.size(24.dp))
    }
    pendingDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف اشتراک") },
            text = { Text("اشتراک «${sub.name}» و ${sub.serverCount.toString().toFarsiDigits()} سرور وابسته حذف شوند؟") },
            confirmButton = {
                TextButton(onClick = { vm.removeSubscription(sub.id); pendingDelete = null }) {
                    Text("حذف", color = FailedRed)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun SubscriptionCard(sub: Subscription, vm: MainViewModel, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub.name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = sub.enabled, onCheckedChange = { vm.toggleSubscription(sub.id, it) })
            }
            Text("${sub.serverCount} سرور".toFarsiDigits(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

            if (sub.totalBytes > 0) {
                Spacer(Modifier.size(8.dp))
                val fraction = (sub.usedBytes.toFloat() / sub.totalBytes).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(4.dp))
                Text(
                    "مصرف: ${formatBytes(sub.usedBytes)} از ${formatBytes(sub.totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
            }
            sub.remainingDays?.let {
                Text("${it} روز باقی‌مانده".toFarsiDigits(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            sub.lastError?.let {
                Text(it, color = FailedRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { vm.updateSubscription(sub.id) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "به‌روزرسانی", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = FailedRed)
                }
            }
        }
    }
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun importSucceeded(message: String): Boolean =
    !Regex("نامعتبر|پیدا نشد|هیچ|خطا|یافت نشد").containsMatchIn(message)
