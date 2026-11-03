package com.example.calendarwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日付が変わったら描き直すための、0時の目覚まし（1日1回）。
 * 日付の変わり目の知らせ（DATE_CHANGED）は今の Android ではアプリに届かず、定期処理も夜は省電力で遅れるため、
 * 正確な目覚ましを使う（遅れのある目覚ましだと、昼に掛けたものが朝まで遅れることがある）。
 */
object MidnightAlarm {
    const val ACTION = "com.example.calendarwidget.MIDNIGHT"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 100,
            Intent(context, CalendarWidgetReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 次の0時（日付が確実に変わっているよう30秒後）に掛ける。同じものを掛け直すので重ならない。 */
    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 30_000L
        val pi = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        try {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }
}
