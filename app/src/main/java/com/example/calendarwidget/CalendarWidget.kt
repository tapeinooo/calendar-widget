package com.example.calendarwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
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
            // 0時の目覚まし（日付の変わり目の知らせ DATE_CHANGED は、今の Android ではアプリに届かないため）。
            // 端末の再起動で目覚ましが消えるので、起動時にも描き直して掛け直す
            MidnightAlarm.ACTION, Intent.ACTION_BOOT_COMPLETED,
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
        MidnightAlarm.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRenderer.render(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        CalendarWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        MidnightAlarm.cancel(context)
        CalendarWorker.cancelPeriodic(context)
    }
}

private const val PADDING_DP = 3f

/** 段（レーン）に置く予定の1本：表示する7日のうちの何日目から何日目まで。 */
private class Seg(val ev: Ev, val from: Int, val to: Int)

object WidgetRenderer {
    private const val GOOGLE_CALENDAR = "com.google.android.calendar"

    private val WHITE = Color.parseColor("#EDEDED")
    private val GRAY = Color.parseColor("#BDBDBD")
    private val RED = Color.parseColor("#F2635B")
    private val BLUE = Color.parseColor("#5B95F2")
    private val DARK = Color.parseColor("#202124")

    private val EVENT_LAYOUTS = intArrayOf(R.layout.seg_e1, R.layout.seg_e2, R.layout.seg_e3, R.layout.seg_e4, R.layout.seg_e5, R.layout.seg_e6, R.layout.seg_e7)
    private val GAP_LAYOUTS = intArrayOf(R.layout.seg_g1, R.layout.seg_g2, R.layout.seg_g3, R.layout.seg_g4, R.layout.seg_g5, R.layout.seg_g6, R.layout.seg_g7)
    private val HEAD_IDS = intArrayOf(R.id.h0, R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5, R.id.h6)

