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
import android.net.Uri
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

/**
 * ホーム画面のウィジェット：今日から1週間を1行に並べ、左右で1週間ずつ送る。
 * 送りを速くするため、送れる範囲のページ（1週間ぶん）を全部 ViewFlipper に入れて渡しておき、
 * 押されたら「何枚目を見せるか」だけを送る（partiallyUpdateAppWidget）。
 */
class CalendarWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 新しい版を入れたら、ウィジェットを新しい版で作り直す
                WidgetRenderer.updateAll(context)
                return
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_PROVIDER_CHANGED -> {
                WidgetRenderer.updateAll(context)
                return
            }
            ACTION_NOTHING -> return
            ACTION_SHIFT, ACTION_TODAY -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val delta = if (intent.action == ACTION_TODAY) null else intent.getIntExtra(EXTRA_DELTA, 0)
                    WidgetRenderer.shift(context, id, delta)
                    OffsetReset.arm(context)
                }
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

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetRenderer.forget(context, it) }
    }

    override fun onEnabled(context: Context) {
        CalendarWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        CalendarWorker.cancelPeriodic(context)
    }

    companion object {
        const val ACTION_SHIFT = "com.example.calendarwidget.SHIFT"
        const val ACTION_TODAY = "com.example.calendarwidget.TODAY"
        const val ACTION_NOTHING = "com.example.calendarwidget.NOTHING"
        const val EXTRA_DELTA = "delta"
    }
}

private const val ARROW_WIDTH_DP = 26f
private const val PADDING_DP = 6f

/** 送れる範囲（週）。今日の週から、過去 BACK 週・先 FORWARD 週まで。 */
private const val BACK_WEEKS = 2
private const val FORWARD_WEEKS = 6
private const val PAGE_COUNT = BACK_WEEKS + FORWARD_WEEKS + 1
private const val MAX_CHIPS = 6

object WidgetRenderer {
    private val WHITE = Color.parseColor("#EDEDED")
    private val GRAY = Color.parseColor("#BDBDBD")
    private val RED = Color.parseColor("#F2635B")
    private val BLUE = Color.parseColor("#5B95F2")

