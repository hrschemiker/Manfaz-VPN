package com.manfaz.vpn.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.ui.theme.NeutralGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val system: Boolean)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PerAppScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val pm = context.packageManager

    var enabled by remember { mutableStateOf(prefs.perAppEnabled) }
    var bypassMode by remember { mutableStateOf(prefs.perAppBypassMode) }
    var selected by remember { mutableStateOf(prefs.perAppSet) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .map {
                    AppEntry(
                        it.packageName,
                        pm.getApplicationLabel(it).toString(),
                        it.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                            it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("تونل هر برنامه", fontWeight = FontWeight.Black, fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(12.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("فعال‌سازی تونل هر برنامه", fontWeight = FontWeight.Medium, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                if (!it || bypassMode || selected.isNotEmpty()) {
                    enabled = it; prefs.perAppEnabled = it
                }
            })
        }
        Spacer(Modifier.size(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(selected = bypassMode, onClick = { bypassMode = true; prefs.perAppBypassMode = true },
                label = { Text("برنامه‌های انتخابی مستقیم") })
            FilterChip(selected = !bypassMode, onClick = {
                bypassMode = false; prefs.perAppBypassMode = false
                if (selected.isEmpty()) { enabled = false; prefs.perAppEnabled = false }
            },
                label = { Text("فقط برنامه‌های انتخابی از VPN") })
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem },
                label = { Text("نمایش برنامه‌های سیستمی") })
        }
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it },
            label = { Text("جستجوی برنامه") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(8.dp))

        val list = apps
        if (list == null) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        } else {
            val filtered = list.filter {
                (showSystem || !it.system) &&
                    (it.label.contains(query, true) || it.pkg.contains(query, true))
            }
            LazyColumn {
                items(filtered, key = { it.pkg }) { app ->
                    val checked = app.pkg in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            prefs.togglePerApp(app.pkg); selected = prefs.perAppSet
                            if (!bypassMode && selected.isEmpty()) {
                                enabled = false; prefs.perAppEnabled = false
                            }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(app.pkg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = checked, onCheckedChange = {
                            prefs.togglePerApp(app.pkg); selected = prefs.perAppSet
                            if (!bypassMode && selected.isEmpty()) {
                                enabled = false; prefs.perAppEnabled = false
                            }
                        })
                    }
                }
            }
        }
    }
}
