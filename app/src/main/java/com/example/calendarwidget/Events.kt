package com.example.calendarwidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 予定1件。startDate〜lastDate は表示する日の範囲（終日・複数日の予定もこの範囲で表す）。 */
class Ev(
    val id: Long, val title: String, val color: Int, val allDay: Boolean,
    val begin: Long, val end: Long, val startDate: LocalDate, val lastDate: LocalDate,
)

class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int, val colorKey: String?, val accountType: String)

object Events {
    /**
     * Google カレンダーアプリ（暗い画面）で実際に出ている24色。アプリの色の選択画面から測った値。
     * 端末のデータの色は昔の配色で入っているので、色番号からこの色に置き換える。
     */
    private const val WINE = 0xC45B7C; private const val ROSE = 0xDA5B79; private const val FLAMINGO = 0xD4847B
    private const val TOMATO = 0xDF573D; private const val TANGERINE = 0xE26B43; private const val PUMPKIN = 0xDB793A
    private const val MANGO = 0xDE9641; private const val BANANA = 0xE6BB55; private const val CITRON = 0xD8BE61
    private const val AVOCADO = 0xBCC15A; private const val PISTACHIO = 0x86AD5C; private const val BASIL = 0x4E9364
    private const val SAGE = 0x59AF80; private const val EUCALYPTUS = 0x479B8E; private const val PEACOCK = 0x5299CF
    private const val COBALT = 0x6B8CDD; private const val LAVENDER = 0x828CC1; private const val BLUEBERRY = 0x7577C4
    private const val LILAC = 0xAE9CCC; private const val AMETHYST = 0xA47CB0; private const val GRAPE = 0xAB60BB
    private const val COCOA = 0x99776B; private const val GRAPHITE = 0x8A8784; private const val BIRCH = 0xA5998B

    private val PALETTE = intArrayOf(
        WINE, ROSE, FLAMINGO, TOMATO, TANGERINE, PUMPKIN, MANGO, BANANA, CITRON, AVOCADO, PISTACHIO, BASIL,
        SAGE, EUCALYPTUS, PEACOCK, COBALT, LAVENDER, BLUEBERRY, LILAC, AMETHYST, GRAPE, COCOA, GRAPHITE, BIRCH,
    ).map { it or (0xFF shl 24) }

    /** 予定の色（番号 1〜11。Google の決まった11色）。 */
    private val EVENT_COLORS = mapOf(
        "1" to LAVENDER, "2" to SAGE, "3" to GRAPE, "4" to FLAMINGO, "5" to BANANA, "6" to TANGERINE,
        "7" to PEACOCK, "8" to GRAPHITE, "9" to BLUEBERRY, "10" to BASIL, "11" to TOMATO,
    ).mapValues { it.value or (0xFF shl 24) }

    /** カレンダーの色（番号 1〜24）。14・8 はアプリの表示と照らして確認済み、ほかは色の名前の順から。 */
    private val CALENDAR_COLORS = mapOf(
        "1" to COCOA, "2" to FLAMINGO, "3" to TOMATO, "4" to TANGERINE, "5" to PUMPKIN, "6" to MANGO,
        "7" to BASIL, "8" to EUCALYPTUS, "9" to PISTACHIO, "10" to AVOCADO, "11" to CITRON, "12" to BANANA,
        "13" to SAGE, "14" to PEACOCK, "15" to COBALT, "16" to BLUEBERRY, "17" to LAVENDER, "18" to LILAC,
        "19" to GRAPHITE, "20" to BIRCH, "21" to WINE, "22" to ROSE, "23" to GRAPE, "24" to AMETHYST,
    ).mapValues { it.value or (0xFF shl 24) }

    /** 予定に出す色：予定の色の番号 → 予定の色（番号なし）→ カレンダーの色の番号 → カレンダーの色、の順に決める。 */
    fun displayColor(eventKey: String?, eventColor: Int, calKey: String?, calColor: Int): Int =
        EVENT_COLORS[eventKey]
            ?: eventColor.takeIf { it != 0 }?.let { nearest(it) }
            ?: CALENDAR_COLORS[calKey]
            ?: nearest(calColor)

