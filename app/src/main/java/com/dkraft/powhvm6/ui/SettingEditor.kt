package com.dkraft.powhvm6.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dkraft.powhvm6.EditStage
import com.dkraft.powhvm6.MainViewModel
import kotlin.math.abs

/** Generic editor for any setting in the Pi's registry: pick → confirm → write → result. */
@Composable
fun SettingEditorHost(vm: MainViewModel) {
    val e = vm.edit ?: return
    val def = e.def
    val busy = e.stage == EditStage.LOADING || e.stage == EditStage.WRITING

    AlertDialog(
        onDismissRequest = { vm.dismissEdit() },
        properties = DialogProperties(dismissOnClickOutside = !busy, dismissOnBackPress = !busy),
        title = {
            Text(
                when (e.stage) {
                    EditStage.LOADING -> "Loading…"
                    EditStage.PICK -> "Set ${def?.label}"
                    EditStage.CONFIRM -> "Apply change?"
                    EditStage.WRITING -> "Writing to inverter…"
                    EditStage.DONE -> if (e.result?.ok == true) "Done" else "Not applied"
                    EditStage.ERROR -> "Can't edit"
                }
            )
        },
        text = {
            when (e.stage) {
                EditStage.LOADING, EditStage.WRITING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(if (e.stage == EditStage.WRITING) "Sending ${def?.labelFor(e.pending)} and reading it back. Don't close the app." else "Reading current value from the Pi…")
                }
                EditStage.PICK -> {
                    if (def == null) return@AlertDialog
                    Column {
                        if (def.description.isNotEmpty())
                            Text(def.description, style = MaterialTheme.typography.bodySmall)
                        Text("Current: ${def.labelFor(def.current)}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.size(8.dp))
                        val startIdx = def.options.indexOfFirst { abs(it.value - (def.current ?: Double.NaN)) < 1e-6 }.coerceAtLeast(0)
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 2).coerceAtLeast(0))
                        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 320.dp)) {
                            items(def.options) { opt ->
                                val selected = e.pending != null && abs(opt.value - e.pending) < 1e-6
                                val isCurrent = def.current != null && abs(opt.value - def.current) < 1e-6
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { vm.choose(opt.value) }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = { vm.choose(opt.value) })
                                    Text(opt.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    if (isCurrent) Text("  (current)", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
                EditStage.CONFIRM -> if (def != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (def.type == "enum") {
                        // Mode switches: spell out the option names on their own lines.
                        Text(def.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("From: ${def.labelFor(def.current)}", style = MaterialTheme.typography.bodyMedium)
                        Text("To: ${def.labelFor(e.pending)}", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("${def.label}: ${def.labelFor(def.current)} → ${def.labelFor(e.pending)}",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("This writes the new value to the inverter and reads it back.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (def.note.isNotEmpty()) Text(def.note, style = MaterialTheme.typography.bodySmall)
                }
                EditStage.DONE -> {
                    val r = e.result
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r?.message ?: "", color = if (r?.ok == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                        if (r?.readback != null && def != null)
                            Text("Inverter reports: ${def.labelFor(r.readback)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                EditStage.ERROR -> Text(e.message ?: "Unavailable")
            }
        },
        confirmButton = {
            when (e.stage) {
                EditStage.PICK -> {
                    val changed = def?.current == null || e.pending == null || abs(e.pending - def.current) > 1e-6
                    TextButton(enabled = changed, onClick = { vm.toConfirm() }) { Text("Next") }
                }
                EditStage.CONFIRM -> TextButton(onClick = { vm.applyEdit() }) { Text("Apply") }
                EditStage.DONE, EditStage.ERROR -> TextButton(onClick = { vm.dismissEdit() }) { Text("OK") }
                else -> {}
            }
        },
        dismissButton = {
            when (e.stage) {
                EditStage.PICK, EditStage.LOADING -> TextButton(onClick = { vm.dismissEdit() }) { Text("Cancel") }
                EditStage.CONFIRM -> TextButton(onClick = { vm.backToPick() }) { Text("Back") }
                else -> {}
            }
        }
    )
}
