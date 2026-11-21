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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
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
    // AC Feed tile goes first in Status. The Pi script may predate it (feed == null): no tile.
    feedField(snap.feed)?.let { feed ->
        if (vm.isFieldVisible(feed.id)) groups.getOrPut(feed.group) { mutableListOf() }.add(0, feed)
    }

    val editable = vm.editableFieldIds()

    val link = linkFields(vm.appLink, snap.piLink).filter { vm.isFieldVisible(it.id) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
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

        for ((groupName, fields) in orderGroups(groups)) {
            item {
                if (groupName == "live") LiveCard(groupName, fields, editable, onEdit = { vm.beginEdit(fieldId = it) })
                else GroupCard(groupName, fields, editable, onEdit = { vm.beginEdit(fieldId = it) })
            }
        }

        // Link (route, latency, dropped frames) last.
        if (link.isNotEmpty()) {
            item { GroupCard("Link", link) }
        }
    }
}

/** Pi order (live, battery, solar, status, setpoints) with Status moved up above Battery. */
fun orderGroups(groups: Map<String, List<Field>>): List<Pair<String, List<Field>>> {
    val names = groups.keys.toMutableList()
    if ("status" in names && "battery" in names) {
        names.remove("status")
        names.add(names.indexOf("battery"), "status")
    }
    return names.map { it to groups.getValue(it) }
}

private val LOAD_GREEN = Color(0xFF4CAF50)
private val LOAD_YELLOW = Color(0xFFFFEB3B)
private val LOAD_RED = Color(0xFFE53935)

/**
 * Load colour by load percent: solid green to 33.333 %, green→yellow across 33.333–66.667 %,
 * yellow→red across 66.667–100 %, solid red at 100 % and above (linear RGB blend).
 */
fun loadColor(pct: Double?): Color {
    if (pct == null || pct.isNaN()) return Color.Unspecified
    val third = 100.0 / 3.0
    return when {
        pct <= third -> LOAD_GREEN
        pct <= 2 * third -> androidx.compose.ui.graphics.lerp(LOAD_GREEN, LOAD_YELLOW, ((pct - third) / third).toFloat())
        pct < 100.0 -> androidx.compose.ui.graphics.lerp(LOAD_YELLOW, LOAD_RED, ((pct - 2 * third) / third).toFloat())
        else -> LOAD_RED
    }
}

