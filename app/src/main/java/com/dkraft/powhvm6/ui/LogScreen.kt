package com.dkraft.powhvm6.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dkraft.powhvm6.MainViewModel

@Composable
fun LogScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val lines = vm.logLines
    LaunchedEffect(lines.size, vm.logLoading) {
        if (lines.isNotEmpty() && !vm.logLoading) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    vm.logLoading && lines.isEmpty() -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    vm.logError != null -> Text(
                        vm.logError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    !vm.logLoading && lines.isEmpty() -> Text(
                        "The log is empty.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = { vm.loadLog() }, enabled = !vm.logLoading) { Text("Refresh") }
            }
        }
        itemsIndexed(lines, key = { i, line -> "$i:$line" }) { _, line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
