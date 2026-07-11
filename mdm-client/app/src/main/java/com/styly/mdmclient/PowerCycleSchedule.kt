package com.styly.mdmclient

import java.util.Calendar

/**
 * Computes the absolute wall-clock times for the one-shot power cycle armed before a
 * self-update: nothing restarts the client after the installer kills its process
 * (measured on-device for #39), so the device itself is shut down and started back
 * up by system-side timers, and the new build recovers through the boot path.
 *
 * The PICO timing APIs (IToBServiceProxy.openTimingShutdown / openTimingStartup)
 * take an absolute (year, month, day, hour, minute) with a 1-based month, while
 * java.util.Calendar's MONTH is 0-based — exactly the off-by-one this type exists
 * to make testable. Free of Android imports so it runs under plain JUnit.
 */
object PowerCycleSchedule {

    /** Shutdown fires well after the ~30 s the silent install needs to commit. */
    const val SHUTDOWN_DELAY_MINUTES = 2

    /** Startup fires one minute after the shutdown. */
    const val STARTUP_DELAY_MINUTES = 3

    data class TimePoint(
        val year: Int,
        val month: Int,   // 1-based, as the PICO API expects
        val day: Int,
        val hour: Int,
        val minute: Int,
    ) {
        override fun toString(): String =
            "%04d-%02d-%02d %02d:%02d".format(year, month, day, hour, minute)
    }

    data class Plan(val shutdown: TimePoint, val startup: TimePoint)

    fun compute(
        now: Calendar,
        shutdownDelayMinutes: Int = SHUTDOWN_DELAY_MINUTES,
        startupDelayMinutes: Int = STARTUP_DELAY_MINUTES,
    ): Plan = Plan(
        shutdown = timePointAfter(now, shutdownDelayMinutes),
        startup = timePointAfter(now, startupDelayMinutes),
    )

    private fun timePointAfter(now: Calendar, minutes: Int): TimePoint {
        val at = now.clone() as Calendar
        at.add(Calendar.MINUTE, minutes)
        return TimePoint(
            year = at.get(Calendar.YEAR),
            month = at.get(Calendar.MONTH) + 1,
            day = at.get(Calendar.DAY_OF_MONTH),
            hour = at.get(Calendar.HOUR_OF_DAY),
            minute = at.get(Calendar.MINUTE),
        )
    }
}
