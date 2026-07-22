package com.applan.util

data class PolicyEvent(
    val id: String,
    val type: String,
    val occurredAt: Long,
    val packageName: String?,
    val durationMinutes: Int = 0
)

data class RankedApp(val packageName: String, val count: Int)

data class DashboardSummary(
    val blockedCount: Int,
    val focusMinutes: Int,
    val topApps: List<RankedApp>
)

object EventAnalytics {
    fun summarize(events: List<PolicyEvent>, startOfDay: Long, endOfDay: Long): DashboardSummary {
        val today = events.filter { it.occurredAt in startOfDay until endOfDay }
        val blocked = today.filter { it.type == "app_blocked" }
        return DashboardSummary(
            blockedCount = blocked.size,
            focusMinutes = today.filter { it.type == "plan_granted" }.sumOf { it.durationMinutes },
            topApps = blocked.mapNotNull { it.packageName }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { RankedApp(it.key, it.value) }
        )
    }
}
