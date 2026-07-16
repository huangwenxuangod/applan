package com.applan.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccessPlanTest {

    private lateinit var plan: AccessPlan

    @Before
    fun setUp() {
        val futureTimeout = System.currentTimeMillis() + 10 * 60 * 1000L
        plan = AccessPlan(
            allowedPackages = mutableSetOf("com.tencent.mm", "com.android.chrome"),
            allowedAppNames = mutableListOf("微信", "Chrome"),
            planDescription = "微信看合同，浏览器查条款",
            timeoutAt = futureTimeout
        )
    }

    @Test
    fun `plan creation sets correct fields`() {
        assertEquals(2, plan.allowedPackages.size)
        assertTrue(plan.allowedPackages.contains("com.tencent.mm"))
        assertTrue(plan.allowedPackages.contains("com.android.chrome"))
        assertEquals("微信看合同，浏览器查条款", plan.planDescription)
        assertTrue(plan.visitedPackages.isEmpty())
        assertNull(plan.lastForegroundPackage)
        assertTrue(plan.violations.isEmpty())
    }

    @Test
    fun `visitedPackages tracks visited apps`() {
        plan.visitedPackages.add("com.tencent.mm")
        assertEquals(1, plan.visitedPackages.size)
        assertTrue(plan.visitedPackages.contains("com.tencent.mm"))
    }

    @Test
    fun `violation records are stored and resolved`() {
        val v1 = ViolationRecord(packageName = "com.ss.android.ugc.aweme", appName = "抖音")
        val v2 = ViolationRecord(packageName = "com.smile.gifmaker", appName = "快手")
        plan.violations.add(v1)
        plan.violations.add(v2)

        assertEquals(2, plan.violations.size)
        assertFalse(v1.resolved)
        assertFalse(v2.resolved)
        assertEquals("com.ss.android.ugc.aweme", v1.packageName)
        assertEquals("抖音", v1.appName)
        assertTrue(v1.timestamp > 0)

        v1.resolved = true
        assertTrue(v1.resolved)
        assertFalse(v2.resolved)
    }

    @Test
    fun `violation timestamp is set automatically`() {
        val before = System.currentTimeMillis()
        val v = ViolationRecord(packageName = "com.test", appName = "Test")
        val after = System.currentTimeMillis()
        assertTrue(v.timestamp in before..after)
    }

    @Test
    fun `plan timeout in future means not expired`() {
        val future = System.currentTimeMillis() + 60000
        val futurePlan = plan.copy(timeoutAt = future)
        assertFalse(System.currentTimeMillis() > futurePlan.timeoutAt)
    }

    @Test
    fun `plan timeout in past means expired`() {
        val past = System.currentTimeMillis() - 1000
        val pastPlan = plan.copy(timeoutAt = past)
        assertTrue(System.currentTimeMillis() > pastPlan.timeoutAt)
    }

    @Test
    fun `allowedPackages is mutable after creation`() {
        plan.allowedPackages.add("com.android.memo")
        assertEquals(3, plan.allowedPackages.size)
        assertTrue(plan.allowedPackages.contains("com.android.memo"))
    }

    @Test
    fun `allowedAppNames tracks corresponding display names`() {
        assertEquals(2, plan.allowedAppNames.size)
        assertEquals("微信", plan.allowedAppNames[0])
        assertEquals("Chrome", plan.allowedAppNames[1])
        plan.allowedAppNames.add("备忘录")
        assertEquals(3, plan.allowedAppNames.size)
    }

    @Test
    fun `lastForegroundPackage updates on navigation`() {
        assertNull(plan.lastForegroundPackage)
        plan.lastForegroundPackage = "com.tencent.mm"
        assertEquals("com.tencent.mm", plan.lastForegroundPackage)
        plan.lastForegroundPackage = "com.android.chrome"
        assertEquals("com.android.chrome", plan.lastForegroundPackage)
    }
}
