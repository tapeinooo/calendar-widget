package com.example.calendarwidget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 予定・日付を定期的に描き直す（予定の変更・日付の変わり目の知らせを取りこぼしたときの保険。通常はその知らせで描き直すので、間隔は長くてよい）。 */
class CalendarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // タスクの取得（通信）はここだけで行い、描き直しは保存した結果を使う
        Tasks.refresh(applicationContext)
        WidgetRenderer.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "calendar_periodic"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CalendarWorker>(3, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
