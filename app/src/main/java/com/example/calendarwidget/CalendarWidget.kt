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

private const val PADDING_DP = 3f

/** 段（レーン）に置く予定の1本：表示する7日のうちの何日目から何日目まで。 */
private class Seg(val ev: Ev, val from: Int, val to: Int)

object WidgetRenderer {
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
        manager.getAppWidgetIds(ComponentName(context, CalendarWidgetReceiver::class.java)).forEach { render(context, manager, it) }
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
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

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
            rv.setTextViewText(h, if (date.dayOfMonth == 1) "${date.monthValue}/1" else "${date.dayOfMonth}")
            rv.setTextColor(h, color)
            rv.setTextViewTextSize(h, TypedValue.COMPLEX_UNIT_DIP, m.unit)
            rv.setViewPadding(h, 0, m.dp(1f), 0, m.dp(1f))
            // 今日は丸く（上下の余白は押す場所のため残し、背景は文字のまわりだけ）
            rv.setInt(h, "setBackgroundResource", if (i == 0) R.drawable.today_bg else 0)
            val click = if (permitted) {
                val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val uri = ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), millis).build()
                PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            } else {
                PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            }
            rv.setOnClickPendingIntent(h, click)
        }

        // 予定の段。描き直しでも使い回されるよう、先に removeAllViews を置く（番号付きで入れる決まり）
        rv.removeAllViews(R.id.lanes)
        val lanes = layoutLanes(list, today)
        val visible = m.lanes.coerceAtLeast(1)
        // 段が1つしか入らないときは、「+件数」より予定そのものを出す
        val overflow = lanes.size > visible && visible > 1
        val shown = if (overflow) visible - 1 else minOf(lanes.size, visible)
        for (li in 0 until shown) {
            rv.addChild(R.id.lanes, laneView(context, lanes[li], li, m, today))
        }
        if (overflow || (!permitted && lanes.isEmpty())) {
            // 入りきらない予定は、日ごとの「+件数」にして最後の段に出す（権限がなければ案内）
            rv.addChild(R.id.lanes, moreView(context, lanes, shown, m, if (permitted) null else "要許可"), shown)
        }
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

    private fun laneView(context: Context, lane: List<Seg>, index: Int, m: Metrics, today: LocalDate): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.lane)
        row.removeAllViews(R.id.lane)
        var pos = 0
        var n = 0
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        for (seg in lane) {
            if (seg.from > pos) row.addChild(R.id.lane, RemoteViews(context.packageName, GAP_LAYOUTS[seg.from - pos - 1]), n++)
            val ev = seg.ev
            val chip = RemoteViews(context.packageName, EVENT_LAYOUTS[seg.to - seg.from])
            chip.setTextViewText(R.id.seg, ev.title)
            chip.setTextViewTextSize(R.id.seg, TypedValue.COMPLEX_UNIT_DIP, m.unit)
            chip.setTextColor(R.id.seg, if (isLight(ev.color)) DARK else Color.WHITE)
            chip.setBg(R.id.seg, ev.color)
            // 押すと、その予定を開く
            val view = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            chip.setOnClickPendingIntent(R.id.seg, PendingIntent.getActivity(context, 2, view, flags))
            row.addChild(R.id.lane, chip, n++)
            pos = seg.to + 1
        }
        if (pos < 7) row.addChild(R.id.lane, RemoteViews(context.packageName, GAP_LAYOUTS[7 - pos - 1]), n)
        return row
    }

    /** 入りきらない予定の件数を日ごとに出す段（lanes の from 段目から後ろにある予定の数）。 */
    private fun moreView(context: Context, lanes: List<List<Seg>>, from: Int, m: Metrics, notice: String?): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.lane)
        row.removeAllViews(R.id.lane)
        val counts = IntArray(7)
        for (li in from until lanes.size) for (seg in lanes[li]) for (d in seg.from..seg.to) counts[d]++
        for (d in 0 until 7) {
            val cell = RemoteViews(context.packageName, R.layout.seg_e1)
            cell.setTextViewTextSize(R.id.seg, TypedValue.COMPLEX_UNIT_DIP, m.unit)
            cell.setTextColor(R.id.seg, GRAY)
            cell.setInt(R.id.seg, "setBackgroundResource", 0)
            cell.setTextViewText(R.id.seg, if (notice != null && d == 0) notice else if (counts[d] > 0) "+${counts[d]}" else "")
            row.addChild(R.id.lane, cell, d)
        }
        return row
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
     * 文字の大きさの決め方。1 単位＝文字の大きさ（dp）。
     * ウィジェットの高さに日付の行と予定2段が入る大きさを基本にし、設定の％を掛ける
     * （幅には合わせない：予定の文字は右が切れてよい）。予定の段の高さは文字の大きさに合わせ、
     * 高さに入る段の数だけ出す。大きさは dp で指定するので、端末の文字サイズ設定には左右されない。
     */
    class Metrics(val unit: Float, val density: Float, val lanes: Int) {
        fun dp(dp: Float): Int = (dp * density).toInt()
    }

    private fun metrics(manager: AppWidgetManager, id: Int, settings: Settings, context: Context): Metrics {
        val options = manager.getAppWidgetOptions(id)
        // 縦向きのときの高さ（dp）
        // ホーム画面は上下に余白を取ることがあるので、使える高さは報告された高さより少なく見る
        val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 70) - 14
        val base = ((height - PADDING_DP * 2 - 7f) / (HEAD_FACTOR + 2 * CHIP_FACTOR)).coerceIn(8f, 20f)
        val unit = base * settings.textScale / 100f
        val lanes = ((height - PADDING_DP * 2 - unit * HEAD_FACTOR) / (unit * CHIP_FACTOR + 3f)).toInt().coerceAtLeast(1)
        return Metrics(unit, context.resources.displayMetrics.density, lanes)
    }

    /** 日付の行・予定1段の高さ（文字の大きさの何倍か。余白を含む）。 */
    private const val HEAD_FACTOR = 1.5f
    private const val CHIP_FACTOR = 1.3f
}
