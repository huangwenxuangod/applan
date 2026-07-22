package com.applan.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EventAnalyticsTest {
    @Test
    fun `today summary counts blocks and ranks blocked apps`() {
        val day = 1_700_000_000_000L
        val summary = EventAnalytics.summarize(
            listOf(
                PolicyEvent("1", "app_blocked", day, "com.ss.android.ugc.aweme"),
                PolicyEvent("2", "app_blocked", day + 1, "com.ss.android.ugc.aweme"),
                PolicyEvent("3", "app_blocked", day + 2, "com.xingin.xhs"),
                PolicyEvent("4", "plan_granted", day + 3, null, durationMinutes = 25)
            ),
            startOfDay = day - 100,
            endOfDay = day + 86_400_000
        )

        assertEquals(3, summary.blockedCount)
        assertEquals(25, summary.focusMinutes)
        assertEquals("com.ss.android.ugc.aweme", summary.topApps.first().packageName)
        assertEquals(2, summary.topApps.first().count)
    }
}
