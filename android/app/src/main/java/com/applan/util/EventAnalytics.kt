package com.applan.util

data class PolicyEvent(
    val id: String,
    val type: String,
    val occurredAt: Long,
    val packageName: String?,
    val durationMinutes: Int = 0,
    val durationSeconds: Int = 0,
    val planId: String? = null
)

data class RankedApp(val packageName: String, val count: Int)

data class DashboardSummary(
    val blockedCount: Int,
    val focusMinutes: Int,
    val topApps: List<RankedApp>,
    val planStartedCount: Int = 0,
    val planEndedEarlyCount: Int = 0,
    val planExpiredCount: Int = 0
)

object EventAnalytics {
    const val MAX_DAILY_TEMPORARY_PASSES = 5

    fun temporaryPassCount(events: List<PolicyEvent>, startOfDay: Long, endOfDay: Long): Int =
        events.count { it.type == "temporary_pass_granted" && it.occurredAt in startOfDay until endOfDay }

    fun remainingTemporaryPasses(events: List<PolicyEvent>, startOfDay: Long, endOfDay: Long): Int =
        (MAX_DAILY_TEMPORARY_PASSES - temporaryPassCount(events, startOfDay, endOfDay)).coerceAtLeast(0)

    fun summarize(events: List<PolicyEvent>, startOfDay: Long, endOfDay: Long): DashboardSummary {
        val today = events.filter { it.occurredAt in startOfDay until endOfDay }
        val blocked = today.filter { it.type == "app_blocked" }
        return DashboardSummary(
            blockedCount = blocked.size,
            focusMinutes = today.filter { it.type == "plan_app_usage" }.sumOf { it.durationSeconds } / 60,
            topApps = blocked.mapNotNull { it.packageName }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { RankedApp(it.key, it.value) },
            planStartedCount = today.count { it.type == "plan_started" },
            planEndedEarlyCount = today.count { it.type == "plan_ended_early" },
            planExpiredCount = today.count { it.type == "plan_expired" }
        )
    }
}
