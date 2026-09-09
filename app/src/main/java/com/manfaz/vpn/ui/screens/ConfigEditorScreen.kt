package com.manfaz.vpn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.ui.MainViewModel
import com.manfaz.vpn.ui.components.ManfazScreen

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
    var revealCred by remember { mutableStateOf(false) }

    val portValue = port.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val addressValid = address.isNotBlank()
    val canSave = portValid && addressValid

    ManfazScreen(title = "ویرایش کانفیگ", onBack = onDone) { inner ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "فقط فیلدهای پرکاربرد اینجا قابل ویرایش‌اند؛ بقیهٔ تنظیمات کانفیگ دست‌نخورده می‌مانند.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))

            Field("نام", name) { name = it }
            Field(
                label = "آدرس سرور",
                value = address,
                isError = !addressValid,
                supporting = if (!addressValid) "آدرس نمی‌تواند خالی باشد." else null,
            ) { address = it }
            Field(
                label = "پورت",
                value = port,
                isError = !portValid,
                supporting = if (!portValid) "پورت باید عددی بین ۱ تا ۶۵۵۳۵ باشد." else null,
                keyboardType = KeyboardType.Number,
            ) { port = it.filter { c -> c.isDigit() }.take(5) }
            Field("SNI", sni) { sni = it }
            Field("Host", host) { host = it }
            Field("Path", path) { path = it }
            Field(
                label = if (original.uuid.isNotBlank()) "شناسه (UUID)" else "رمز عبور",
                value = cred,
                // Credentials stay masked by default so the screen is safe to show to someone.
                masked = !revealCred,
                supporting = if (revealCred) "برای پنهان‌کردن دوباره لمس کنید." else "برای نمایش لمس کنید.",
                onSupportingClick = { revealCred = !revealCred },
            ) { cred = it }

            Spacer(Modifier.size(16.dp))
            Button(
                onClick = {
                    val updated = original.copy(
                        name = name.ifBlank { original.name },
                        address = address.trim(),
                        port = portValue ?: original.port,
                        sni = sni.trim(), host = host.trim(), path = path.trim(),
                        uuid = if (original.uuid.isNotBlank()) cred.trim() else original.uuid,
                        password = if (original.uuid.isBlank()) cred.trim() else original.password,
                        pingMs = null, // parameters changed, the old measurement is meaningless
                        latencyTested = false,
                    )
                    ServerRepository.update(updated)
                    onDone()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("ذخیره", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    isError: Boolean = false,
    supporting: String? = null,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSupportingClick: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supporting?.let { hint ->
            {
                Text(
                    hint,
                    modifier = if (onSupportingClick != null) {
                        Modifier.clickable(onClick = onSupportingClick)
                    } else {
                        Modifier
                    },
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
