package com.dkraft.powhvm6

import org.json.JSONObject

/** One reading a tracker can watch, as served by GET /api/v1/events. */
data class EventMetric(val id: String, val label: String, val unit: String)

/** A finished event from the Pi's SQLite history. */
data class PowerEvent(
    val id: Long,
    val startMs: Long?,
    val endMs: Long?,
    val durationS: Double,
    val peak: Double?,
    val avg: Double?,
    val kwh: Double?,
    val truncated: Boolean
)

/** A generic event tracker (rule + totals + live state). */
data class Tracker(
    val id: Long,
    val name: String,
    val metric: String,
    val metricLabel: String,
    val unit: String,
    val startOp: String,
    val startValue: Double,
    val minS: Double,
    val endOp: String,
    val endValue: Double,
    val endHoldS: Double,
    val maxS: Double?,
    val enabled: Boolean,
    val summary: String,
    val count: Long,
    val kwh: Double,
    val totalS: Double,
    val sinceMs: Long?,
    /** Start condition seen, not yet min_s long (won't count if it stops now). */
    val candidate: Boolean,
    /** Long enough to count; still going. */
    val running: Boolean,
    /** End condition is true, waiting out the hold time. */
    val ending: Boolean,
    val elapsedS: Double?,
    val livePeak: Double?,
    val liveKwh: Double?,
    val liveValue: Double?,
    val lastEvent: PowerEvent?
)

data class EventsList(
    val metrics: List<EventMetric>,
    val trackers: List<Tracker>,
    val defaults: TrackerDraft,
    val keepDays: Int
)

data class EventHistory(val tracker: Tracker, val total: Long, val events: List<PowerEvent>)

/** Editable rule fields, as text so the form can hold partial input. */
data class TrackerDraft(
    val name: String = "New tracker",
    val metric: String = "load_power",
    val startOp: String = ">",
    val startValue: String = "800",
    val minS: String = "30",
    val endOp: String = "<",
    val endValue: String = "600",
    val endHoldS: String = "10",
    /** Minutes; blank = no limit. */
    val maxMin: String = "60"
) {
    fun toJson(): JSONObject {
        fun num(s: String, what: String): Double =
            s.trim().replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("$what must be a number")
        val o = JSONObject()
            .put("name", name.trim())
            .put("metric", metric)
            .put("start_op", startOp)
            .put("start_value", num(startValue, "Start value"))
            .put("min_s", num(minS, "Minimum time"))
            .put("end_op", endOp)
            .put("end_value", num(endValue, "End value"))
            .put("end_hold_s", num(endHoldS, "End hold time"))
        if (maxMin.isBlank()) o.put("max_s", JSONObject.NULL)
        else o.put("max_s", num(maxMin, "Max duration") * 60.0)
        return o
    }

    companion object {
        private fun s(v: Double): String = fmtNum(v, "")
        fun from(t: Tracker) = TrackerDraft(
            name = t.name, metric = t.metric, startOp = t.startOp, startValue = s(t.startValue),
            minS = s(t.minS), endOp = t.endOp, endValue = s(t.endValue), endHoldS = s(t.endHoldS),
            maxMin = t.maxS?.let { s(Math.round(it / 6.0) / 10.0) } ?: ""
        )
    }
}

class EventsUnsupported(msg: String) : Exception(msg)

object EventsApi {
    private fun optD(o: JSONObject, k: String): Double? =
        if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    private fun isoMs(o: JSONObject, k: String): Long? =
        if (!o.has(k) || o.isNull(k)) null
        else runCatching { java.time.OffsetDateTime.parse(o.getString(k)).toInstant().toEpochMilli() }.getOrNull()

    private fun parseEvent(o: JSONObject) = PowerEvent(
        id = o.optLong("id"),
        startMs = isoMs(o, "start"),
        endMs = isoMs(o, "end"),
        durationS = o.optDouble("duration_s", 0.0),
        peak = optD(o, "peak"),
        avg = optD(o, "avg"),
        kwh = optD(o, "kwh"),
        truncated = o.optBoolean("truncated", false)
    )

