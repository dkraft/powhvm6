package com.dkraft.powhvm6

import org.json.JSONObject

data class Field(
    val id: String,
    val label: String,
    val group: String,
    val text: String,
    val number: Double?,
    /** 0 neutral, 1 good (green), 2 alert (red), 3 warning (amber). Only set by the app (AC Feed tile). */
    val tone: Int = 0,
    /** Optional smaller line under the value. */
    val sub: String? = null
)

/** Grid feed state from /api/v1/now (Pi script v1.7+). Times are epoch millis. */
data class FeedInfo(
    val ok: Boolean?,
    val outageStartMs: Long?,
    val outageEndMs: Long?,
    val outageSeconds: Long?,
    val endApprox: Boolean,
    val lastBlipMs: Long? = null,
    val lastBlipMinV: Double? = null,
    val blips24h: Int = 0
)

/** Pi -> inverter Modbus frame stats from /api/v1/now (Pi script v1.8+). */
data class PiLink(
    val msLast: Double?,
    val msAvg: Double?,
    val framesOk: Long,
    val framesDropped: Long,
    val frames1h: Long,
    val dropped1h: Long,
    val successPct: Double?
)

/** App -> Pi /api/v1/now fetch stats, kept by the view model. */
data class AppLink(
    val msLast: Double? = null,
    val recent: List<Double> = emptyList(),
    val ok: Long = 0,
    val failed: Long = 0
) {
    val msAvg: Double? get() = if (recent.isEmpty()) null else recent.average()
    fun good(ms: Double) = copy(msLast = ms, recent = (recent + ms).takeLast(30), ok = ok + 1)
    fun bad() = copy(failed = failed + 1)
}

data class HistoryPoint(
    val ageS: Double,
    val pct: Double,
    val state: String
)

data class Snapshot(
    val name: String,
    val model: String,
    val linkOk: Boolean,
    val upSince: Double,
    val fields: List<Field>,
    val history: List<HistoryPoint>,
    /** Null when the Pi script predates the feed fields. */
    val feed: FeedInfo? = null,
    /** Null when the Pi script predates the link stats. */
    val piLink: PiLink? = null
)

object Api {
    fun parseNow(json: String): Snapshot {
        val o = JSONObject(json)
        val fields = mutableListOf<Field>()
        val arr = o.getJSONArray("fields")
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            fields += Field(
                id = f.getString("id"),
                label = f.getString("label"),
                group = f.getString("group"),
                text = f.getString("text"),
                number = if (f.isNull("number")) null else f.getDouble("number")
            )
        }
        val hist = mutableListOf<HistoryPoint>()
        val h = o.getJSONArray("history")
        for (i in 0 until h.length()) {
            val p = h.getJSONObject(i)
            hist += HistoryPoint(
                ageS = p.getDouble("age_s"),
                pct = p.getDouble("pct"),
                state = p.getString("state")
            )
        }
        return Snapshot(
            name = o.optString("name"),
            model = o.optString("model"),
            linkOk = o.optBoolean("link_ok"),
            upSince = o.optDouble("up_since"),
            fields = fields,
            history = hist,
            feed = parseFeed(o),
            piLink = parsePiLink(o)
        )
    }

    private fun isoMs(o: JSONObject, k: String): Long? =
        if (!o.has(k) || o.isNull(k)) null
        else runCatching { java.time.OffsetDateTime.parse(o.getString(k)).toInstant().toEpochMilli() }.getOrNull()

    private fun parseFeed(o: JSONObject): FeedInfo? {
        if (!o.has("feed_ok")) return null
        return FeedInfo(
            ok = if (o.isNull("feed_ok")) null else o.getBoolean("feed_ok"),
            outageStartMs = isoMs(o, "last_outage_start"),
            outageEndMs = isoMs(o, "last_outage_end"),
            outageSeconds = if (o.isNull("last_outage_seconds") || !o.has("last_outage_seconds")) null else o.getLong("last_outage_seconds"),
            endApprox = o.optBoolean("last_outage_end_approx", false),
            lastBlipMs = isoMs(o, "last_blip_time"),
            lastBlipMinV = if (!o.has("last_blip_min_voltage") || o.isNull("last_blip_min_voltage")) null else o.getDouble("last_blip_min_voltage"),
            blips24h = o.optInt("blips_24h", 0)
        )
    }

    private fun optD(o: JSONObject, k: String): Double? =
        if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    private fun parsePiLink(o: JSONObject): PiLink? {
        if (!o.has("frames_ok")) return null
        return PiLink(
            msLast = optD(o, "link_ms_last"),
            msAvg = optD(o, "link_ms_avg"),
            framesOk = o.optLong("frames_ok"),
            framesDropped = o.optLong("frames_dropped"),
            frames1h = o.optLong("frames_1h"),
            dropped1h = o.optLong("frames_dropped_1h"),
            successPct = optD(o, "link_success_pct")
        )
    }

    fun fetchNow(host: String): Snapshot {
        val url = "http://$host/api/v1/now"
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return parseNow(body)
    }

    fun fetchIdentity(host: String): JSONObject {
        val url = "http://$host/api/v1/identity"
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2500
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }
}

