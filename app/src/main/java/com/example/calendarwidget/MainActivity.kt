package com.example.calendarwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.accounts.AccountManager
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** 設定画面：予定の権限、表示するカレンダー、文字の大きさ。変えるとすぐウィジェットに反映する。 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var body: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        // ウィジェットの「編集」から開かれたときは、閉じたときに OK を返す
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(body) })
        build()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        build()
        WidgetRenderer.updateAll(this)
    }

    private fun text(s: String, size: Float = 15f, color: Int = Color.parseColor("#202124")) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        setPadding(0, dp(6), 0, dp(6))
    }

    /** タイトルを押すと開閉する欄。開いているかは再描画しても保つ。 */
    private fun collapsible(title: String, key: String, content: LinearLayout) {
        val toggle = text("", 17f)
        toggle.setPadding(0, dp(20), 0, dp(10))
        fun apply() {
            val open = key in openSections
            content.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = (if (open) "▼ " else "▶ ") + title
        }
        toggle.setOnClickListener {
            if (!openSections.add(key)) openSections.remove(key)
            apply()
        }
        apply()
        body.addView(toggle)
        body.addView(content)
    }

    private val openSections = mutableSetOf<String>()

    private fun build() {
        body.removeAllViews()
        body.addView(text("たっぴぃずカレンダー", 22f))
        body.addView(text("今日からの1週間を1行に並べて予定を表示します。ホーム画面を長押しして「ウィジェット」から置いてください。", 14f, Color.GRAY))

        // カレンダーもタスクも、いますぐ全部取り直す
        body.addView(Button(this).apply {
            text = "カレンダーとタスクをすべて更新"
            setOnClickListener { refreshTasks() }
        })

        if (!Events.hasPermission(this)) {
            body.addView(text("予定を表示するには、カレンダーの閲覧を許可してください。"))
            body.addView(Button(this).apply {
                text = "カレンダーの閲覧を許可する"
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }
            })
        }

        val label = text("文字の大きさ（${settings.textScale}%）", 17f)
        body.addView(label)
        body.addView(SeekBar(this).apply {
            max = 140
            progress = settings.textScale - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "文字の大きさ（${p + 60}%）"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    settings.textScale = s.progress + 60
                    WidgetRenderer.updateAll(this@MainActivity)
                }
            })
        })

        body.addView(text("予定の段数", 17f))
        body.addView(text("「自動」で予定が少なすぎるときは、段数を決めてください（多すぎるとウィジェットの下が切れます）。", 13f, Color.GRAY))
        body.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((value, label) in listOf(0 to "自動", 1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5")) {
                addView(android.widget.RadioButton(this@MainActivity).apply {
                    text = label
                    id = 100 + value
                    isChecked = settings.laneCount == value
                    setTextColor(Color.parseColor("#202124"))
                    setOnClickListener { settings.laneCount = value; WidgetRenderer.updateAll(this@MainActivity) }
                })
            }
        })
        val manager = AppWidgetManager.getInstance(this)
        val heights = manager.getAppWidgetIds(android.content.ComponentName(this, CalendarWidgetReceiver::class.java))
            .joinToString { "${WidgetRenderer.reportedHeight(manager, it)}dp" }
        if (heights.isNotEmpty()) body.addView(text("ウィジェットの高さ（ホーム画面の報告）: $heights", 12f, Color.GRAY))

        body.addView(Button(this).apply {
            text = "完了"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
            gravity = Gravity.CENTER
            setOnClickListener { WidgetRenderer.updateAll(this@MainActivity); finish() }
        })

        // いちばん下：Google タスクと表示するカレンダー（最初は閉じておく）
        buildTasksSection()

        if (Events.hasPermission(this)) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val hidden = settings.hiddenCalendars.toMutableSet()
            val calendars = Events.calendars(this)
            if (calendars.isEmpty()) list.addView(text("カレンダーが見つかりません", 14f, Color.GRAY))
            for (c in calendars) {
                list.addView(CheckBox(this).apply {
                    text = if (c.account.isBlank() || c.account == c.name) c.name else "${c.name}\n${c.account}"
                    isChecked = c.id !in hidden
                    setTextColor(Color.parseColor("#202124"))
                    setOnCheckedChangeListener { _, on ->
                        if (on) hidden.remove(c.id) else hidden.add(c.id)
                        settings.hiddenCalendars = hidden
                        WidgetRenderer.updateAll(this@MainActivity)
                    }
                })
            }
            collapsible("表示するカレンダー", "calendars", list)
        }
    }

    // ---- Google タスク ----

    /** 連携の途中のアカウント（許可の画面から戻ってきたときに使う）。 */
    private var pendingAccount = ""

    private fun buildTasksSection() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text("Google カレンダーアプリに出るタスクも、ウィジェットに出せます（読み取りだけ。通信は数時間おきと「今すぐ更新」のときだけ。アカウントは複数連携できます）。", 13f, Color.GRAY))
        val accounts = settings.taskAccounts
        for (account in accounts) {
            val error = settings.tasksError(account)
            box.addView(text(if (account.isEmpty()) "最初に連携したアカウント" else account, 15f))
            box.addView(text(if (error.isEmpty()) settings.tasksSummary(account).ifEmpty { "まだ取得していません" } else error, 13f, if (error.isEmpty()) Color.GRAY else Color.RED))
            box.addView(smallButton("連携をやめる") {
                settings.removeTaskAccount(account)
                WidgetRenderer.updateAll(this@MainActivity)
                build()
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (accounts.isNotEmpty()) row.addView(smallButton("今すぐ更新") { refreshTasks() })
        row.addView(smallButton(if (accounts.isEmpty()) "連携する" else "別のアカウントを連携") {
            startActivityForResult(
                AccountPicker.newChooseAccountIntent(
                    AccountPicker.AccountChooserOptions.Builder().setAllowableAccountsTypes(listOf("com.google")).build(),
                ),
                REQUEST_PICK,
            )
        })
        box.addView(row)
        val failed = accounts.any { settings.tasksError(it).isNotEmpty() }
        val state = when {
            accounts.isEmpty() -> "未連携"
            failed -> "エラーあり"
            else -> "${accounts.size}件連携中"
        }
        collapsible("Google タスク（$state）", "tasks", box)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** 読み取りの許可を求める。まだ許可していなければ Google の許可画面が出る。 */
    private fun authorizeTasks(account: String) {
        pendingAccount = account
        Identity.getAuthorizationClient(this).authorize(Tasks.authorizationRequest(account))
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    startIntentSenderForResult(pending.intentSender, REQUEST_TASKS, null, 0, 0, 0)
                } else {
                    onTasksAuthorized(account)
                }
            }
            .addOnFailureListener { e ->
                settings.setTasksError(account, "許可できませんでした：${e.message ?: e.javaClass.simpleName}")
                if (account !in settings.taskAccounts) settings.taskAccounts = settings.taskAccounts + account
                build()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_PICK -> data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { authorizeTasks(it) }
            REQUEST_TASKS -> onTasksAuthorized(pendingAccount)
        }
    }

    private fun onTasksAuthorized(account: String) {
        if (account !in settings.taskAccounts) settings.taskAccounts = settings.taskAccounts + account
        settings.setTasksError(account, "")
        refreshTasks()
    }

    private fun refreshTasks() {
        Thread {
            Tasks.refresh(applicationContext)
            runOnUiThread {
                WidgetRenderer.updateAll(this)
                build()
                val failed = settings.taskAccounts.any { settings.tasksError(it).isNotEmpty() }
                Toast.makeText(this, if (failed) "更新しました（タスクの取得でエラーがあります）" else "更新しました", Toast.LENGTH_SHORT).show()
            }
        }.start()
        build()
    }

    companion object {
        private const val REQUEST_TASKS = 7
        private const val REQUEST_PICK = 8
    }
}
