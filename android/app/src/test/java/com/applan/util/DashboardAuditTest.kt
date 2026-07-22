package com.applan.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAuditTest {
    private val start = 1_700_000_000_000L
    private val end = start + 86_400_000L

    @Test
    fun `matching local and remote audit summaries are aligned`() {
        val events = listOf(
            PolicyEvent("1", "app_blocked", start, "com.tencent.mm"),
            PolicyEvent("2", "temporary_pass_granted", start + 1, "com.tencent.mm"),
            PolicyEvent("3", "plan_started", start + 2, null),
            PolicyEvent("4", "plan_ended_early", start + 3, null),
            PolicyEvent("5", "plan_expired", start + 4, null)
        )
        val remote = DashboardAuditSnapshot(
            blockedCount = 1,
            temporaryPassCount = 1,
            planStartedCount = 1,
            planEndedEarlyCount = 1,
            planExpiredCount = 1,
            topApps = listOf(RankedApp("com.tencent.mm", 1))
        )

        assertTrue(DashboardAudit.isAligned(events, start, end, remote))
    }

    @Test
    fun `a remote count or ranking mismatch is not aligned`() {
        val events = listOf(PolicyEvent("1", "app_blocked", start, "com.tencent.mm"))
        val wrongCount = DashboardAuditSnapshot(2, 0, 0, 0, 0, listOf(RankedApp("com.tencent.mm", 2)))
        val wrongRank = DashboardAuditSnapshot(1, 0, 0, 0, 0, listOf(RankedApp("com.douyin", 1)))

        assertFalse(DashboardAudit.isAligned(events, start, end, wrongCount))
        assertFalse(DashboardAudit.isAligned(events, start, end, wrongRank))
    }
}
