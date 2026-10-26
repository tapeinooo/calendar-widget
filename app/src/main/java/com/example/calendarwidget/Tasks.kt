package com.example.calendarwidget

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Google タスク（Google カレンダーアプリで予定と並んで出るもの）。
 * 端末のカレンダーのデータには入っていないので、Tasks API から読む（読み取りだけ）。
 * 通信はバックグラウンド（定期処理・設定画面）だけで行い、結果を Settings に保存して描き直しに使う。
 */
object Tasks {
    const val SCOPE = "https://www.googleapis.com/auth/tasks.readonly"
    private const val API = "https://tasks.googleapis.com/tasks/v1"
    private val COLOR = 0xFF6C8DDA.toInt()

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** 保存してあるタスクのうち、from〜to（含む）に期限があるものを予定の形で返す。 */
    fun load(context: Context, from: LocalDate, to: LocalDate): List<Ev> {
        val settings = Settings(context)
        if (!settings.tasksEnabled) return emptyList()
        val out = ArrayList<Ev>()
        runCatching {
            val array = JSONArray(settings.tasksJson)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val due = LocalDate.parse(o.getString("due"))
                if (due < from || due > to) continue
                val begin = due.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                out += Ev(0, "✓ " + o.getString("title"), COLOR, true, begin, begin + 86_400_000L, due, due)
            }
        }
        return out
    }

    /**
     * タスクを取得して保存する（バックグラウンドで呼ぶ）。許可の画面が必要なとき（まだ許可していない・取り消された）は、
     * 保存してあるものは消さずに、設定画面に出すエラーだけ残す。
     */
    fun refresh(context: Context): Boolean {
        val settings = Settings(context)
        if (!settings.tasksEnabled) return true
        return try {
            val result: AuthorizationResult = com.google.android.gms.tasks.Tasks.await(
                Identity.getAuthorizationClient(context).authorize(authorizationRequest()), 30, TimeUnit.SECONDS,
            )
            val token = result.accessToken
            if (result.hasResolution() || token == null) {
                settings.tasksError = "許可が必要です。設定画面で「Google タスクと連携する」を押してください。"
                return false
            }
            settings.tasksJson = fetch(token).toString()
            settings.tasksError = ""
            true
        } catch (e: Exception) {
            settings.tasksError = "取得できませんでした：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** すべてのリストの、未完了で期限のあるタスク（今日の少し前から先 8 週間）。 */
    private fun fetch(token: String): JSONArray {
        val today = LocalDate.now()
        val min = today.minusDays(2).toString() + "T00:00:00.000Z"
        val max = today.plusDays(56).toString() + "T00:00:00.000Z"
        val out = JSONArray()
        val lists = getJson("$API/users/@me/lists?maxResults=100", token).optJSONArray("items") ?: return out
        for (i in 0 until lists.length()) {
            val id = lists.getJSONObject(i).getString("id")
            val url = "$API/lists/${URLEncoder.encode(id, "UTF-8")}/tasks?showCompleted=false&showHidden=false&maxResults=100" +
                "&dueMin=${URLEncoder.encode(min, "UTF-8")}&dueMax=${URLEncoder.encode(max, "UTF-8")}"
            val items = getJson(url, token).optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val t = items.getJSONObject(j)
                val due = t.optString("due").take(10)
                val title = t.optString("title")
                if (due.length != 10 || title.isBlank() || t.optString("status") == "completed") continue
                // 期限は日付だけ（時刻は 0 時 UTC で入っている）なので、日付の部分をそのまま使う
                out.put(JSONObject().put("title", title).put("due", due))
            }
        }
        return out
    }

    private fun getJson(url: String, token: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode !in 200..299) {
                error("HTTP ${c.responseCode} ${c.errorStream?.bufferedReader()?.readText()?.take(120) ?: ""}")
            }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
