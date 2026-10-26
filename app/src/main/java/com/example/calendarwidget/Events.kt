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

class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

object Events {
    /**
     * Google カレンダーで「予定の色」を選んだとき、端末の予定データには昔の配色で入っている。
     * Google カレンダーアプリは同じ色の名前を新しい配色で出すので、それに合わせる。
     */
    private val GOOGLE_EVENT_COLORS = mapOf(
        "1" to 0x7986CB, "2" to 0x33B679, "3" to 0x8E24AA, "4" to 0xE67C73, "5" to 0xF6BF26, "6" to 0xF4511E,
        "7" to 0x5398CF, "8" to 0x616161, "9" to 0x3F51B5, "10" to 0x0B8043, "11" to 0xD50000,
    ).mapValues { it.value or (0xFF shl 24) }

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
                ),
                "${CalendarContract.Calendars.VISIBLE}=1", null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { c ->
                while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3))
            }
        }
        return out
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
                    CalendarContract.Instances.EVENT_ID,
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
                    val base = c.getInt(4).takeIf { it != 0 } ?: c.getInt(5)
                    val color = GOOGLE_EVENT_COLORS[c.getString(8)] ?: modernColor(base)
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "（タイトルなし）"
                    result += Ev(c.getLong(9), title, color, allDay, b, e, startDate, lastDate)
                }
            }
        }
        result += Tasks.load(context, from, to)
        return result
    }

    /**
     * 端末の予定データのカレンダーの色は昔の配色（明るい水色など）で入っている。
     * Google カレンダーアプリは近い色を自分の配色（暗い画面向けに少し落ち着かせたもの）で出すので、
     * 色相がいちばん近いものに置き換える。
     */
    private val MODERN_PALETTE = intArrayOf(
        0xD50000, 0xE67C73, 0xF4511E, 0xF6BF26, 0x33B679, 0x0B8043, 0x5398CF, 0x3F51B5, 0x7986CB, 0x8E24AA,
    ).map { it or (0xFF shl 24) }.toIntArray()
    private val GRAPHITE = 0xFF616161.toInt()

    fun modernColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.12f) return GRAPHITE
        val target = hsv[0]
        return MODERN_PALETTE.minByOrNull { p ->
            val ph = FloatArray(3)
            android.graphics.Color.colorToHSV(p, ph)
            val d = Math.abs(ph[0] - target)
            minOf(d, 360f - d)
        } ?: color
    }
}
