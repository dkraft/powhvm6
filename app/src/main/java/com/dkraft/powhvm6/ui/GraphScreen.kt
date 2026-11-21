package com.dkraft.powhvm6.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dkraft.powhvm6.MainViewModel
import com.dkraft.powhvm6.Snapshot

@Composable
fun GraphScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snap: Snapshot? = vm.snapshot
    val host = vm.settings.host

    if (host.isEmpty()) {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("No host set.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the search icon in the top bar to find the Pi, or open Settings to enter an IP address.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    if (snap == null) {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val err = vm.error
                Text(
                    if (err != null) "Connecting… ($err)" else "Connecting…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    if (snap.history.isEmpty()) {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "No history yet. The graph fills in as the Pi collects samples.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HistoryCard(snap.history, graphHeight = 280.dp) }
        item {
            val oldest = snap.history.firstOrNull()?.ageS
            val span = if (oldest == null) ""
            else if (oldest >= 3600) "About ${"%.0f".format(oldest / 3600)} h of samples, newest on the right."
            else if (oldest >= 60) "About ${"%.0f".format(oldest / 60)} min of samples, newest on the right."
            else "Newest samples on the right."
            Text(
                span,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
