package com.dkraft.powhvm6.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dkraft.powhvm6.EventMetric
import com.dkraft.powhvm6.MainViewModel
import com.dkraft.powhvm6.PowerEvent
import com.dkraft.powhvm6.Tracker
import com.dkraft.powhvm6.TrackerDraft
import com.dkraft.powhvm6.fmtDate
import com.dkraft.powhvm6.fmtDuration
import com.dkraft.powhvm6.fmtKwh
import com.dkraft.powhvm6.fmtValue
import com.dkraft.powhvm6.fmtWhen
import kotlinx.coroutines.delay

private val RUNNING = Color(0xFFE53935)
private val STARTING = Color(0xFFFFA000)

/** Wall-clock ms, ticking once a second (drives "running now" timers between polls). */
@Composable
private fun rememberNowMs(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    return now
}

/** Elapsed seconds of the live event: the Pi's figure plus time since that reply (capped so a dead link doesn't run away). */
private fun liveElapsed(t: Tracker, fetchedAtMs: Long, nowMs: Long): Double? {
    val e = t.elapsedS ?: return null
    val extra = ((nowMs - fetchedAtMs) / 1000.0).coerceIn(0.0, 15.0)
    return e + extra
}

private sealed class Pending {
    data class Menu(val t: Tracker) : Pending()
    data class Reset(val t: Tracker) : Pending()
    data class Delete(val t: Tracker) : Pending()
    data class Form(val id: Long?, val draft: TrackerDraft) : Pending()
}

