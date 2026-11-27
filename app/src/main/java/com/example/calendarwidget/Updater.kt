package com.example.calendarwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * アプリ自身の更新。GitHub（公開リポジトリ）の apk ブランチにある最新の APK を調べて、
 * 新しければダウンロードしておき、インストールの確認画面まで進める。
 * 確認画面（「更新」を押す）は Android の仕組みで必ず出る。
 */
object Updater {
    private const val REPO = "tapeinooo/calendar-widget"
    private const val BRANCH = "apk"
    private val NAME = Regex("""^calendar-widget-v(\d+(?:\.\d+)*)\.apk$""")
    private const val CHANNEL = "update"

    private class Latest(val version: String, val file: String)

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** a が b より新しいか（1.9 < 1.10 のように数字ごとに比べる）。 */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun apkFile(context: Context) = File(context.cacheDir, "update.apk")

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "calendar-widget-updater")
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            error("HTTP $code")
        }
        return c
    }

    private fun fetchLatest(): Latest? {
        val c = get("https://api.github.com/repos/$REPO/contents/?ref=$BRANCH")
        val array = try { JSONArray(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        var best: Latest? = null
        for (i in 0 until array.length()) {
            val name = array.getJSONObject(i).optString("name")
            val v = NAME.matchEntire(name)?.groupValues?.get(1) ?: continue
            if (best == null || isNewer(v, best.version)) best = Latest(v, name)
        }
        return best
    }

    private fun download(context: Context, latest: Latest) {
        val out = apkFile(context)
        val tmp = File(context.cacheDir, "update.apk.tmp")
        val c = get("https://raw.githubusercontent.com/$REPO/$BRANCH/${latest.file}")
        try {
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            c.disconnect()
        }
        // 壊れたファイルや別のアプリを入れないよう、中身を確かめる
        val info = context.packageManager.getPackageArchiveInfo(tmp.path, 0)
        if (info == null || info.packageName != context.packageName || info.versionName != latest.version) {
            tmp.delete()
            error("ダウンロードしたファイルが正しくありません")
        }
        out.delete()
        check(tmp.renameTo(out)) { "ファイルを保存できませんでした" }
        Settings(context).updateDownloaded = latest.version
    }

    /**
     * 新しい版を調べる（通信するので、バックグラウンドで呼ぶ）。download が true なら、新しい版をダウンロードしておく。
     * 新しい版があれば true。結果とエラーは Settings に残す。
     */
    fun check(context: Context, download: Boolean): Boolean {
        val settings = Settings(context)
        return try {
            val latest = fetchLatest()
            settings.updateError = ""
            if (latest == null || !isNewer(latest.version, installedVersion(context))) {
                settings.updateAvailable = ""
                settings.updateDownloaded = ""
                apkFile(context).delete()
                return false
            }
            settings.updateAvailable = latest.version
            if (download && (settings.updateDownloaded != latest.version || !apkFile(context).exists())) download(context, latest)
            true
        } catch (e: Exception) {
            settings.updateError = "確認できませんでした：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** ダウンロード済みの新しい版が用意できているか。 */
    fun isReady(context: Context): Boolean {
        val s = Settings(context)
        return s.updateAvailable.isNotEmpty() && s.updateDownloaded == s.updateAvailable && apkFile(context).exists()
    }

    /** 新しい版を調べたあとに、まだ知らせていなければ通知する（定期処理から呼ぶ）。 */
    fun notifyIfNeeded(context: Context) {
        val s = Settings(context)
        if (!isReady(context) || s.updateNotified == s.updateAvailable) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("新しい版 ${s.updateAvailable} があります。押して更新してください")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(1, n)
        s.updateNotified = s.updateAvailable
    }

    /**
     * ダウンロード済みの APK のインストールを始める。Android の確認画面は結果を受ける側で出す。
     * 始められなかったときは、理由を返す。
     */
    fun install(context: Context): String? {
        if (!isReady(context)) return "新しい版がまだ用意できていません"
        if (!context.packageManager.canRequestPackageInstalls()) {
            // 「このアプリからのインストールを許可」の画面を開く
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "このアプリからのインストールを許可してから、もう一度「更新する」を押してください"
        }
        return try {
            val file = apkFile(context)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { i ->
                    session.openWrite("update", 0, file.length()).use { o ->
                        i.copyTo(o)
                        session.fsync(o)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(result.intentSender)
            }
            null
        } catch (e: Exception) {
            "インストールを始められませんでした：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** インストールの結果を受ける。確認が必要なときは、Android の確認画面を出す。 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(context, "更新できませんでした $reason", Toast.LENGTH_LONG).show()
            }
        }
    }
}
