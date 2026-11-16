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
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

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
    private val BG_IDS = intArrayOf(R.id.b0, R.id.b1, R.id.b2, R.id.b3, R.id.b4, R.id.b5, R.id.b6)
    private val BLUE_TINT = Color.parseColor("#265B95F2")
    private val RED_TINT = Color.parseColor("#26F2635B")
    private val TODAY_TINT = Color.parseColor("#33FFFFFF")

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
        val start = when (settings.startDay) {
            1 -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            2 -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            else -> today
        }
        val permitted = Events.hasPermission(context)
        val list = Events.load(context, start, start.plusDays(7L * m.weeks - 1), settings.hiddenCalendars)
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setViewPadding(R.id.page, m.dp(PADDING_DP), m.dp(PADDING_DP), m.dp(PADDING_DP), 0)
        // ウィジェットを押すと Google カレンダーのアプリを開く（入っていなければ今日の画面。権限がなければ設定画面）
        rv.setOnClickPendingIntent(R.id.page, openCalendarIntent(context, permitted))
        rv.removeAllViews(R.id.page)
        for (w in 0 until m.weeks) {
            val weekStart = start.plusWeeks(w.toLong())
            rv.addChild(R.id.page, weekView(context, weekStart, today, list, m, permitted), w)
        }
        manager.updateAppWidget(id, rv)
    }

    /** 1週間ぶん：列の背景・日付の行・予定の段。 */
    private fun weekView(context: Context, weekStart: LocalDate, today: LocalDate, list: List<Ev>, m: Metrics, permitted: Boolean): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.week)
        for (i in 0 until 7) {
            val date = weekStart.plusDays(i.toLong())
            val holiday = JapaneseHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY
            val saturday = date.dayOfWeek == DayOfWeek.SATURDAY
            val color = when {
                date == today -> DARK
                holiday -> RED
                saturday -> BLUE
                else -> WHITE
            }
            val h = HEAD_IDS[i]
            val day = if (date.dayOfMonth == 1 || (i == 0 && date != today)) "${date.monthValue}/${date.dayOfMonth}" else "${date.dayOfMonth}"
            rv.setTextViewText(h, "$day(${"日月火水木金土"[date.dayOfWeek.value % 7]})")
            rv.setTextColor(h, color)
            rv.setTextViewTextSize(h, TypedValue.COMPLEX_UNIT_DIP, m.unit)
            rv.setViewPadding(h, 0, m.dp(1f), 0, m.dp(1f))
            // 今日は丸く
            rv.setInt(h, "setBackgroundResource", if (date == today) R.drawable.today_bg else 0)
            // 列の背景：今日は白っぽく、土はうっすら青、日・祝日はうっすら赤（ほかは透明。毎回どちらかを指定する）
            rv.setInt(BG_IDS[i], "setBackgroundColor", when { date == today -> TODAY_TINT; holiday -> RED_TINT; saturday -> BLUE_TINT; else -> Color.TRANSPARENT })
        }
        // 予定の段。入る段数のほかに、まだ予定があるときは次の段も（見切れてもよいので）出し、予定が続いていると分かるようにする
        rv.removeAllViews(R.id.lanes)
        val lanes = layoutLanes(list, weekStart)
        val shown = minOf(lanes.size, m.lanes + 1)
        for (li in 0 until shown) rv.addChild(R.id.lanes, laneView(context, lanes[li], m), li)
        if (!permitted && lanes.isEmpty() && weekStart <= today && today < weekStart.plusDays(7)) {
            rv.addChild(R.id.lanes, noticeView(context, "要許可", m), 0)
        }
        return rv
    }

    /** 予定を段に割り当てる（weekStart からの7日にかかるものだけ）。複数日・終日を先に、同じ日なら開始の早い順。 */
    private fun layoutLanes(list: List<Ev>, weekStart: LocalDate): List<List<Seg>> {
        val weekEnd = weekStart.plusDays(6)
        val segs = list.filter { it.lastDate >= weekStart && it.startDate <= weekEnd }.map { ev ->
            val from = ChronoUnit.DAYS.between(weekStart, ev.startDate).toInt().coerceIn(0, 6)
            val to = ChronoUnit.DAYS.between(weekStart, ev.lastDate).toInt().coerceIn(0, 6)
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
     * レイアウトにも標準の 10dp を書いておく（描画時の指定が効かなかったときに、Android の既定の大きな文字にならないように）。
     */
    class Metrics(val unit: Float, val density: Float, val lanes: Int, val weeks: Int) {
        fun dp(dp: Float): Int = (dp * density).toInt()
    }

    /** ホーム画面が報告するウィジェットの高さ（dp）。 */
    fun reportedHeight(manager: AppWidgetManager, id: Int): Int =
        manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)

    private fun metrics(manager: AppWidgetManager, id: Int, settings: Settings, context: Context): Metrics {
        // 報告される高さは、実際に使える高さより小さいことがある（スマホで確認）ので、少し足して見る
        val height = (reportedHeight(manager, id).takeIf { it > 0 } ?: 70) + HEIGHT_BONUS
        val unit = BASE_UNIT * settings.textScale / 100f
        // 週の数：縦1マス（高さ100dp未満）なら1週。ほかは1週あたり少なくとも MIN_WEEK_DP の高さが取れる分まで
        val weeks = if (height < ONE_ROW_LIMIT_DP) 1 else settings.weeks.coerceAtMost((height / MIN_WEEK_DP).toInt().coerceAtLeast(1))
        // 予定の段数は1週あたりの高さから決める（週が1つなら縦いっぱいに）
        val weekHeight = (height - PADDING_DP) / weeks
        val auto = ((weekHeight - PADDING_DP - unit * HEAD_FACTOR) / (unit * CHIP_FACTOR + 1.5f)).toInt()
        val lanes = (settings.laneCount.takeIf { it > 0 } ?: auto).coerceAtLeast(1)
        return Metrics(unit, context.resources.displayMetrics.density, lanes, weeks)
    }

    private const val BASE_UNIT = 10f
    private const val HEIGHT_BONUS = 10
    private const val ONE_ROW_LIMIT_DP = 100
    private const val MIN_WEEK_DP = 55f

    /** 日付の行・予定1段の高さ（文字の大きさの何倍か。余白を含む）。 */
    private const val HEAD_FACTOR = 1.5f
    private const val CHIP_FACTOR = 1.25f
}
