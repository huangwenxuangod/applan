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

    @Test
    fun `temporary pass quota counts only successful passes in the local day`() {
        val day = 1_700_000_000_000L
        val events = listOf(
            PolicyEvent("1", "temporary_pass_granted", day, "com.sankuai.meituan"),
            PolicyEvent("2", "temporary_pass_granted", day + 1, "com.tencent.mm"),
            PolicyEvent("3", "temporary_pass_granted", day + 2, "com.tencent.mm"),
            PolicyEvent("4", "temporary_pass_granted", day - 1, "com.eg.android.AlipayGphone")
        )

        assertEquals(3, EventAnalytics.temporaryPassCount(events, day, day + 86_400_000))
        assertEquals(2, EventAnalytics.remainingTemporaryPasses(events, day, day + 86_400_000))
    }
}