@Composable
fun EventsScreen(
    vm: MainViewModel,
    historyId: Long?,
    onOpenHistory: (Long) -> Unit,
    onCloseHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val list = vm.events
    val nowMs = rememberNowMs()
    var pending by remember { mutableStateOf<Pending?>(null) }

    if (vm.settings.host.isEmpty()) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            Text("No host set. Find the Pi with the search icon, or enter it in Settings.")
        }
        return
    }

    if (historyId != null) {
        val tracker = list?.trackers?.firstOrNull { it.id == historyId }
        HistoryView(vm, historyId, tracker, nowMs, modifier, onLongPress = { t -> pending = Pending.Menu(t) })
    } else {
        Box(modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                vm.eventsError?.let { err ->
                    item { ErrorCard(err) }
                }
                if (list == null) {
                    if (vm.eventsError == null) item {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (list.trackers.isEmpty()) {
                    item {
                        Text(
                            "No trackers yet. Tap + to watch for a power event (for example a pump starting).",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    items(list.trackers, key = { it.id }) { t ->
                        TrackerCard(
                            t, liveElapsed(t, vm.eventsFetchedAtMs, nowMs),
                            onClick = { onOpenHistory(t.id) },
                            onLongClick = { pending = Pending.Menu(t) }
                        )
                    }
                    item {
                        Text(
                            "Long-press a tracker to edit, pause, reset or delete it. History is kept ${list.keepDays} days on the Pi.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
            if (list != null) {
                FloatingActionButton(
                    onClick = { pending = Pending.Form(null, list.defaults) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add event tracker")
                }
            }
        }
    }

    when (val p = pending) {
        is Pending.Menu -> TrackerMenu(
            p.t,
            onDismiss = { pending = null },
            onEdit = { pending = Pending.Form(p.t.id, TrackerDraft.from(p.t)) },
            onPauseResume = {
                vm.trackerAction(p.t.id, if (p.t.enabled) "pause" else "resume")
                pending = null
            },
            onReset = { pending = Pending.Reset(p.t) },
            onDelete = { pending = Pending.Delete(p.t) }
        )
        is Pending.Reset -> ConfirmDialog(
            title = "Reset ${p.t.name}?",
            text = "Count (${p.t.count}) and energy (${fmtKwh(p.t.kwh)}) go back to zero, counting from now. Event history is kept.",
            confirm = "Reset",
            onConfirm = { vm.trackerAction(p.t.id, "reset"); pending = null },
            onDismiss = { pending = null }
        )
        is Pending.Delete -> ConfirmDialog(
            title = "Delete ${p.t.name}?",
            text = "This removes the tracker and all ${p.t.count}+ recorded events for it from the Pi. This can't be undone.",
            confirm = "Delete",
            onConfirm = {
                vm.trackerAction(p.t.id, "delete")
                if (historyId == p.t.id) onCloseHistory()
                pending = null
            },
            onDismiss = { pending = null }
        )
        is Pending.Form -> TrackerForm(
            vm,
            id = p.id,
            initial = p.draft,
            metrics = list?.metrics ?: emptyList(),
            onDismiss = { pending = null }
        )
        null -> {}
    }
}

@Composable
private fun ErrorCard(err: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            err,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StatusLine(t: Tracker, elapsed: Double?) {
    val (dot, text) = when {
        !t.enabled -> MaterialTheme.colorScheme.outline to "Paused"
        t.running && t.ending -> RUNNING to "Running now · ${fmtDuration(elapsed ?: 0.0)} · ending…"
        t.running -> RUNNING to "Running now · ${fmtDuration(elapsed ?: 0.0)}"
        t.candidate -> STARTING to "Starting? · ${fmtDuration(elapsed ?: 0.0)} of ${fmtDuration(t.minS)}"
        else -> null to null
    }
    if (text == null || dot == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = dot)
        if ((t.running || t.candidate) && t.enabled) {
            val extra = buildList {
                t.liveValue?.let { add("now ${fmtValue(it, t.unit)}") }
                t.livePeak?.let { add("peak ${fmtValue(it, t.unit)}") }
                if (t.unit == "W") t.liveKwh?.let { add(fmtKwh(it)) }
            }.joinToString(" · ")
            if (extra.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    extra, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackerCard(t: Tracker, elapsed: Double?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clipCard()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                t.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (t.enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                t.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Stat("Count", t.count.toString(), Modifier.weight(1f))
                Stat("Energy", if (t.unit == "W") fmtKwh(t.kwh) else "—", Modifier.weight(1f))
                Stat("Since", fmtDate(t.sinceMs), Modifier.weight(1f))
            }
            Text(
                "Totals since reset ${fmtWhen(t.sinceMs)} · run time ${fmtDuration(t.totalS)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            StatusLine(t, elapsed)
            t.lastEvent?.let { e ->
                Text(
                    "Last: ${fmtWhen(e.startMs)} · ${fmtDuration(e.durationS)} · peak ${fmtValue(e.peak, t.unit)}" +
                        (if (t.unit == "W") " · ${fmtKwh(e.kwh)}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun Modifier.clipCard(): Modifier = this.then(Modifier.background(Color.Transparent, RoundedCornerShape(12.dp)))

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun HistoryView(
    vm: MainViewModel,
    id: Long,
    tracker: Tracker?,
    nowMs: Long,
    modifier: Modifier,
    onLongPress: (Tracker) -> Unit
) {
    val h = vm.eventHistory?.takeIf { it.tracker.id == id }
    val t = tracker ?: h?.tracker
    // Refresh the history whenever the Pi's count changes (a new event finished).
    LaunchedEffect(id, tracker?.count, tracker?.lastEvent?.id) { vm.loadEventHistory(id) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (t != null) {
            item {
                TrackerCard(t, liveElapsed(t, vm.eventsFetchedAtMs, nowMs), onClick = {}, onLongClick = { onLongPress(t) })
            }
        }
        vm.eventHistoryError?.let { item { ErrorCard(it) } }
        if (h == null) {
            if (vm.eventHistoryError == null) item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (h.events.isEmpty()) {
            item {
                Text("No events recorded yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            }
        } else {
            item {
                val shown = (if (h.total > h.events.size) "Latest ${h.events.size} of ${h.total} events" else "${h.total} events") +
                    " in history (kept ${vm.events?.keepDays ?: 90} days; totals above run until reset)"
                Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            item { HistoryHeader(h.tracker.unit) }
            items(h.events, key = { it.id }) { e -> HistoryRow(e, h.tracker.unit) }
        }
    }
}

@Composable
private fun HistoryHeader(unit: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        val st = MaterialTheme.typography.labelSmall
        val c = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        Text("Started", style = st, color = c, modifier = Modifier.weight(1.6f))
        Text("Duration", style = st, color = c, modifier = Modifier.weight(1f))
        Text("Peak", style = st, color = c, modifier = Modifier.weight(1f))
        if (unit == "W") Text("Energy", style = st, color = c, modifier = Modifier.weight(1.1f))
    }
    HorizontalDivider()
}

@Composable
private fun HistoryRow(e: PowerEvent, unit: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val st = MaterialTheme.typography.bodyMedium
        Text(fmtWhen(e.startMs), style = st, modifier = Modifier.weight(1.6f))
        Text(fmtDuration(e.durationS) + if (e.truncated) "+" else "", style = st, modifier = Modifier.weight(1f))
        Text(fmtValue(e.peak, unit), style = st, modifier = Modifier.weight(1f))
        if (unit == "W") Text(fmtKwh(e.kwh), style = st, modifier = Modifier.weight(1.1f))
    }
}

@Composable
private fun TrackerMenu(
    t: Tracker,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onPauseResume: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.name) },
        text = {
            Column {
                MenuRow(Icons.Default.Edit, "Edit", onEdit)
                if (t.enabled) MenuRow(Icons.Default.Pause, "Pause", onPauseResume)
                else MenuRow(Icons.Default.PlayArrow, "Resume", onPauseResume)
                MenuRow(Icons.Default.RestartAlt, "Reset count & kWh", onReset)
                MenuRow(Icons.Default.Delete, "Delete", onDelete, MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = Color.Unspecified) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.primary else tint)
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint)
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerForm(
    vm: MainViewModel,
    id: Long?,
    initial: TrackerDraft,
    metrics: List<EventMetric>,
    onDismiss: () -> Unit
) {
    var d by remember(id) { mutableStateOf(initial) }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val metric = metrics.firstOrNull { it.id == d.metric }
    val unit = metric?.unit ?: ""

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (id == null) "New event tracker" else "Edit tracker") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.name, onValueChange = { d = d.copy(name = it.take(40)) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                MetricPicker(metrics, d.metric) { d = d.copy(metric = it) }

                Text("Starts when", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OpValueRow(d.startOp, d.startValue, unit, { d = d.copy(startOp = it) }, { d = d.copy(startValue = it) })
                NumField("for at least (seconds)", d.minS) { d = d.copy(minS = it) }

                Text("Ends when", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OpValueRow(d.endOp, d.endValue, unit, { d = d.copy(endOp = it) }, { d = d.copy(endValue = it) })
                NumField("held for (seconds)", d.endHoldS) { d = d.copy(endHoldS = it) }

                NumField("Max duration (minutes, blank = none)", d.maxMin) { d = d.copy(maxMin = it) }
                Text(
                    "Shorter blips than the minimum aren't counted. A gap between start and end values stops flicker from splitting one event in two.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    err = null
                    vm.saveTracker(id, d) { e ->
                        saving = false
                        if (e == null) onDismiss() else err = e
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MetricPicker(metrics: List<EventMetric>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = metrics.firstOrNull { it.id == selected }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(cur?.let { "${it.label} (${it.unit})" } ?: selected, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose reading")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in metrics) {
                DropdownMenuItem(
                    text = { Text("${m.label} (${m.unit})") },
                    onClick = { onPick(m.id); open = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpValueRow(op: String, value: String, unit: String, onOp: (String) -> Unit, onValue: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = op == ">", onClick = { onOp(">") }, label = { Text("above") })
        FilterChip(selected = op == "<", onClick = { onOp("<") }, label = { Text("below") })
        OutlinedTextField(
            value = value, onValueChange = onValue, singleLine = true,
            suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValue, singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}
