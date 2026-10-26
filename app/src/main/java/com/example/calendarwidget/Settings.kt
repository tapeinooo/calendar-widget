package com.example.calendarwidget

import android.content.Context

/** 設定（文字の大きさの％と、表示するカレンダー）。 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var textScale: Int
        get() = prefs.getInt("text_scale", 100)
        set(v) = prefs.edit().putInt("text_scale", v.coerceIn(60, 200)).apply()

    /** 表示しないカレンダーの id（新しく増えたカレンダーは表示するため、除く側を覚える）。 */
    var hiddenCalendars: Set<Long>
        get() = prefs.getStringSet("hidden_calendars", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("hidden_calendars", v.map { it.toString() }.toSet()).apply()
}
