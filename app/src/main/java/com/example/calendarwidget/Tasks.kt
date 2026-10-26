package com.example.calendarwidget

import android.accounts.Account
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

    /** account が空なら、Google が選んだアカウントで（古い版の連携）。 */
    fun authorizationRequest(account: String): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).also {
            if (account.isNotEmpty()) it.setAccount(Account(account, "com.google"))
        }.build()

    /** 保存してあるタスクのうち、from〜to（含む）に出すものを予定の形で返す（全アカウントぶん）。 */
    fun load(context: Context, from: LocalDate, to: LocalDate): List<Ev> {
        val settings = Settings(context)
        val out = ArrayList<Ev>()
        for (account in settings.taskAccounts) runCatching {
            val array = JSONArray(settings.tasksJson(account))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val due = LocalDate.parse(o.getString("due"))
                // 期限を過ぎた未完了のタスクは、Google カレンダーアプリと同じく今日の列に出す
                val shownOn = if (due < from) from else due
                if (shownOn > to) continue
                val begin = shownOn.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                out += Ev(0, "✓ " + o.getString("title"), COLOR, true, begin, begin + 86_400_000L, shownOn, shownOn)
            }
        }
        return out
    }

    /**
     * 連携しているすべてのアカウントのタスクを取得して保存する（バックグラウンドで呼ぶ）。
     * 許可の画面が必要なとき（取り消された・期限切れ）は、保存してあるものは消さずに、
     * 設定画面に出すエラーだけ残す。アカウントごとに独立して行う。
     */
    fun refresh(context: Context) {
        val settings = Settings(context)
        for (account in settings.taskAccounts) refresh(context, account)
    }

    fun refresh(context: Context, account: String): Boolean {
        val settings = Settings(context)
        return try {
            val result: AuthorizationResult = com.google.android.gms.tasks.Tasks.await(
                Identity.getAuthorizationClient(context).authorize(authorizationRequest(account)), 30, TimeUnit.SECONDS,
            )
            val token = result.accessToken
            if (result.hasResolution() || token == null) {
                settings.setTasksError(account, "許可が必要です。いったん連携をやめて、もう一度連携してください。")
                return false
            }
            val fetched = fetch(token)
            settings.setTasksJson(account, fetched.tasks.toString())
            settings.setTasksSummary(account, fetched.summary)
            settings.setTasksError(account, "")
            true
        } catch (e: Exception) {
            settings.setTasksError(account, "取得できませんでした：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** 取得の結果（保存するタスクと、設定画面に出す件数の説明）。 */
    private class Fetched(val tasks: JSONArray, val summary: String)

    /**
     * すべてのリストの未完了のタスク。期限での絞り込みは API に任せず、こちらで行う
     * （繰り返しなどで期限の入り方が違っても取りこぼさないため）。
     */
    private fun fetch(token: String): Fetched {
        val out = JSONArray()
        val lists = getJson("$API/users/@me/lists?maxResults=100", token).optJSONArray("items")
            ?: return Fetched(out, "タスクのリストが見つかりません")
        var total = 0
        var noDue = 0
        val dues = ArrayList<String>()
        for (i in 0 until lists.length()) {
            val id = lists.getJSONObject(i).getString("id")
            var page: String? = null
            do {
                val url = "$API/lists/${URLEncoder.encode(id, "UTF-8")}/tasks?showCompleted=false&showHidden=false&maxResults=100" +
                    (page?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
                val json = getJson(url, token)
                val items = json.optJSONArray("items")
                for (j in 0 until (items?.length() ?: 0)) {
                    val t = items!!.getJSONObject(j)
                    val title = t.optString("title")
                    if (title.isBlank() || t.optString("status") == "completed") continue
                    total++
                    val due = t.optString("due").take(10)
                    if (due.length != 10) { noDue++; continue }
                    // 期限は日付だけ（時刻は 0 時 UTC で入っている）なので、日付の部分をそのまま使う
                    out.put(JSONObject().put("title", title).put("due", due))
                    dues += due
                }
                page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
            } while (page != null)
        }
        return Fetched(out, "リスト ${lists.length()} 件、未完了のタスク $total 件（期限あり ${total - noDue} 件：${dues.sorted().joinToString("、").ifEmpty { "なし" }}）")
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
