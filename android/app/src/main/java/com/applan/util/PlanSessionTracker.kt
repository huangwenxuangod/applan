package com.applan.util

data class PlanUsage(val planId: String, val packageName: String, val durationSeconds: Int)

class PlanSessionTracker {
    private var planId: String? = null
    private var packageName: String? = null
    private var startedAt: Long = 0

    fun onForeground(pkg: String, activePlanId: String?, allowed: Set<String>, now: Long = System.currentTimeMillis()): List<PlanUsage> {
        val flushed = if (pkg != packageName || activePlanId != planId) stop(now) else emptyList()
        if (activePlanId != null && pkg in allowed && packageName == null) {
            planId = activePlanId; packageName = pkg; startedAt = now
        }
        return flushed
    }

    fun stop(now: Long = System.currentTimeMillis()): List<PlanUsage> {
        val id = planId ?: return emptyList()
        val pkg = packageName ?: return emptyList()
        val seconds = ((now - startedAt).coerceAtLeast(0) / 1_000).toInt()
        planId = null; packageName = null; startedAt = 0
        return if (seconds > 0) listOf(PlanUsage(id, pkg, seconds)) else emptyList()
    }
}
