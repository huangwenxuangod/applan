package com.applan.util

import java.util.Calendar

data class TimeProfile(
    val id: String,
    val weekdays: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val allowedPackages: Set<String>
)

data class AiPlan(
    val allowedPackages: Set<String>,
    val purpose: String,
    val expiresAt: Long
)

data class TemporaryPass(
    val packageName: String,
    val expiresAt: Long
) {
    fun allows(packageName: String, now: Long = System.currentTimeMillis()): Boolean =
        this.packageName == packageName && expiresAt > now
}

data class EffectivePolicy(
    val isScheduled: Boolean,
    val isPlanActive: Boolean,
    val allowedPackages: Set<String>
) {
    val isActive: Boolean
        get() = isScheduled || isPlanActive

    val isGlobalBlock: Boolean
        get() = isScheduled && allowedPackages.isEmpty()
}

object BlockingDecision {
    fun isAllowed(
        packageName: String,
        policy: EffectivePolicy,
        exitGranted: Boolean,
        planModeEnabled: Boolean,
        temporaryPass: TemporaryPass?
    ): Boolean {
        if (exitGranted && !planModeEnabled) return true
        if (temporaryPass?.allows(packageName) == true) return true
        return !policy.isActive || packageName in policy.allowedPackages
    }
}

object BlockingCoordinator {
    enum class Decision { PASSIVE, ALLOWED, BLOCKED }

    fun evaluate(
        packageName: String?,
        policy: EffectivePolicy,
        exitGranted: Boolean,
        planModeEnabled: Boolean,
        temporaryPass: TemporaryPass?
    ): Decision {
        if (packageName == null) return Decision.PASSIVE
        if (exitGranted && !planModeEnabled) return Decision.PASSIVE
        if (temporaryPass?.allows(packageName) == true) return Decision.ALLOWED
        if (!policy.isActive) return Decision.PASSIVE
        return if (packageName in policy.allowedPackages) Decision.ALLOWED else Decision.BLOCKED
    }
}

object PolicyEngine {
    fun evaluate(
        profiles: List<TimeProfile>,
        plan: AiPlan?,
        now: Calendar = Calendar.getInstance()
    ): EffectivePolicy {
        val activeProfiles = profiles.filter { it.isActiveAt(now) }
        val activePlan = plan?.takeIf { it.expiresAt > now.timeInMillis }
        val scheduledPackages = activeProfiles
            .map { it.allowedPackages }
            .reduceOrNull { allowed, next -> allowed.intersect(next) }

        val allowedPackages = when {
            scheduledPackages != null && activePlan != null -> scheduledPackages.intersect(activePlan.allowedPackages)
            scheduledPackages != null -> scheduledPackages
            activePlan != null -> activePlan.allowedPackages
            else -> emptySet()
        }

        return EffectivePolicy(
            isScheduled = activeProfiles.isNotEmpty(),
            isPlanActive = activePlan != null,
            allowedPackages = allowedPackages
        )
    }

    private fun TimeProfile.isActiveAt(now: Calendar): Boolean {
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = now.get(Calendar.DAY_OF_WEEK).toMondayFirstDay()
        val previousDay = if (today == 1) 7 else today - 1

        return if (startMinute <= endMinute) {
            today in weekdays && minute in startMinute until endMinute
        } else {
            (today in weekdays && minute >= startMinute) ||
                (previousDay in weekdays && minute < endMinute)
        }
    }

    private fun Int.toMondayFirstDay(): Int = when (this) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 7
    }
}