private fun fieldNumber(f: Field?): Double? =
    f?.number ?: f?.text?.let { Regex("""-?\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

/** Live card: big colour-coded Load power at the top left, Load percent beside it, then the rest. */
@Composable
fun LiveCard(
    groupName: String,
    fields: List<Field>,
    editable: Set<String> = emptySet(),
    onEdit: (String) -> Unit = {}
) {
    val power = fields.firstOrNull { it.id == "load_power" }
    val pctField = fields.firstOrNull { it.id == "load_percent" }
    if (power == null) { GroupCard(groupName, fields, editable, onEdit); return }
    val rest = fields.filter { it.id != "load_power" && it.id != "load_percent" }
    val target = loadColor(fieldNumber(pctField))
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = if (target == Color.Unspecified) MaterialTheme.colorScheme.onSurface else target,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 900),
        label = "loadColor"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = groupName.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (power.id in editable) Modifier.clickable { onEdit(power.id) } else Modifier)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = power.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = power.text,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1
                    )
                }
                if (pctField != null) {
                    FieldCell(pctField, modifier = Modifier.weight(1f), editable = pctField.id in editable, onEdit = onEdit)
                } else Spacer(Modifier.weight(1f))
            }
            if (rest.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FieldGrid(rest, editable, onEdit)
            }
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
            FieldGrid(fields, editable, onEdit)
        }
    }
}

@Composable
private fun FieldGrid(fields: List<Field>, editable: Set<String>, onEdit: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
fun HistoryCard(history: List<HistoryPoint>, graphHeight: androidx.compose.ui.unit.Dp = 220.dp) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Load % and battery state",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            LoadGraph(history, graphHeight)
            Spacer(Modifier.height(8.dp))
            StateLegend()
        }
    }
}

@Composable
fun StateLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendSwatch(STATE_CHARGE, "Charge")
        LegendSwatch(STATE_IDLE, "Idle")
        LegendSwatch(STATE_DISCHARGE, "Discharge")
        Spacer(Modifier.weight(1f))
        Text("Load %", color = Color(0xFF4FC3F7), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

val STATE_CHARGE = Color(0xFF4CAF50)
val STATE_IDLE = Color(0xFF26C6DA)
val STATE_DISCHARGE = Color(0xFFE53935)

fun stateLevel(state: String): Int = when (state) {
    "charging", "charge" -> 2
    "discharging", "discharge" -> 0
    else -> 1
}

fun stateColor(state: String): Color = when (stateLevel(state)) {
    2 -> STATE_CHARGE
    0 -> STATE_DISCHARGE
    else -> STATE_IDLE
}


/** Y axis fitted to the visible data: padded, snapped to "nice" tick steps. */
data class AxisScale(val min: Double, val max: Double, val step: Double) {
    val ticks: List<Double>
        get() {
            val out = mutableListOf<Double>()
            var v = min
            var guard = 0
            while (v <= max + step * 1e-6 && guard++ < 20) { out += v; v += step }
            return out
        }
}

private fun niceStep(rough: Double): Double {
    if (rough <= 0.0 || rough.isNaN() || rough.isInfinite()) return 1.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(rough)))
    val norm = rough / mag
    val nice = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

/**
 * Fits [values] with ~8% padding and about [targetTicks] rounded ticks.
 * Flat data (min == max) gets a small window around the value instead of dividing by zero.
 * [floor]/[ceil] clamp the axis to the quantity's physical range (e.g. 0–100 %).
 */
fun autoScale(values: List<Double>, targetTicks: Int = 4, floor: Double? = null, ceil: Double? = null): AxisScale {
    val finite = values.filter { !it.isNaN() && !it.isInfinite() }
    if (finite.isEmpty()) return AxisScale(floor ?: 0.0, ceil ?: 1.0, ((ceil ?: 1.0) - (floor ?: 0.0)) / targetTicks)
    var lo = finite.min()
    var hi = finite.max()
    var range = hi - lo
    if (range < 1e-9) {
        range = maxOf(Math.abs(hi) * 0.1, 1.0)
        lo -= range / 2; hi += range / 2
    } else {
        lo -= range * 0.08; hi += range * 0.08
    }
    if (floor != null) lo = maxOf(lo, floor)
    if (ceil != null) hi = minOf(hi, ceil)
    val step = niceStep((hi - lo) / targetTicks)
    var min = Math.floor(lo / step) * step
    var max = Math.ceil(hi / step) * step
    if (floor != null) min = maxOf(min, floor)
    if (ceil != null) max = minOf(max, ceil)
    if (max - min < step) max = min + step
    return AxisScale(min, max, step)
}

private fun tickLabel(v: Double, step: Double): String {
    val decimals = when {
        step >= 1.0 && Math.abs(step - Math.rint(step)) < 1e-9 -> 0
        Math.abs(step * 10 - Math.rint(step * 10)) < 1e-9 -> 1
        else -> 2
    }
    return String.format(java.util.Locale.US, "%.${decimals}f%%", v)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun LoadGraph(points: List<HistoryPoint>, graphHeight: androidx.compose.ui.unit.Dp = 220.dp) {
    // Recomputed whenever a new history list arrives (each poll).
    val scale = androidx.compose.runtime.remember(points) { autoScale(points.map { it.pct }, floor = 0.0, ceil = 100.0) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    val trackStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    val trackH = 36.dp
    Column(modifier = Modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(graphHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val labels = scale.ticks.map { it to measurer.measure(tickLabel(it, scale.step), labelStyle) }
            val gutter = (labels.maxOfOrNull { it.second.size.width } ?: 0) + 6f
            val left = gutter
            val w = size.width - left
            val h = size.height
            val span = (scale.max - scale.min).takeIf { it > 0 } ?: 1.0
            fun yOf(v: Double): Float = h - ((v - scale.min) / span).toFloat().coerceIn(0f, 1f) * h
            for ((v, layout) in labels) {
                val y = yOf(v)
                drawLine(Color.Gray.copy(alpha = 0.25f), Offset(left, y), Offset(size.width, y), strokeWidth = 1.5f)
                val ty = (y - layout.size.height / 2f).coerceIn(0f, h - layout.size.height)
                drawText(layout, topLeft = Offset(0f, ty))
            }
            if (points.isEmpty()) return@Canvas
            val n = points.size
            val path = Path()
            for (i in 0 until n) {
                val x = left + if (n == 1) 0f else i.toFloat() / (n - 1).toFloat() * w
                val y = yOf(points[i].pct)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFF4FC3F7), style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        Row(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
            Text("now", style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackH)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            val labels = listOf("Dis", "Idle", "Chg").map { measurer.measure(it, trackStyle) }
            val gutter = (labels.maxOfOrNull { it.size.width } ?: 0) + 6f
            val left = gutter
            val w = size.width - left
            val h = size.height
            fun yLevel(level: Int): Float = h - (level / 2f) * h
            for (i in 0..2) {
                val y = yLevel(i)
                drawLine(Color.Gray.copy(alpha = 0.2f), Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                val layout = labels[i]
                val ty = (y - layout.size.height / 2f).coerceIn(0f, h - layout.size.height)
                drawText(layout, topLeft = Offset(0f, ty))
            }
            if (points.isEmpty()) return@Canvas
            val n = points.size
            val path = Path()
            for (i in 0 until n) {
                val x = left + if (n == 1) 0f else i.toFloat() / (n - 1).toFloat() * w
                val y = yLevel(stateLevel(points[i].state))
                if (i == 0) path.moveTo(x, y) else {
                    // Stepped line so state changes are sharp.
                    val prevY = yLevel(stateLevel(points[i - 1].state))
                    if (prevY != y) path.lineTo(x, prevY)
                    path.lineTo(x, y)
                }
            }
            // Colour the stepped path in segments so charge/idle/discharge are distinct.
            for (i in 0 until n) {
                val x0 = left + if (n == 1) 0f else i.toFloat() / (n - 1).toFloat() * w
                val x1 = left + if (n == 1 || i == n - 1) x0 else (i + 1).toFloat() / (n - 1).toFloat() * w
                val y = yLevel(stateLevel(points[i].state))
                drawLine(stateColor(points[i].state), Offset(x0, y), Offset(x1, y), strokeWidth = 5f, cap = StrokeCap.Round)
                if (i < n - 1) {
                    val y2 = yLevel(stateLevel(points[i + 1].state))
                    if (y2 != y) drawLine(stateColor(points[i + 1].state), Offset(x1, y), Offset(x1, y2), strokeWidth = 5f, cap = StrokeCap.Round)
                }
            }
            // Keep a faint outline of the whole path for continuity.
            drawPath(path, color = Color.Transparent)
        }
    }
    }
}
