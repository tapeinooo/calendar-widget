package com.example.calendarwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

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

    private fun build() {
        body.removeAllViews()
        body.addView(text("たっぴぃずカレンダー", 22f))
        body.addView(text("今日からの1週間を1行に並べて予定を表示します。ホーム画面を長押しして「ウィジェット」から置いてください。", 14f, Color.GRAY))

        if (!Events.hasPermission(this)) {
            body.addView(text("予定を表示するには、カレンダーの閲覧を許可してください。"))
            body.addView(Button(this).apply {
                text = "カレンダーの閲覧を許可する"
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }
            })
        } else {
            body.addView(text("表示するカレンダー", 17f))
            val hidden = settings.hiddenCalendars.toMutableSet()
            val calendars = Events.calendars(this)
            if (calendars.isEmpty()) body.addView(text("カレンダーが見つかりません", 14f, Color.GRAY))
            for (c in calendars) {
                body.addView(CheckBox(this).apply {
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

        body.addView(Button(this).apply {
            text = "完了"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
            gravity = Gravity.CENTER
            setOnClickListener { WidgetRenderer.updateAll(this@MainActivity); finish() }
        })
    }
}
