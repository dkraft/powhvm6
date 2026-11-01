package com.dkraft.powhvm6

import org.json.JSONObject

data class Field(
    val id: String,
    val label: String,
    val group: String,
    val text: String,
    val number: Double?
)

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
    val history: List<HistoryPoint>
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
            history = hist
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
