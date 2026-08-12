package com.manfaz.vpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.ui.MainViewModel

@Composable
fun ConfigEditorScreen(vm: MainViewModel, serverId: String, onDone: () -> Unit) {
    val original = remember(serverId) { ServerRepository.get(serverId) }
    if (original == null) { onDone(); return }

    var name by remember { mutableStateOf(original.name) }
    var address by remember { mutableStateOf(original.address) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var sni by remember { mutableStateOf(original.sni) }
    var host by remember { mutableStateOf(original.host) }
    var path by remember { mutableStateOf(original.path) }
    var cred by remember { mutableStateOf(original.uuid.ifBlank { original.password }) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
            }
            Text("ویرایش کانفیگ", fontWeight = FontWeight.Black, fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.size(12.dp))

        Field("نام", name) { name = it }
        Field("آدرس سرور", address) { address = it }
        Field("پورت", port) { port = it.filter { c -> c.isDigit() } }
        Field("SNI", sni) { sni = it }
        Field("Host", host) { host = it }
        Field("Path", path) { path = it }
        Field(if (original.uuid.isNotBlank()) "شناسه (UUID)" else "رمز عبور", cred) { cred = it }

        Spacer(Modifier.size(16.dp))
        Button(
            onClick = {
                val updated = original.copy(
                    name = name.ifBlank { original.name },
                    address = address.trim(),
                    port = port.toIntOrNull() ?: original.port,
                    sni = sni.trim(), host = host.trim(), path = path.trim(),
                    uuid = if (original.uuid.isNotBlank()) cred.trim() else original.uuid,
                    password = if (original.uuid.isBlank()) cred.trim() else original.password,
                    pingMs = null, // params changed → re-test
                )
                ServerRepository.update(updated)
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("ذخیره", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
