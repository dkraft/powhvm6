package com.dkraft.powhvm6

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

class Settings(private val ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("powhvm6", Context.MODE_PRIVATE)

    var host: String
        get() = HostAddr.normalize(prefs.getString("host", "") ?: "")
        set(v) = prefs.edit().putString("host", HostAddr.normalize(v)).apply()

    /** System / Dark / Light. Migrates the pre-1.3 boolean "night" pref (true -> Dark, else System). */
    var themeMode: ThemeMode
        get() {
            prefs.getString("theme", null)?.let { s ->
                return ThemeMode.values().firstOrNull { it.name == s } ?: ThemeMode.SYSTEM
            }
            val migrated = if (prefs.getBoolean("night", false)) ThemeMode.DARK else ThemeMode.SYSTEM
            prefs.edit().putString("theme", migrated.name).remove("night").apply()
            return migrated
        }
        set(v) = prefs.edit().putString("theme", v.name).apply()

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
