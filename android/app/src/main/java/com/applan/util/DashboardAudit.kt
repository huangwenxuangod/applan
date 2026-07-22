package com.applan.util

data class DashboardAuditSnapshot(
    val blockedCount: Int,
    val temporaryPassCount: Int,
    val planStartedCount: Int,
    val planEndedEarlyCount: Int,
    val planExpiredCount: Int,
    val topApps: List<RankedApp>
)

object DashboardAudit {
    fun localSnapshot(events: List<PolicyEvent>, start: Long, end: Long): DashboardAuditSnapshot {
        val summary = EventAnalytics.summarize(events, start, end)
        return DashboardAuditSnapshot(
            blockedCount = summary.blockedCount,
            temporaryPassCount = EventAnalytics.temporaryPassCount(events, start, end),
            planStartedCount = summary.planStartedCount,
            planEndedEarlyCount = summary.planEndedEarlyCount,
            planExpiredCount = summary.planExpiredCount,
            topApps = summary.topApps
        )
    }

    fun isAligned(events: List<PolicyEvent>, start: Long, end: Long, remote: DashboardAuditSnapshot): Boolean =
        localSnapshot(events, start, end) == remote
}