private val CELSIUS_TEXT = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*°?\s*C\s*$""")

/** Field text for display: temperatures the Pi reports in °C ("35 C") are shown in the chosen unit. */
fun Field.displayText(unit: TempUnit): String {
    val m = CELSIUS_TEXT.matchEntire(text) ?: return text
    val c = m.groupValues[1].toDouble()
    return when (unit) {
        TempUnit.CELSIUS -> "${fmtTemp(c)} °C"
        TempUnit.FAHRENHEIT -> "${fmtTemp(c * 9.0 / 5.0 + 32.0)} °F"
    }
}

private fun fmtTemp(v: Double): String = kotlin.math.round(v).toLong().toString()

/** One writable inverter setting, as described by the Pi's GET /api/v1/settings. */
data class SettingOption(val value: Double, val label: String)

data class SettingDef(
    val key: String,
    val label: String,
    val description: String,
    val unit: String,
    val fieldId: String?,
    val type: String,
    val options: List<SettingOption>,
    val current: Double?,
    val enabled: Boolean,
    val note: String
) {
    fun labelFor(v: Double?): String {
        if (v == null) return "—"
        return options.firstOrNull { kotlin.math.abs(it.value - v) < 1e-6 }?.label ?: fmtNum(v, unit)
    }
}

data class SetResult(
    val ok: Boolean,
    val message: String,
    val old: Double?,
    val readback: Double?
)

class SettingsUnsupported(msg: String) : Exception(msg)

fun fmtNum(v: Double, unit: String): String {
    val n = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    return if (unit.isEmpty()) n else "$n $unit"
}

object SettingsApi {
    fun fetch(host: String): List<SettingDef> {
        val conn = java.net.URL("http://$host/api/v1/settings").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 5000
        val code = conn.responseCode
        if (code == 404) throw SettingsUnsupported("This Pi script doesn't support settings yet. Update hdmi-inverter.py on the Pi.")
        if (code != 200) throw Exception("HTTP $code")
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val arr = o.getJSONArray("settings")
        val out = mutableListOf<SettingDef>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val opts = mutableListOf<SettingOption>()
            val oa = s.optJSONArray("options")
            if (oa != null) for (j in 0 until oa.length()) {
                val op = oa.getJSONObject(j)
                opts += SettingOption(op.getDouble("value"), op.optString("label"))
            }
            out += SettingDef(
                key = s.getString("key"),
                label = s.optString("label", s.getString("key")),
                description = s.optString("description"),
                unit = s.optString("unit"),
                fieldId = if (s.isNull("field_id")) null else s.optString("field_id"),
                type = s.optString("type"),
                options = opts,
                current = if (s.isNull("current")) null else s.optDouble("current"),
                enabled = s.optBoolean("enabled", false) && o.optBoolean("writes_enabled", false),
                note = s.optString("note")
            )
        }
        return out
    }

    fun set(host: String, key: String, value: Double): SetResult {
        val conn = java.net.URL("http://$host/api/v1/set").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 20000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("key", key)
        if (value == Math.floor(value)) body.put("value", value.toLong()) else body.put("value", value)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code == 404 || code == 501) {
            // 501 = old script without POST support
            val txt = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 501 || !txt.contains("unknown setting"))
                throw SettingsUnsupported("This Pi script doesn't support writes yet. Update hdmi-inverter.py on the Pi.")
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val o = try { JSONObject(txt) } catch (e: Exception) { JSONObject().put("message", "HTTP $code") }
        return SetResult(
            ok = o.optBoolean("ok", false) && code in 200..299,
            message = o.optString("message", "HTTP $code"),
            old = if (o.isNull("old") || !o.has("old")) null else o.optDouble("old"),
            readback = if (o.isNull("readback") || !o.has("readback")) null else o.optDouble("readback")
        )
    }
}


/** How long a past outage keeps the AC Feed tile red. */
const val FEED_ALERT_MS = 24L * 3600 * 1000

private fun fmtWhen(ms: Long, nowMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val time = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
    return if (t.toLocalDate() == today) t.format(time)
    else t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US))
}

fun fmtDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> { val h = seconds / 3600; val m = (seconds % 3600) / 60; if (m == 0L) "$h h" else "$h h $m min" }
}

/** The dashboard "AC Feed" tile (Status group), or null if the Pi doesn't report feed state. */
fun feedField(feed: FeedInfo?, nowMs: Long = System.currentTimeMillis()): Field? {
    feed ?: return null
    val start = feed.outageStartMs
    val end = feed.outageEndMs
    val blip = feed.lastBlipMs
    return when {
        feed.ok == false -> Field("feed", "AC Feed", "status",
            if (start != null) "Down since ${fmtWhen(start, nowMs)}" else "Down",
            null, tone = 2,
            sub = if (start != null) "for ${fmtDuration((nowMs - start) / 1000)}" else null)
        feed.ok == null -> Field("feed", "AC Feed", "status", "—", null,
            sub = if (start != null) "Last out ${fmtWhen(start, nowMs)}" else null)
        start != null && end != null && nowMs - end < FEED_ALERT_MS -> Field("feed", "AC Feed", "status",
            "Last out ${fmtWhen(start, nowMs)} · ${if (feed.endApprox) "~" else ""}${fmtDuration(feed.outageSeconds ?: ((end - start) / 1000))}",
            null, tone = 2)
        blip != null && nowMs - blip < FEED_ALERT_MS -> Field("feed", "AC Feed", "status",
            "Blip ${fmtWhen(blip, nowMs)}" + (if (feed.blips24h > 1) " · ${feed.blips24h} in 24 h" else ""),
            null, tone = 3,
            sub = feed.lastBlipMinV?.let { String.format(java.util.Locale.US, "min %.0f V", it) })
        else -> Field("feed", "AC Feed", "status", "OK", null, tone = 1,
            sub = when {
                start != null -> "Last out ${fmtWhen(start, nowMs)}"
                blip != null -> "Last blip ${fmtWhen(blip, nowMs)}"
                else -> null
            })
    }
}

private fun ms(v: Double?): String = if (v == null) "—" else String.format(java.util.Locale.US, "%.0f ms", v)

/** Compact link diagnostics for the bottom "Link" card. Pi tile only if the script reports it. */
fun linkFields(app: AppLink?, pi: PiLink?): List<Field> {
    val out = mutableListOf<Field>()
    if (app != null) {
        val tot = app.ok + app.failed
        out += Field("diag_app", "App → Pi", "link",
            "${ms(app.msLast)} · avg ${ms(app.msAvg)}", null,
            sub = "dropped ${app.failed}" + if (tot > 0) String.format(java.util.Locale.US, " · %.1f%% ok", 100.0 * app.ok / tot) else "")
    }
    if (pi != null) {
        out += Field("diag_pi", "Pi → inverter", "link",
            "${ms(pi.msLast)} · avg ${ms(pi.msAvg)}", null,
            sub = "dropped ${pi.framesDropped} · 1 h ${pi.dropped1h}" +
                (pi.successPct?.let { String.format(java.util.Locale.US, " · %.1f%% ok", it) } ?: ""))
    }
    return out
}