    /** 置かれているすべてのウィジェットを描き直す。 */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        ids(context, manager).forEach { render(context, manager, it) }
    }

    /** 送った位置を元（今日の週）に戻す。 */
    fun resetOffsets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val state = state(context)
        ids(context, manager).forEach { id ->
            if (state.getInt(offsetKey(id), 0) != 0) {
                state.edit().remove(offsetKey(id)).apply()
                render(context, manager, id)
            }
        }
    }

    fun forget(context: Context, id: Int) {
        state(context).edit().remove(offsetKey(id)).remove(versionKey(id)).apply()
    }

    /** ウィジェット全体を作って渡す。 */
    fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val settings = Settings(context)
        val m = metrics(context, manager, id, settings)
        val today = LocalDate.now()
        val offset = state(context).getInt(offsetKey(id), 0).coerceIn(-BACK_WEEKS, FORWARD_WEEKS)
        val rv = RemoteViews(context.packageName, R.layout.widget)
        state(context).edit().putLong(versionKey(id), appVersion(context)).apply()

        val first = today.plusWeeks(-BACK_WEEKS.toLong())
        val events = Events.load(context, first, first.plusWeeks(PAGE_COUNT.toLong()).minusDays(1), settings.hiddenCalendars)

        rv.removeAllViews(R.id.flipper)
        for (p in 0 until PAGE_COUNT) {
            rv.addChild(R.id.flipper, pageView(context, first.plusWeeks(p.toLong()), today, events, m), p)
        }
        rv.setDisplayedChild(R.id.flipper, offset + BACK_WEEKS)
        // 日の並びは、タイトルの下で、左右に矢印の分をあけて置く
        rv.setViewPadding(R.id.days_frame, m.dp(ARROW_WIDTH_DP), m.titleHeight(), m.dp(ARROW_WIDTH_DP), m.dp(PADDING_DP))
        rv.setViewVisibility(R.id.message, View.GONE)
        rv.setViewVisibility(R.id.days_frame, View.VISIBLE)
        rv.setViewVisibility(R.id.zones, View.VISIBLE)

        setupTitle(context, rv, id, today, offset, m, Events.hasPermission(context))
        setupZones(context, rv, id, offset, m, withIntents = true)
        manager.updateAppWidget(id, rv)
    }

    /**
     * 1週間送る（delta が null なら今日の週に戻す）。ページはもう渡してあるので、
     * 見せるページとタイトル・押す場所だけを送る（アニメーションはなし。最速で切り替える）。
     */
    fun shift(context: Context, id: Int, delta: Int?) {
        val manager = AppWidgetManager.getInstance(context)
        val state = state(context)
        val old = state.getInt(offsetKey(id), 0).coerceIn(-BACK_WEEKS, FORWARD_WEEKS)
        val offset = if (delta == null) 0 else (old + delta).coerceIn(-BACK_WEEKS, FORWARD_WEEKS)
        if (offset == old) return
        state.edit().putInt(offsetKey(id), offset).apply()
        // ホーム画面にある部品が前の版のものなら、差分は効かないので全体を渡し直す
        if (state.getLong(versionKey(id), -1) != appVersion(context)) {
            render(context, manager, id)
            return
        }
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setDisplayedChild(R.id.flipper, offset + BACK_WEEKS)
        val m = metrics(context, manager, id, Settings(context))
        setupTitle(context, rv, id, LocalDate.now(), offset, m, Events.hasPermission(context), withIntents = false)
        setupZones(context, rv, id, offset, m, withIntents = false)
        manager.partiallyUpdateAppWidget(id, rv)
    }

    /** タイトル（月）。押すと今日の週に戻る。予定の権限がなければ、押すと設定を開く。 */
    private fun setupTitle(
        context: Context, rv: RemoteViews, id: Int, today: LocalDate, offset: Int, m: Metrics,
        permitted: Boolean, withIntents: Boolean = true,
    ) {
        val start = today.plusWeeks(offset.toLong())
        val end = start.plusDays(6)
        val text = when {
            !permitted -> "タップして予定の表示を許可"
            start.month == end.month -> "${start.year}年${start.monthValue}月"
            start.year == end.year -> "${start.year}年${start.monthValue}月〜${end.monthValue}月"
            else -> "${start.year}年${start.monthValue}月〜${end.year}年${end.monthValue}月"
        }
        rv.setTextViewText(R.id.title, text)
        rv.setTextColor(R.id.title, if (permitted && offset != 0) WHITE else GRAY)
        if (!withIntents) return
        rv.setTextViewTextSize(R.id.title, TypedValue.COMPLEX_UNIT_DIP, m.unit * 1.0f)
        rv.setViewPadding(R.id.title, m.dp(12f), m.dp(PADDING_DP / 2), m.dp(12f), 0)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val click = if (permitted) {
            val i = Intent(context, CalendarWidgetReceiver::class.java)
                .setAction(CalendarWidgetReceiver.ACTION_TODAY)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            PendingIntent.getBroadcast(context, id * 8 + 4, i, flags)
        } else {
            PendingIntent.getActivity(context, id * 8 + 5, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        }
        rv.setOnClickPendingIntent(R.id.title, click)
    }

    /**
     * 左右の矢印の押す場所。端までは押せるが、行き止まり（これ以上送れない側）は何も起きない場所にする。
     * 押す場所はレイアウトに全部置いてあり、表示を切り替えるだけ（足したり消したりしない）。
     */
    private fun setupZones(context: Context, rv: RemoteViews, id: Int, offset: Int, m: Metrics, withIntents: Boolean) {
        fun side(delta: Int, enabled: Boolean, on: Int, off: Int, onArrow: Int, offArrow: Int) {
            rv.setViewVisibility(on, if (enabled) View.VISIBLE else View.GONE)
            rv.setViewVisibility(off, if (enabled) View.GONE else View.VISIBLE)
            if (!withIntents) return
            rv.setTextViewTextSize(onArrow, TypedValue.COMPLEX_UNIT_DIP, m.unit * 2.2f)
            rv.setTextViewTextSize(offArrow, TypedValue.COMPLEX_UNIT_DIP, m.unit * 2.2f)
            val request = id * 8 + if (delta > 0) 1 else 0
            val shift = Intent(context, CalendarWidgetReceiver::class.java)
                .setAction(CalendarWidgetReceiver.ACTION_SHIFT)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(CalendarWidgetReceiver.EXTRA_DELTA, delta)
            val nothing = Intent(context, CalendarWidgetReceiver::class.java).setAction(CalendarWidgetReceiver.ACTION_NOTHING)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            rv.setOnClickPendingIntent(on, PendingIntent.getBroadcast(context, request, shift, flags))
            rv.setOnClickPendingIntent(off, PendingIntent.getBroadcast(context, request + 2, nothing, flags))
        }
        side(-1, offset > -BACK_WEEKS, R.id.zone_left_on, R.id.zone_left_off, R.id.zone_left_on_arrow, R.id.zone_left_off_arrow)
        side(1, offset < FORWARD_WEEKS, R.id.zone_right_on, R.id.zone_right_off, R.id.zone_right_on_arrow, R.id.zone_right_off_arrow)
    }

    /** Android 12 以降は番号付きで入れる（描き直しで使い回される）。 */
    private fun RemoteViews.addChild(parent: Int, child: RemoteViews, stableId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addStableView(parent, child, stableId) else addView(parent, child)
    }

    private fun pageView(context: Context, start: LocalDate, today: LocalDate, events: Map<LocalDate, List<Ev>>, m: Metrics): RemoteViews {
        val page = RemoteViews(context.packageName, R.layout.widget_page)
        page.removeAllViews(R.id.page)
        for (i in 0 until 7) {
            val date = start.plusDays(i.toLong())
            page.addChild(R.id.page, dayView(context, date, date == today, events[date].orEmpty(), m), i)
        }
        return page
    }

    private fun dayView(context: Context, date: LocalDate, isToday: Boolean, list: List<Ev>, m: Metrics): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_day)
        val dayColor = when {
            JapaneseHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> RED
            date.dayOfWeek == DayOfWeek.SATURDAY -> BLUE
            else -> WHITE
        }
        // 日付：月の最初の日と1週の先頭は月も添える
        val dayText = if (date.dayOfMonth == 1) "${date.monthValue}/1" else "${date.dayOfMonth}"
        val weekday = "日月火水木金土"[date.dayOfWeek.value % 7]
        val head = SpannableStringBuilder("$dayText ").append(weekday.toString(), ForegroundColorSpan(dayColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        head.setSpan(ForegroundColorSpan(dayColor), 0, dayText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rv.setTextViewText(R.id.day_head, head)
        rv.setTextViewTextSize(R.id.day_head, TypedValue.COMPLEX_UNIT_DIP, m.unit * 1.0f)
        rv.setInt(R.id.day_head, "setBackgroundResource", if (isToday) R.drawable.today_bg else 0)

        val chipIds = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5)
        val slots = m.chips.coerceIn(1, MAX_CHIPS)
        val overflow = list.size > slots
        val shown = if (overflow) slots - 1 else list.size
        for (i in 0 until MAX_CHIPS) {
            val id = chipIds[i]
            if (i >= slots || (i >= shown && !(overflow && i == shown))) {
                rv.setViewVisibility(id, View.GONE)
                continue
            }
            rv.setViewVisibility(id, View.VISIBLE)
            rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, m.unit * 0.85f)
            if (i < shown) {
                val ev = list[i]
                if (ev.allDay) {
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
            } else {
                rv.setTextViewText(id, "+${list.size - shown}")
                rv.setTextColor(id, GRAY)
                rv.setInt(id, "setBackgroundResource", 0)
            }
        }

        // 押すと、その日を Google カレンダーなどで開く
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val view = Intent(Intent.ACTION_VIEW, ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), millis).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        rv.setOnClickPendingIntent(
            R.id.day,
            PendingIntent.getActivity(context, 0, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
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
     * 1列の幅に収まり、縦に予定が何件入るかも決まる。設定の％を掛ける。
     * 大きさは dp で指定するので、端末の文字サイズ設定には左右されない。
     */
    class Metrics(val unit: Float, val density: Float, val chips: Int) {
        fun px(factor: Float): Int = (unit * factor * density).toInt().coerceAtLeast(1)
        fun dp(dp: Float): Int = (dp * density).toInt()

        /** 日の並びの上の余白（タイトルの行の分）。 */
        fun titleHeight(): Int = dp(PADDING_DP / 2) + px(1.0f * 1.4f + 0.2f)
    }

    private fun metrics(context: Context, manager: AppWidgetManager, id: Int, settings: Settings): Metrics {
        val options = manager.getAppWidgetOptions(id)
        // 縦向きのときの幅と高さ（dp）
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 120
        val columnWidth = (width - ARROW_WIDTH_DP * 2) / 7
        val byWidth = columnWidth / 4.4f
        val unit = byWidth.coerceIn(6f, 24f) * settings.textScale / 100f
        // 日の並びに使える高さ：全体の高さ − 上下の余白 − タイトル − 日付の行
        val titleAndHead = unit * (1.0f * 1.4f + 0.2f) + unit * 1.4f
        val chipHeight = unit * 0.85f * 1.5f + 1f
        val chips = ((height - PADDING_DP * 2f - titleAndHead) / chipHeight).toInt().coerceIn(1, MAX_CHIPS)
        return Metrics(unit, context.resources.displayMetrics.density, chips)
    }

    private fun ids(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, CalendarWidgetReceiver::class.java))

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences("widget_state", Context.MODE_PRIVATE)

    private fun appVersion(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }

    private fun versionKey(id: Int) = "version_$id"
    private fun offsetKey(id: Int) = "offset_$id"
}
