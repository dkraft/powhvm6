package com.dkraft.powhvm6.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dkraft.powhvm6.Found
import com.dkraft.powhvm6.MainViewModel

@Composable
fun SearchScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        vm.search()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Finding POW-HVM6 on the LAN", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Broadcasts a discovery packet and scans the local subnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.searching) {
                            CircularProgressIndicator(modifier = Modifier.height(24.dp))
                            Spacer(Modifier.weight(1f))
                        } else {
                            Text(
                                if (vm.foundDevices.isEmpty()) "No devices found." else "${vm.foundDevices.size} device(s) found.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = { vm.search() }) { Text("Rescan") }
                    }
                }
            }
        }

        for (d in vm.foundDevices) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.ip, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${d.name} ${d.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        TextButton(onClick = {
                            vm.pickDevice(d)
                        }) { Text("Use") }
                    }
                }
            }
        }
    }
}