    /** 番号の分からない色は、24色のうち色相がいちばん近いものにする（色の薄いものはグラファイト）。 */
    private fun nearest(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.12f) return GRAPHITE or (0xFF shl 24)
        return PALETTE.minByOrNull { p ->
            val ph = FloatArray(3)
            android.graphics.Color.colorToHSV(p, ph)
            val d = Math.abs(ph[0] - hsv[0])
            minOf(d, 360f - d) + if (ph[1] < 0.12f) 360f else 0f
        } ?: color
    }

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 端末で表示になっているカレンダーの一覧。 */
    fun calendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<CalendarInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.CALENDAR_COLOR_KEY, CalendarContract.Calendars.ACCOUNT_TYPE,
                ),
                "${CalendarContract.Calendars.VISIBLE}=1", null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { c ->
                while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4), c.getString(5) ?: "")
            }
        }
        return out
    }

    /**
     * アカウントの色の対応表（色番号 → 色）。type は 0＝カレンダーの色、1＝予定の色。
     * Google の同期が入れている表で、色の確かめ用に設定画面に出す。
     */
    fun colorTable(context: Context, account: String, accountType: String, type: Int): List<Pair<String, Int>> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Colors.CONTENT_URI,
                arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR),
                "${CalendarContract.Colors.ACCOUNT_NAME}=? AND ${CalendarContract.Colors.ACCOUNT_TYPE}=? AND ${CalendarContract.Colors.COLOR_TYPE}=?",
                arrayOf(account, accountType, type.toString()), null,
            )?.use { c -> while (c.moveToNext()) out += (c.getString(0) ?: "") to c.getInt(1) }
        }
        return out.sortedBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** 色の確かめ用：from〜to の予定ごとの色の情報（予定名・予定の色・色番号・表示色・カレンダーの色番号）。 */
    class ColorDebug(val title: String, val eventColor: Int, val eventKey: String?, val displayColor: Int?, val calColor: Int, val calKey: String?)

    fun colorDebug(context: Context, from: LocalDate, to: LocalDate): List<ColorDebug> {
        if (!hasPermission(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from.atStartOfDay(zone).toInstant().toEpochMilli())
            android.content.ContentUris.appendId(it, to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }.build()
        val base = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_COLOR, CalendarContract.Instances.EVENT_COLOR_KEY,
            CalendarContract.Instances.CALENDAR_COLOR, CalendarContract.Instances.CALENDAR_COLOR_KEY,
        )
        fun read(projection: Array<String>): List<ColorDebug> {
            val out = ArrayList<ColorDebug>()
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE}=1", null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) out += ColorDebug(
                    c.getString(0) ?: "", c.getInt(1), c.getString(2),
                    if (projection.size > 5) c.getInt(5) else null, c.getInt(3), c.getString(4),
                )
            }
            return out
        }
        // displayColor（予定の色があればそれ、なければカレンダーの色）は端末によってはないので、なければ外して読む
        return runCatching { read(base + CalendarContract.Instances.DISPLAY_COLOR) }.getOrElse { runCatching { read(base) }.getOrDefault(emptyList()) }
    }

    /** from〜to（含む）にかかる予定。権限がなければ空。 */
    fun load(context: Context, from: LocalDate, to: LocalDate, hidden: Set<Long>): List<Ev> {
        if (!hasPermission(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        // 終日の予定は UTC の0時で入っているので、前後に1日ずつ広げて取る
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, begin)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val result = ArrayList<Ev>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_COLOR,
                    CalendarContract.Instances.CALENDAR_COLOR, CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.EVENT_COLOR_KEY,
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_COLOR_KEY,
                ),
                "${CalendarContract.Instances.VISIBLE}=1", null, CalendarContract.Instances.BEGIN,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(6) in hidden) continue
                    if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = c.getInt(3) == 1
                    val b = c.getLong(1)
                    val e = c.getLong(2)
                    val startDate: LocalDate
                    val lastDate: LocalDate
                    if (allDay) {
                        startDate = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                        lastDate = Instant.ofEpochMilli(e - 1).atZone(ZoneOffset.UTC).toLocalDate().let { if (it < startDate) startDate else it }
                    } else {
                        startDate = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
                        lastDate = Instant.ofEpochMilli(maxOf(e - 1, b)).atZone(zone).toLocalDate()
                    }
                    if (lastDate < from || startDate > to) continue
                    val color = displayColor(c.getString(8), c.getInt(4), c.getString(10), c.getInt(5))
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "（タイトルなし）"
                    result += Ev(c.getLong(9), title, color, allDay, b, e, startDate, lastDate)
                }
            }
        }
        result += Tasks.load(context, from, to, LocalDate.now())
        return result
    }
}
