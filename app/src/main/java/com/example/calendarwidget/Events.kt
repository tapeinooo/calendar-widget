package com.example.calendarwidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 1日ぶんの予定の1件。 */
class Ev(val title: String, val color: Int, val allDay: Boolean, val startMillis: Long)

class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

object Events {
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

    /** from〜to（含む）の日ごとの予定。終日を先に、あとは開始が早い順。権限がなければ空。 */
    fun load(context: Context, from: LocalDate, to: LocalDate, hidden: Set<Long>): Map<LocalDate, List<Ev>> {
        if (!hasPermission(context)) return emptyMap()
        val zone = ZoneId.systemDefault()
        // 終日の予定は UTC の0時で入っているので、前後に1日ずつ広げて取る
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, begin)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val result = HashMap<LocalDate, MutableList<Ev>>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_COLOR,
                    CalendarContract.Instances.CALENDAR_COLOR, CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
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
                    val color = c.getInt(4).takeIf { it != 0 } ?: c.getInt(5)
                    val ev = Ev(c.getString(0)?.takeIf { it.isNotBlank() } ?: "（タイトルなし）", color or (0xFF shl 24), allDay, b)
                    var d = maxOf(startDate, from)
                    val stop = minOf(lastDate, to)
                    while (d <= stop) {
                        result.getOrPut(d) { ArrayList() } += ev
                        d = d.plusDays(1)
                    }
                }
            }
        }
        return result.mapValues { (_, list) -> list.sortedWith(compareBy<Ev>({ !it.allDay }, { it.startMillis })) }
    }
}
