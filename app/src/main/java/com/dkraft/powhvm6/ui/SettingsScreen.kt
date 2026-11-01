package com.dkraft.powhvm6.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dkraft.powhvm6.HostAddr
import com.dkraft.powhvm6.MainViewModel
import com.dkraft.powhvm6.Settings
import com.dkraft.powhvm6.ThemeMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var hostText by remember { mutableStateOf(vm.settings.host) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Server", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it.trim() },
                        label = { Text("IP address or hostname") },
                        placeholder = { Text("10.0.0.69 (port defaults to 8088)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            hostText = HostAddr.normalize(hostText)
                            if (hostText.isNotEmpty()) {
                                vm.startPolling(hostText)
                            }
                        }) { Text("Connect") }
                        TextButton(onClick = { vm.search() }) { Text("Search LAN") }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Inverter settings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Values the Pi can change on the inverter. Each change is confirmed and read back.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    val msg = vm.settingsMessage
                    if (msg != null) Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    for (d in vm.settingDefs) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (d.enabled) Modifier.clickable { vm.beginEdit(key = d.key) } else Modifier)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(d.label, style = MaterialTheme.typography.bodyLarge)
                                if (!d.enabled) Text("read-only", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(d.labelFor(d.current), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    TextButton(onClick = { vm.loadSettings() }) { Text("Refresh") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Night mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "System follows your phone's dark theme setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    ThemeModeSelector(vm)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Displayed fields", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Toggle which fields appear on the dashboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    FieldToggles(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeModeSelector(vm: MainViewModel) {
    val modes = ThemeMode.values()
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = vm.themeMode == mode,
                onClick = { vm.updateThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size)
            ) { Text(mode.label) }
        }
    }
}

@Composable
fun FieldToggles(vm: MainViewModel) {
    // Use the last known snapshot's field list. If none yet, use a fallback.
    val snap = vm.snapshot
    val fields = snap?.fields ?: emptyList()

    if (fields.isEmpty()) {
        Text(
            "Connect to a server to see the field list.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val groups = linkedMapOf<String, MutableList<com.dkraft.powhvm6.Field>>()
    for (f in fields) groups.getOrPut(f.group) { mutableListOf() }.add(f)

    for ((g, list) in groups) {
        Text(
            text = g.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        for (f in list) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    f.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = vm.isFieldVisible(f.id),
                    onCheckedChange = { vm.setFieldVisible(f.id, it) }
                )
            }
        }
    }
}
