package com.dkraft.powhvm6

import android.content.Context
import android.content.SharedPreferences

class Settings(private val ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("powhvm6", Context.MODE_PRIVATE)

    var host: String
        get() = HostAddr.normalize(prefs.getString("host", "") ?: "")
        set(v) = prefs.edit().putString("host", HostAddr.normalize(v)).apply()

    var nightMode: Boolean
        get() = prefs.getBoolean("night", false)
        set(v) = prefs.edit().putBoolean("night", v).apply()

    /** Returns the set of hidden field ids. Empty means show all. */
    fun hiddenFields(): Set<String> {
        val s = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        return s
    }

    fun setFieldVisible(id: String, visible: Boolean) {
        val hidden = mutableSetOf<String>()
        hidden.addAll(hiddenFields())
        if (visible) hidden.remove(id) else hidden.add(id)
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    fun isFieldVisible(id: String): Boolean = id !in hiddenFields()
}
