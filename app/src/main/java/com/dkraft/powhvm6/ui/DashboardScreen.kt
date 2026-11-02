package com.dkraft.powhvm6.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dkraft.powhvm6.BuildConfig
import com.dkraft.powhvm6.Field
import com.dkraft.powhvm6.HistoryPoint
import com.dkraft.powhvm6.MainViewModel
import com.dkraft.powhvm6.Snapshot
import com.dkraft.powhvm6.displayText
import com.dkraft.powhvm6.feedField
import com.dkraft.powhvm6.linkFields

@Composable
fun DashboardScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
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

    val visible = snap.fields.filter { vm.isFieldVisible(it.id) }
    val groups = linkedMapOf<String, MutableList<Field>>()
    val unit = vm.tempUnit
    for (f in visible) groups.getOrPut(f.group) { mutableListOf() }.add(f.copy(text = f.displayText(unit)))
    // Feed tile goes first in Status. The Pi script may predate it (feed == null): no tile.
    feedField(snap.feed)?.let { feed ->
        if (vm.isFieldVisible(feed.id)) groups.getOrPut(feed.group) { mutableListOf() }.add(0, feed)
    }

    val editable = vm.editableFieldIds()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (snap.linkOk) Color(0xFF4CAF50) else Color(0xFFE53935),
                            RoundedCornerShape(5.dp)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (snap.linkOk) "Live" else "Offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (snap.linkOk) Color(0xFF4CAF50) else Color(0xFFE53935)
                )
                Text(
                    text = " · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        for ((groupName, fields) in groups) {
            item { GroupCard(groupName, fields, editable, onEdit = { vm.beginEdit(fieldId = it) }) }
        }

        if (snap.history.isNotEmpty()) {
            item { HistoryCard(snap.history) }
        }

        val link = linkFields(vm.appLink, snap.piLink).filter { vm.isFieldVisible(it.id) }
        if (link.isNotEmpty()) {
            item { GroupCard("Link", link) }
        }
    }
}

@Composable
fun GroupCard(
    groupName: String,
    fields: List<Field>,
    editable: Set<String> = emptySet(),
    onEdit: (String) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = groupName.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            val rows = (fields.size + 1) / 2
            for (r in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Column-major: left column is fields[0 until rows], right is the rest.
                    // (Using size / 2 here duplicated the middle field for odd counts.)
                    val left = fields.getOrNull(r)
                    val right = fields.getOrNull(r + rows)
                    FieldCell(left, modifier = Modifier.weight(1f), editable = left?.id in editable, onEdit = onEdit)
                    if (right != null) FieldCell(right, modifier = Modifier.weight(1f), editable = right.id in editable, onEdit = onEdit)
                    else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun FieldCell(
    field: Field?,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onEdit: (String) -> Unit = {}
) {
    if (field == null) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (editable) Modifier.clickable { onEdit(field.id) } else Modifier)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = field.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when (field.tone) {
                    1 -> Color(0xFF4CAF50)
                    2 -> Color(0xFFE53935)
                    3 -> Color(0xFFFFA000)
                    else -> Color.Unspecified
                }
            )
            if (field.sub != null) {
                Text(
                    text = field.sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            val perBattery = perBatteryVolts(field)
            if (perBattery != null) {
                Text(
                    text = String.format(java.util.Locale.US, "%.2f V each", perBattery),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        if (editable) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit ${field.label}",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Pack is 4 x 12 V batteries in series; show pack voltage / 4 under the "Battery" field. */
private const val BATTERIES_IN_SERIES = 4

private fun perBatteryVolts(field: Field): Double? {
    if (field.id != "battery") return null
    val v = field.number
        ?: Regex("""-?\d+(\.\d+)?""").find(field.text)?.value?.toDoubleOrNull()
        ?: return null
    if (v.isNaN() || v <= 0.0) return null
    return v / BATTERIES_IN_SERIES
}

@Composable
fun HistoryCard(history: List<HistoryPoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Load % over time",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            LoadGraph(history)
        }
    }
}

@Composable
fun LoadGraph(points: List<HistoryPoint>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val w = size.width
            val h = size.height
            if (points.isEmpty()) return@Canvas
            val n = points.size
            val path = Path()
            for (i in 0 until n) {
                val x = if (n == 1) 0f else i.toFloat() / (n - 1).toFloat() * w
                val y = h - (points[i].pct / 100.0).toFloat().coerceIn(0f, 1f) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFF4FC3F7), style = Stroke(width = 4f, cap = StrokeCap.Round))
            // baseline
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 2f
            )
        }
        // Labels
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Text("100%", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("0%", style = MaterialTheme.typography.labelSmall)
        }
        Row(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
            Text("now", style = MaterialTheme.typography.labelSmall)
        }
    }
}