    /** 置かれているすべてのウィジェットを描き直す。 */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CalendarWidgetReceiver::class.java))
        ids.forEach { render(context, manager, it) }
        // 描き直すたびに、次の0時の目覚ましを掛け直す（0時の目覚ましで描き直すと、翌日の分が掛かる）
        if (ids.isNotEmpty()) MidnightAlarm.schedule(context)
    }

    /** ウィジェット全体を作って渡す。 */
    fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val settings = Settings(context)
        val m = metrics(manager, id, settings, context)
        val today = LocalDate.now()
        val permitted = Events.hasPermission(context)
        val list = Events.load(context, today, today.plusDays(6), settings.hiddenCalendars)
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setViewPadding(R.id.page, m.dp(PADDING_DP), m.dp(PADDING_DP), m.dp(PADDING_DP), 0)

        // 日付の行：押すと、その日を Google カレンダーなどで開く（権限がなければ設定画面）
        for (i in 0 until 7) {
            val date = today.plusDays(i.toLong())
            val color = when {
                i == 0 -> DARK
                JapaneseHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> RED
                date.dayOfWeek == DayOfWeek.SATURDAY -> BLUE
                else -> WHITE
            }
            val h = HEAD_IDS[i]
            val day = if (date.dayOfMonth == 1) "${date.monthValue}/1" else "${date.dayOfMonth}"
            rv.setTextViewText(h, "$day(${"日月火水木金土"[date.dayOfWeek.value % 7]})")
            rv.setTextColor(h, color)
            rv.setTextViewTextSize(h, TypedValue.COMPLEX_UNIT_DIP, m.unit)
            rv.setViewPadding(h, 0, m.dp(1f), 0, m.dp(1f))
            // 今日は丸く（上下の余白は押す場所のため残し、背景は文字のまわりだけ）
            rv.setInt(h, "setBackgroundResource", if (i == 0) R.drawable.today_bg else 0)
        }

        // ウィジェットを押すと Google カレンダーのアプリを開く（入っていなければ今日の画面。権限がなければ設定画面）
        rv.setOnClickPendingIntent(R.id.page, openCalendarIntent(context, permitted))

        // 予定の段。描き直しでも使い回されるよう、先に removeAllViews を置く（番号付きで入れる決まり）
        rv.removeAllViews(R.id.lanes)
        val lanes = layoutLanes(list, today)
        // 入る段数のほかに、まだ予定があるときは次の段も（見切れてもよいので）出し、予定が続いていると分かるようにする
        val shown = minOf(lanes.size, m.lanes + 1)
        for (li in 0 until shown) rv.addChild(R.id.lanes, laneView(context, lanes[li], m), li)
        if (!permitted && lanes.isEmpty()) rv.addChild(R.id.lanes, noticeView(context, "要許可", m), 0)
        manager.updateAppWidget(id, rv)
    }

    /** 予定を段に割り当てる。複数日・終日を先に、同じ日なら開始の早い順。 */
    private fun layoutLanes(list: List<Ev>, today: LocalDate): List<List<Seg>> {
        val segs = list.map { ev ->
            val from = java.time.temporal.ChronoUnit.DAYS.between(today, ev.startDate).toInt().coerceIn(0, 6)
            val to = java.time.temporal.ChronoUnit.DAYS.between(today, ev.lastDate).toInt().coerceIn(0, 6)
            Seg(ev, from, to)
        }.sortedWith(compareBy<Seg>({ !(it.ev.allDay || it.to > it.from) }, { it.from }, { -(it.to - it.from) }, { it.ev.begin }))
        val lanes = ArrayList<MutableList<Seg>>()
        for (seg in segs) {
            val lane = lanes.firstOrNull { l -> l.none { it.from <= seg.to && seg.from <= it.to } }
                ?: ArrayList<Seg>().also { lanes += it }
            lane += seg
        }
        return lanes.map { l -> l.sortedBy { it.from } }
    }

    private fun laneView(context: Context, lane: List<Seg>, m: Metrics): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.lane)
        row.removeAllViews(R.id.lane)
        var pos = 0
        var n = 0
        for (seg in lane) {
            if (seg.from > pos) row.addChild(R.id.lane, RemoteViews(context.packageName, GAP_LAYOUTS[seg.from - pos - 1]), n++)
            val ev = seg.ev
            val chip = RemoteViews(context.packageName, EVENT_LAYOUTS[seg.to - seg.from])
            chip.setTextViewText(R.id.seg, ev.title)
            chip.setTextViewTextSize(R.id.seg, TypedValue.COMPLEX_UNIT_DIP, m.unit)
            chip.setTextColor(R.id.seg, if (isLight(ev.color)) DARK else Color.WHITE)
            chip.setBg(R.id.seg, ev.color)
            row.addChild(R.id.lane, chip, n++)
            pos = seg.to + 1
        }
        if (pos < 7) row.addChild(R.id.lane, RemoteViews(context.packageName, GAP_LAYOUTS[7 - pos - 1]), n)
        return row
    }

    /** 予定の権限がないときの案内（今日の列）。 */
    private fun noticeView(context: Context, text: String, m: Metrics): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.lane)
        row.removeAllViews(R.id.lane)
        val cell = RemoteViews(context.packageName, R.layout.seg_e2)
        cell.setTextViewTextSize(R.id.seg, TypedValue.COMPLEX_UNIT_DIP, m.unit)
        cell.setTextColor(R.id.seg, GRAY)
        cell.setInt(R.id.seg, "setBackgroundResource", 0)
        cell.setTextViewText(R.id.seg, text)
        row.addChild(R.id.lane, cell, 0)
        return row
    }

    private fun openCalendarIntent(context: Context, permitted: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (!permitted) {
            return PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR)
            ?: Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
        return PendingIntent.getActivity(context, 0, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
    }

    private fun RemoteViews.addChild(parent: Int, child: RemoteViews, stableId: Int = 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addStableView(parent, child, stableId) else addView(parent, child)
    }

    private fun isLight(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 150

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
     * 文字の大きさの決め方。1 単位＝文字の大きさ（dp）。標準は 10dp で、設定の％を掛ける
     * （幅や高さには合わせない：予定の文字は右が切れてよい）。予定の段の高さは文字の大きさに合わせ、
     * 高さに入る段の数だけ出す（設定で段数を決めることもできる）。
     * 大きさは dp で指定するので、端末の文字サイズ設定には左右されない。
     */
    class Metrics(val unit: Float, val density: Float, val lanes: Int) {
        fun dp(dp: Float): Int = (dp * density).toInt()
    }

    /** ホーム画面が報告するウィジェットの高さ（dp）。 */
    fun reportedHeight(manager: AppWidgetManager, id: Int): Int =
        manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)

    private fun metrics(manager: AppWidgetManager, id: Int, settings: Settings, context: Context): Metrics {
        // 報告される高さは、実際に使える高さより小さいことがある（スマホで確認）ので、少し足して見る
        val height = (reportedHeight(manager, id).takeIf { it > 0 } ?: 70) + HEIGHT_BONUS
        val unit = BASE_UNIT * settings.textScale / 100f
        val auto = ((height - PADDING_DP * 2 - unit * HEAD_FACTOR) / (unit * CHIP_FACTOR + 1.5f)).toInt()
        val lanes = (settings.laneCount.takeIf { it > 0 } ?: auto).coerceAtLeast(1)
        return Metrics(unit, context.resources.displayMetrics.density, lanes)
    }

    private const val BASE_UNIT = 10f
    private const val HEIGHT_BONUS = 10

    /** 日付の行・予定1段の高さ（文字の大きさの何倍か。余白を含む）。 */
    private const val HEAD_FACTOR = 1.5f
    private const val CHIP_FACTOR = 1.25f
}
