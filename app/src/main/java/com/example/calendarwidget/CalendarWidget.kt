package com.example.calendarwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** ホーム画面のウィジェット：今日から7日分を1行に、左右いっぱいに並べて予定を表示する。 */
class CalendarWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 新しい版を入れたとき・日付が変わったとき・予定が変わったときは描き直す
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_PROVIDER_CHANGED -> {
                WidgetRenderer.updateAll(context)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.render(context, manager, it) }
        CalendarWorker.schedulePeriodic(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRenderer.render(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        CalendarWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        CalendarWorker.cancelPeriodic(context)
    }
}

private const val PADDING_DP = 4f
private const val MAX_CHIPS = 6

object WidgetRenderer {
    private val WHITE = Color.parseColor("#EDEDED")
    private val GRAY = Color.parseColor("#BDBDBD")
    private val RED = Color.parseColor("#F2635B")
    private val BLUE = Color.parseColor("#5B95F2")

    /** 置かれているすべてのウィジェットを描き直す。 */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, CalendarWidgetReceiver::class.java)).forEach { render(context, manager, it) }
    }

    /** ウィジェット全体を作って渡す。 */
    fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val settings = Settings(context)
        val m = metrics(context, manager, id, settings)
        val today = LocalDate.now()
        val permitted = Events.hasPermission(context)
        val events = Events.load(context, today, today.plusDays(6), settings.hiddenCalendars)
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setViewPadding(R.id.page, m.dp(PADDING_DP), m.dp(PADDING_DP), m.dp(PADDING_DP), m.dp(PADDING_DP))
        // 描き直しでも使い回されるよう、先に removeAllViews を置く（番号付きで入れる決まり）
        rv.removeAllViews(R.id.page)
        for (i in 0 until 7) {
            val date = today.plusDays(i.toLong())
            val view = dayView(context, date, i == 0, events[date].orEmpty(), m, permitted)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rv.addStableView(R.id.page, view, i) else rv.addView(R.id.page, view)
        }
        manager.updateAppWidget(id, rv)
    }

    private fun dayView(context: Context, date: LocalDate, isToday: Boolean, list: List<Ev>, m: Metrics, permitted: Boolean): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_day)
        val dayColor = when {
            JapaneseHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> RED
            date.dayOfWeek == DayOfWeek.SATURDAY -> BLUE
            else -> WHITE
        }
        val dayText = if (date.dayOfMonth == 1) "${date.monthValue}/1" else "${date.dayOfMonth}"
        val head = SpannableStringBuilder("$dayText ").append("日月火水木金土"[date.dayOfWeek.value % 7].toString())
        head.setSpan(ForegroundColorSpan(dayColor), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rv.setTextViewText(R.id.day_head, head)
        rv.setTextViewTextSize(R.id.day_head, TypedValue.COMPLEX_UNIT_DIP, m.unit)
        rv.setInt(R.id.day_head, "setBackgroundResource", if (isToday) R.drawable.today_bg else 0)

        // 予定の権限がないときは、今日の列にその案内を出す
        val rows: List<Ev?> = if (!permitted && isToday) listOf(null) else list
        val chipIds = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5)
        val slots = m.chips.coerceIn(1, MAX_CHIPS)
        val overflow = rows.size > slots
        val shown = if (overflow) slots - 1 else rows.size
        for (i in 0 until MAX_CHIPS) {
            val id = chipIds[i]
            if (i >= slots || (i >= shown && !(overflow && i == shown))) {
                rv.setViewVisibility(id, View.GONE)
                continue
            }
            rv.setViewVisibility(id, View.VISIBLE)
            rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, m.unit * 0.85f)
            if (i >= shown) {
                rv.setTextViewText(id, "+${rows.size - shown}")
                rv.setTextColor(id, GRAY)
                rv.setInt(id, "setBackgroundResource", 0)
                continue
            }
            val ev = rows[i]
            if (ev == null) {
                rv.setTextViewText(id, "要許可")
                rv.setTextColor(id, GRAY)
                rv.setInt(id, "setBackgroundResource", 0)
            } else if (ev.allDay) {
                // 終日：カレンダーの色で塗る
                rv.setTextViewText(id, ev.title)
                rv.setTextColor(id, Color.WHITE)
                rv.setBg(id, ev.color)
            } else {
                // 時間のある予定：色の点＋タイトル（Google カレンダーの月表示と同じ）
                val t = SpannableStringBuilder("●").append(" ${ev.title}")
                t.setSpan(ForegroundColorSpan(ev.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rv.setTextViewText(id, t)
                rv.setTextColor(id, WHITE)
                rv.setInt(id, "setBackgroundResource", 0)
            }
        }

        // 押すと、その日を Google カレンダーなどで開く（権限がなければ設定画面）
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val click = if (permitted) {
            val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val uri = ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), millis).build()
            PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        } else {
            PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        }
        rv.setOnClickPendingIntent(R.id.day, click)
        return rv
    }

    /** 色で塗る（Android 12 以降は角を丸くしたまま色を替える）。 */
    private fun RemoteViews.setBg(viewId: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setInt(viewId, "setBackgroundResource", R.drawable.chip_bg)
            setColorStateList(viewId, "setBackgroundTintList", ColorStateList.valueOf(color))
        } else {
            setInt(viewId, "setBackgroundColor", color)
        }
    }

    /**
     * 文字の大きさの決め方。1 単位＝日付の文字の大きさ（dp）。
     * 1列の幅に収まる大きさにし、高さから予定を何件出せるかを決める。設定の％を掛ける。
     * 大きさは dp で指定するので、端末の文字サイズ設定には左右されない。
     */
    class Metrics(val unit: Float, val density: Float, val chips: Int) {
        fun dp(dp: Float): Int = (dp * density).toInt()
    }

    private fun metrics(context: Context, manager: AppWidgetManager, id: Int, settings: Settings): Metrics {
        val options = manager.getAppWidgetOptions(id)
        // 縦向きのときの幅と高さ（dp）
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 70
        val unit = ((width - PADDING_DP * 2) / 7 / 4.4f).coerceIn(6f, 24f) * settings.textScale / 100f
        // 日付の行と、予定1件ぶんの高さ（実測に合わせて、はみ出さないよう少し大きめに見る）
        val chipHeight = unit * 0.85f * 1.5f + 1f
        val chips = ((height - PADDING_DP * 2.5f - unit * 1.9f) / chipHeight).toInt().coerceIn(1, MAX_CHIPS)
        return Metrics(unit, context.resources.displayMetrics.density, chips)
    }
}
