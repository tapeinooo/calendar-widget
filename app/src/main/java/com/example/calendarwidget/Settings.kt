package com.example.calendarwidget

import android.content.Context

/** 設定（文字の大きさの％と、表示するカレンダー）。 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var textScale: Int
        get() = prefs.getInt("text_scale", 100)
        set(v) = prefs.edit().putInt("text_scale", v.coerceIn(60, 200)).apply()

    /** 左端の日：0＝今日、1＝月曜、2＝日曜。 */
    var startDay: Int
        get() = prefs.getInt("start_day", 0)
        set(v) = prefs.edit().putInt("start_day", v.coerceIn(0, 2)).apply()

    /** 何週間出すか（高さが足りなければ入る分だけ。縦1マスなら1週）。 */
    var weeks: Int
        get() = prefs.getInt("weeks", 1)
        set(v) = prefs.edit().putInt("weeks", v.coerceIn(1, 4)).apply()

    /** 予定の段数（0 なら高さから自動で決める）。 */
    var laneCount: Int
        get() = prefs.getInt("lane_count", 0)
        set(v) = prefs.edit().putInt("lane_count", v.coerceIn(0, 6)).apply()

    /**
     * タスクを読むアカウントの一覧（メールアドレス）。空文字は、アカウントを選ばずに連携した古い版のもの。
     * アカウントごとに、取得したタスク（JSON）・件数の説明・エラーを分けて持つ。
     * 描き直しは保存した結果だけを使い、通信はしない。
     */
    var taskAccounts: List<String>
        get() {
            val set = prefs.getStringSet("task_accounts", emptySet())!!.toMutableSet()
            if (prefs.getBoolean("tasks_enabled", false)) set += ""
            return set.sorted()
        }
        set(v) = prefs.edit().putStringSet("task_accounts", v.filter { it.isNotEmpty() }.toSet())
            .putBoolean("tasks_enabled", "" in v).apply()

    private fun key(base: String, account: String) = if (account.isEmpty()) base else "${base}_$account"

    fun tasksJson(account: String): String = prefs.getString(key("tasks_json", account), "[]")!!
    fun setTasksJson(account: String, v: String) = prefs.edit().putString(key("tasks_json", account), v).apply()

    /** 最後のタスク取得で見つかった件数の説明（設定画面に出す）。 */
    fun tasksSummary(account: String): String = prefs.getString(key("tasks_summary", account), "")!!
    fun setTasksSummary(account: String, v: String) = prefs.edit().putString(key("tasks_summary", account), v).apply()

    /** 最後のタスク取得のエラー（空なら成功）。 */
    fun tasksError(account: String): String = prefs.getString(key("tasks_error", account), "")!!
    fun setTasksError(account: String, v: String) = prefs.edit().putString(key("tasks_error", account), v).apply()

    fun removeTaskAccount(account: String) {
        taskAccounts = taskAccounts - account
        prefs.edit().remove(key("tasks_json", account)).remove(key("tasks_summary", account)).remove(key("tasks_error", account)).apply()
    }

    /** 表示しないカレンダーの id（新しく増えたカレンダーは表示するため、除く側を覚える）。 */
    var hiddenCalendars: Set<Long>
        get() = prefs.getStringSet("hidden_calendars", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("hidden_calendars", v.map { it.toString() }.toSet()).apply()
}
