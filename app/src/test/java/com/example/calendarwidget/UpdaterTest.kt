package com.example.calendarwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun 新しい版かどうかを数字ごとに比べる() {
        assertTrue(Updater.isNewer("1.25", "1.24"))
        assertTrue(Updater.isNewer("1.10", "1.9"))
        assertTrue(Updater.isNewer("2.0", "1.99"))
        assertTrue(Updater.isNewer("1.25.1", "1.25"))
        assertFalse(Updater.isNewer("1.25", "1.25"))
        assertFalse(Updater.isNewer("1.9", "1.10"))
    }
}
