package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class PowerCycleScheduleTest {

    private fun calendarAt(
        year: Int, month1Based: Int, day: Int, hour: Int, minute: Int,
    ): Calendar {
        val cal = GregorianCalendar(TimeZone.getTimeZone("Asia/Tokyo"))
        cal.clear()
        cal.set(year, month1Based - 1, day, hour, minute, 30)
        return cal
    }

    private fun assertTimePoint(
        point: PowerCycleSchedule.TimePoint,
        year: Int, month: Int, day: Int, hour: Int, minute: Int,
    ) {
        assertEquals(year, point.year)
        assertEquals(month, point.month)
        assertEquals(day, point.day)
        assertEquals(hour, point.hour)
        assertEquals(minute, point.minute)
    }

    @Test
    fun defaultDelaysAreTwoAndThreeMinutes() {
        val plan = PowerCycleSchedule.compute(calendarAt(2026, 7, 10, 20, 30))
        assertTimePoint(plan.shutdown, 2026, 7, 10, 20, 32)
        assertTimePoint(plan.startup, 2026, 7, 10, 20, 33)
    }

    @Test
    fun monthIsOneBased() {
        // January: Calendar.MONTH == 0, the PICO API expects 1.
        val january = PowerCycleSchedule.compute(calendarAt(2026, 1, 15, 12, 0))
        assertEquals(1, january.shutdown.month)
        // December: Calendar.MONTH == 11, the PICO API expects 12.
        val december = PowerCycleSchedule.compute(calendarAt(2026, 12, 15, 12, 0))
        assertEquals(12, december.shutdown.month)
    }

    @Test
    fun rollsOverAcrossTheHour() {
        val plan = PowerCycleSchedule.compute(calendarAt(2026, 7, 10, 20, 59))
        assertTimePoint(plan.shutdown, 2026, 7, 10, 21, 1)
        assertTimePoint(plan.startup, 2026, 7, 10, 21, 2)
    }

    @Test
    fun rollsOverAcrossMidnightAndMonthEnd() {
        val plan = PowerCycleSchedule.compute(calendarAt(2026, 1, 31, 23, 59))
        assertTimePoint(plan.shutdown, 2026, 2, 1, 0, 1)
        assertTimePoint(plan.startup, 2026, 2, 1, 0, 2)
    }

    @Test
    fun rollsOverAcrossTheYear() {
        val plan = PowerCycleSchedule.compute(calendarAt(2026, 12, 31, 23, 58))
        assertTimePoint(plan.shutdown, 2027, 1, 1, 0, 0)
        assertTimePoint(plan.startup, 2027, 1, 1, 0, 1)
    }

    @Test
    fun shutdownIsAlwaysBeforeStartup() {
        // Sweep a day in 7-minute steps; ordering must hold at every instant.
        var minuteOfDay = 0
        while (minuteOfDay < 24 * 60) {
            val now = calendarAt(2026, 6, 30, minuteOfDay / 60, minuteOfDay % 60)
            val plan = PowerCycleSchedule.compute(now)
            val s = plan.shutdown
            val u = plan.startup
            val shutdownKey = ((s.year * 100L + s.month) * 100 + s.day) * 10000 + s.hour * 100 + s.minute
            val startupKey = ((u.year * 100L + u.month) * 100 + u.day) * 10000 + u.hour * 100 + u.minute
            assertTrue("shutdown $s must precede startup $u", shutdownKey < startupKey)
            minuteOfDay += 7
        }
    }

    @Test
    fun customDelaysAreHonored() {
        val plan = PowerCycleSchedule.compute(
            calendarAt(2026, 7, 10, 10, 0),
            shutdownDelayMinutes = 5,
            startupDelayMinutes = 9,
        )
        assertTimePoint(plan.shutdown, 2026, 7, 10, 10, 5)
        assertTimePoint(plan.startup, 2026, 7, 10, 10, 9)
    }
}
