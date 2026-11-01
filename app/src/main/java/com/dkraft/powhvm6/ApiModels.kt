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
