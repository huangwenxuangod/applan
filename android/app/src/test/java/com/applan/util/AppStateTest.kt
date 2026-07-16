package com.applan.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppStateTest {

    @Before
    fun setUp() {
        AppState.resetGrant()
        AppState.touch()
    }

    private fun makePlan(
        packages: Set<String> = setOf("com.tencent.mm", "com.android.chrome"),
        appNames: List<String> = listOf("微信", "Chrome"),
        description: String = "测试计划",
        timeoutMinutes: Int = 10
    ): AccessPlan {
        return AccessPlan(
            allowedPackages = packages.toMutableSet(),
            allowedAppNames = appNames.toMutableList(),
            planDescription = description,
            timeoutAt = System.currentTimeMillis() + timeoutMinutes * 60 * 1000L
        )
    }

    @Test
    fun `initial state is guarding - blocks everything`() {
        assertTrue(AppState.shouldBlock())
        assertFalse(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantedByEmergency())
        assertNull(AppState.getCurrentPlan())
        assertFalse(AppState.isGrantExpired())
    }

    @Test
    fun `grantByAi gives full access for 15 minutes`() {
        AppState.grantByAi()
        assertFalse(AppState.shouldBlock())
        assertTrue(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantedByEmergency())
        assertFalse(AppState.isGrantExpired())
    }

    @Test
    fun `grantByEmergency gives full access for 60 seconds`() {
        AppState.grantByEmergency()
        assertFalse(AppState.shouldBlock())
        assertTrue(AppState.isGrantedByEmergency())
        assertFalse(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantExpired())
    }

    @Test
    fun `grantByPlan sets plan mode with whitelist`() {
        val plan = makePlan()
        AppState.grantByPlan(plan)
        assertFalse(AppState.shouldBlock())
        assertTrue(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByEmergency())
        assertNotNull(AppState.getCurrentPlan())
        assertEquals("测试计划", AppState.getCurrentPlan()?.planDescription)
        assertFalse(AppState.isGrantExpired())
    }

    @Test
    fun `resetGrant clears all grants and returns to guarding`() {
        AppState.grantByAi()
        assertFalse(AppState.shouldBlock())
        AppState.resetGrant()
        assertTrue(AppState.shouldBlock())
        assertFalse(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantedByEmergency())
        assertNull(AppState.getCurrentPlan())
    }

    @Test
    fun `resetGrant after plan mode clears plan`() {
        AppState.grantByPlan(makePlan())
        assertTrue(AppState.isGrantedByPlan())
        AppState.resetGrant()
        assertFalse(AppState.isGrantedByPlan())
        assertNull(AppState.getCurrentPlan())
        assertTrue(AppState.shouldBlock())
    }

    @Test
    fun `plan mode allows whitelisted packages`() {
        AppState.grantByPlan(makePlan())
        assertTrue(AppState.isPackageAllowed("com.tencent.mm"))
        assertTrue(AppState.isPackageAllowed("com.android.chrome"))
    }

    @Test
    fun `plan mode blocks non-whitelisted packages`() {
        AppState.grantByPlan(makePlan())
        assertFalse(AppState.isPackageAllowed("com.ss.android.ugc.aweme"))
        assertFalse(AppState.isPackageAllowed("com.android.settings"))
        assertFalse(AppState.isPackageAllowed("com.smile.gifmaker"))
    }

    @Test
    fun `plan mode marks visited packages and last foreground`() {
        val plan = makePlan()
        AppState.grantByPlan(plan)
        assertTrue(AppState.isPackageAllowed("com.tencent.mm"))
        assertTrue(plan.visitedPackages.contains("com.tencent.mm"))
        assertEquals("com.tencent.mm", plan.lastForegroundPackage)

        assertTrue(AppState.isPackageAllowed("com.android.chrome"))
        assertEquals("com.android.chrome", plan.lastForegroundPackage)
        assertTrue(plan.visitedPackages.size == 2)
    }

    @Test
    fun `isPackageAllowed returns false when not in plan mode`() {
        assertFalse(AppState.isPackageAllowed("com.tencent.mm"))
        AppState.grantByAi()
        assertFalse(AppState.isPackageAllowed("com.tencent.mm"))
        AppState.resetGrant()
        AppState.grantByEmergency()
        assertFalse(AppState.isPackageAllowed("com.tencent.mm"))
    }

    @Test
    fun `grantByPlan overrides previous grant states - mutual exclusion`() {
        AppState.grantByAi()
        assertTrue(AppState.isGrantedByAi())

        val plan = makePlan()
        AppState.grantByPlan(plan)
        assertTrue(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByEmergency())
    }

    @Test
    fun `grantByAi overrides previous plan`() {
        AppState.grantByPlan(makePlan())
        assertTrue(AppState.isGrantedByPlan())

        AppState.grantByAi()
        assertTrue(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByPlan())
        assertNull(AppState.getCurrentPlan())
    }

    @Test
    fun `appendAllowedPackages adds new packages to existing plan`() {
        val plan = makePlan()
        AppState.grantByPlan(plan)
        assertTrue(AppState.isPackageAllowed("com.tencent.mm"))
        assertFalse(AppState.isPackageAllowed("com.android.memo"))

        AppState.appendAllowedPackages(
            packages = setOf("com.android.memo"),
            appNames = listOf("备忘录"),
            reason = "合理偏离"
        )

        assertTrue(AppState.isPackageAllowed("com.android.memo"))
        assertTrue(plan.allowedPackages.contains("com.android.memo"))
        assertTrue(plan.allowedAppNames.contains("备忘录"))
    }

    @Test
    fun `appendAllowedPackages resolves last unresolved violation`() {
        val plan = makePlan()
        AppState.grantByPlan(plan)

        val violation = ViolationRecord(packageName = "com.android.memo", appName = "备忘录")
        plan.violations.add(violation)
        assertFalse(violation.resolved)

        AppState.appendAllowedPackages(
            packages = setOf("com.android.memo"),
            appNames = listOf("备忘录"),
            reason = "需要备忘录"
        )

        assertTrue(violation.resolved)
    }

    @Test
    fun `appendAllowedPackages only resolves last violation, not older ones`() {
        val plan = makePlan()
        AppState.grantByPlan(plan)

        val oldViolation = ViolationRecord(packageName = "com.ss.android.ugc.aweme", appName = "抖音")
        plan.violations.add(oldViolation)

        val newViolation = ViolationRecord(packageName = "com.android.memo", appName = "备忘录")
        plan.violations.add(newViolation)

        AppState.appendAllowedPackages(
            packages = setOf("com.android.memo"),
            appNames = listOf("备忘录"),
            reason = "需要备忘录"
        )

        assertFalse(oldViolation.resolved)
        assertTrue(newViolation.resolved)
    }

    @Test
    fun `expired plan is detected and shouldBlock returns true`() {
        val expiredPlan = AccessPlan(
            allowedPackages = mutableSetOf("com.tencent.mm"),
            allowedAppNames = mutableListOf("微信"),
            planDescription = "过期计划",
            timeoutAt = System.currentTimeMillis() - 1000
        )
        AppState.grantByPlan(expiredPlan)
        assertTrue(AppState.isGrantExpired())
        assertTrue(AppState.shouldBlock())
        assertFalse(AppState.isGrantedByPlan())
    }

    @Test
    fun `fresh plan is not expired`() {
        AppState.grantByPlan(makePlan(timeoutMinutes = 10))
        assertFalse(AppState.isGrantExpired())
        assertFalse(AppState.shouldBlock())
    }

    @Test
    fun `emergency grant expires after 60 seconds`() {
        AppState.grantByEmergency()
        assertFalse(AppState.isGrantExpired())
        assertFalse(AppState.shouldBlock())
        assertTrue(AppState.isGrantedByEmergency())
    }

    @Test
    fun `expired emergency grant is detected by shouldBlock`() {
        AppState.grantByEmergency()
        assertFalse(AppState.shouldBlock())

        val field = AppState.javaClass.getDeclaredField("grantTimeoutAt")
        field.isAccessible = true
        field.setLong(null, System.currentTimeMillis() - 1000)

        assertTrue(AppState.isGrantExpired())
        assertTrue(AppState.shouldBlock())
        assertFalse(AppState.isGrantedByEmergency())
    }

    @Test
    fun `settings grace period allows access for 120 seconds`() {
        assertFalse(AppState.isInSettingsGracePeriod())
        AppState.startSettingsSession()
        assertTrue(AppState.isInSettingsGracePeriod())
        assertFalse(AppState.shouldBlock())
        AppState.endSettingsSession()
        assertFalse(AppState.isInSettingsGracePeriod())
        assertTrue(AppState.shouldBlock())
    }

    @Test
    fun `isGrantExpired returns false when timeout is zero (no timeout set)`() {
        assertFalse(AppState.isGrantExpired())
    }

    @Test
    fun `onViolationDetected callback is invoked`() {
        var capturedRecord: ViolationRecord? = null
        AppState.onViolationDetected = { record -> capturedRecord = record }
        val record = ViolationRecord(packageName = "com.test", appName = "Test")
        AppState.onViolationDetected?.invoke(record)
        assertNotNull(capturedRecord)
        assertEquals("com.test", capturedRecord?.packageName)
        assertEquals("Test", capturedRecord?.appName)
    }

    @Test
    fun `touch updates lastInteractionTime`() {
        val before = AppState.lastInteractionTime
        Thread.sleep(10)
        AppState.touch()
        assertTrue(AppState.lastInteractionTime > before)
    }

    @Test
    fun `startSettingsSession resets existing plan and ai grant`() {
        AppState.grantByPlan(makePlan())
        assertTrue(AppState.isGrantedByPlan())
        AppState.startSettingsSession()
        assertFalse(AppState.isGrantedByPlan())
        assertNull(AppState.getCurrentPlan())
        assertFalse(AppState.isGrantedByAi())
    }

    @Test
    fun `startSettingsSession clears emergency grant`() {
        AppState.grantByEmergency()
        assertTrue(AppState.isGrantedByEmergency())
        AppState.startSettingsSession()
        assertFalse(AppState.isGrantedByEmergency())
        assertFalse(AppState.isGrantedByAi())
        assertFalse(AppState.isGrantedByPlan())
        assertFalse(AppState.isGrantExpired())
        assertTrue(AppState.isInSettingsGracePeriod())
    }

    @Test
    fun `multiple violations are tracked in plan`() {
        val plan = makePlan()
        AppState.grantByPlan(plan)
        val v1 = ViolationRecord(packageName = "com.douyin", appName = "抖音")
        val v2 = ViolationRecord(packageName = "com.kuaishou", appName = "快手")
        plan.violations.add(v1)
        plan.violations.add(v2)
        assertEquals(2, plan.violations.size)
    }
}
