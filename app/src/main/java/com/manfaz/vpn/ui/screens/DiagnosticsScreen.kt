package com.manfaz.vpn.ui.screens

import android.content.ClipData
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.ui.components.ManfazScreen
import com.manfaz.vpn.util.LogBuffer
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf(LogBuffer.read(context)) }
    val scrollState = rememberScrollState()
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The newest events are appended at the end, so land the user there instead of making
    // them scroll through the whole history to find what just happened.
    LaunchedEffect(log) { scrollState.scrollTo(scrollState.maxValue) }

    ManfazScreen(
        title = "گزارش و عیب‌یابی",
        onBack = onBack,
        actions = {
            IconButton(onClick = { shareLog(context, log) }) {
                Icon(Icons.Filled.Share, contentDescription = "ارسال گزارش")
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
            Text(
                "این گزارش فقط روی دستگاه شما ذخیره می‌شود. پیش از ارسال، محتوای آن را ببینید.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.size(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, log)
                        scope.launch { snackHost.showSnackbar("گزارش در کلیپ‌بورد کپی شد.") }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("کپی") }
                OutlinedButton(
                    onClick = { LogBuffer.clear(context); log = LogBuffer.read(context) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("پاک کردن") }
                OutlinedButton(
                    onClick = { log = LogBuffer.read(context) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("تازه‌سازی") }
            }
            Spacer(Modifier.size(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    log,
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Log lines line up only in a monospaced face.
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            SnackbarHost(snackHost)
            Spacer(Modifier.size(12.dp))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Manfaz log", text))
}

private fun shareLog(context: Context, text: String) {
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text),
                "ارسال گزارش عیب‌یابی",
            ),
        )
    }
}