    fun parseTracker(o: JSONObject) = Tracker(
        id = o.getLong("id"),
        name = o.optString("name"),
        metric = o.optString("metric"),
        metricLabel = o.optString("metric_label", o.optString("metric")),
        unit = o.optString("unit"),
        startOp = o.optString("start_op", ">"),
        startValue = o.optDouble("start_value", 0.0),
        minS = o.optDouble("min_s", 0.0),
        endOp = o.optString("end_op", "<"),
        endValue = o.optDouble("end_value", 0.0),
        endHoldS = o.optDouble("end_hold_s", 0.0),
        maxS = optD(o, "max_s"),
        enabled = o.optBoolean("enabled", true),
        summary = o.optString("summary"),
        count = o.optLong("count"),
        kwh = o.optDouble("kwh", 0.0),
        totalS = o.optDouble("total_s", 0.0),
        sinceMs = isoMs(o, "since"),
        candidate = o.optBoolean("candidate"),
        running = o.optBoolean("running"),
        ending = o.optBoolean("ending"),
        elapsedS = optD(o, "elapsed_s"),
        livePeak = optD(o, "live_peak"),
        liveKwh = optD(o, "live_kwh"),
        liveValue = optD(o, "live_value"),
        lastEvent = o.optJSONObject("last_event")?.let { parseEvent(it) }
    )

    private fun request(host: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = java.net.URL("http://$host$path").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.requestMethod = method
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
            .bufferedReader().use { it.readText() }
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (code == 404 && path == "/api/v1/events")
            throw EventsUnsupported("This Pi script doesn't track events yet. Update hdmi-inverter.py on the Pi.")
        if (code !in 200..299) throw Exception(o?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP $code")
        return o ?: throw Exception("Bad reply from the Pi")
    }

    fun list(host: String): EventsList {
        val o = request(host, "GET", "/api/v1/events")
        val metrics = mutableListOf<EventMetric>()
        o.optJSONArray("metrics")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                metrics += EventMetric(it.getString("id"), it.optString("label"), it.optString("unit"))
            }
        }
        val trackers = mutableListOf<Tracker>()
        o.optJSONArray("trackers")?.let { a -> for (i in 0 until a.length()) trackers += parseTracker(a.getJSONObject(i)) }
        val d = o.optJSONObject("defaults")
        val defaults = if (d == null) TrackerDraft() else TrackerDraft(
            name = d.optString("name", "New tracker"),
            metric = d.optString("metric", "load_power"),
            startOp = d.optString("start_op", ">"),
            startValue = fmtNum(d.optDouble("start_value", 800.0), ""),
            minS = fmtNum(d.optDouble("min_s", 30.0), ""),
            endOp = d.optString("end_op", "<"),
            endValue = fmtNum(d.optDouble("end_value", 600.0), ""),
            endHoldS = fmtNum(d.optDouble("end_hold_s", 10.0), ""),
            maxMin = optD(d, "max_s")?.let { fmtNum(Math.round(it / 6.0) / 10.0, "") } ?: ""
        )
        return EventsList(metrics, trackers, defaults, o.optInt("keep_days", 90))
    }

    fun history(host: String, id: Long, limit: Int = 100): EventHistory {
        val o = request(host, "GET", "/api/v1/events/$id/history?limit=$limit")
        val evs = mutableListOf<PowerEvent>()
        o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) evs += parseEvent(a.getJSONObject(i)) }
        return EventHistory(parseTracker(o.getJSONObject("tracker")), o.optLong("total_events"), evs)
    }

    fun create(host: String, draft: TrackerDraft): Tracker =
        parseTracker(request(host, "POST", "/api/v1/events", draft.toJson()))

    fun update(host: String, id: Long, draft: TrackerDraft): Tracker =
        parseTracker(request(host, "POST", "/api/v1/events/$id/update", draft.toJson()))

    /** pause | resume | reset | delete */
    fun action(host: String, id: Long, action: String) {
        request(host, "POST", "/api/v1/events/$id/$action")
    }
}

/** "4:05", "1:02:09" */
fun fmtDuration(seconds: Double): String {
    val s = Math.max(0L, Math.round(seconds))
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(java.util.Locale.US, "%d:%02d", m, sec)
}

fun fmtKwh(kwh: Double?): String =
    if (kwh == null) "—" else if (Math.abs(kwh) < 10) String.format(java.util.Locale.US, "%.3f kWh", kwh)
    else String.format(java.util.Locale.US, "%.1f kWh", kwh)

fun fmtValue(v: Double?, unit: String): String {
    if (v == null) return "—"
    val n = if (Math.abs(v) >= 100 || v == Math.floor(v)) Math.round(v).toString()
    else String.format(java.util.Locale.US, "%.1f", v)
    return if (unit.isEmpty()) n else "$n $unit"
}

private val DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US)
private val DATE_ONLY = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)

fun fmtWhen(ms: Long?): String = if (ms == null) "—" else
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(DATE_TIME)

fun fmtDate(ms: Long?): String = if (ms == null) "—" else
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(DATE_ONLY)

