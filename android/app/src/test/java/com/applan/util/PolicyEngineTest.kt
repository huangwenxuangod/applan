package com.applan.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PolicyEngineTest {

    @Test
    fun `weekday profile is active inside its time window`() {
        val policy = PolicyEngine.evaluate(
            profiles = listOf(TimeProfile("work", setOf(1), 9 * 60, 12 * 60, setOf("com.tencent.wework"))),
            plan = null,
            now = at(Calendar.MONDAY, 10, 0)
        )

        assertTrue(policy.isActive)
        assertEquals(setOf("com.tencent.wework"), policy.allowedPackages)
    }

    @Test
    fun `overnight profile remains active after midnight`() {
        val policy = PolicyEngine.evaluate(
            profiles = listOf(TimeProfile("sleep", setOf(5), 22 * 60, 7 * 60, emptySet())),
            plan = null,
            now = at(Calendar.SATURDAY, 2, 0)
        )

        assertTrue(policy.isActive)
        assertTrue(policy.isGlobalBlock)
    }

    @Test
    fun `expired plan does not activate policy`() {
        val now = at(Calendar.MONDAY, 10, 0)
        val policy = PolicyEngine.evaluate(
            profiles = emptyList(),
            plan = AiPlan(setOf("com.tencent.mm"), "reply", now.timeInMillis - 1),
            now = now
        )

        assertFalse(policy.isActive)
    }

    @Test
    fun `plan cannot expand active profile allowlist`() {
        val now = at(Calendar.MONDAY, 10, 0)
        val policy = PolicyEngine.evaluate(
            profiles = listOf(TimeProfile("work", setOf(1), 9 * 60, 12 * 60, setOf("com.tencent.wework"))),
            plan = AiPlan(setOf("com.tencent.wework", "com.tencent.mm"), "reply", now.timeInMillis + 15 * 60_000),
            now = now
        )

        assertTrue(policy.isPlanActive)
        assertEquals(setOf("com.tencent.wework"), policy.allowedPackages)
    }

    @Test
    fun `daily profile repeats on every weekday`() {
        val profile = TimeProfile("daily", (1..7).toSet(), 9 * 60, 17 * 60, emptySet())

        assertTrue(PolicyEngine.evaluate(listOf(profile), null, at(Calendar.MONDAY, 10, 0)).isActive)
        assertTrue(PolicyEngine.evaluate(listOf(profile), null, at(Calendar.SUNDAY, 10, 0)).isActive)
        assertFalse(PolicyEngine.evaluate(listOf(profile), null, at(Calendar.SUNDAY, 18, 0)).isActive)
    }

    private fun at(day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, 20 + (day - Calendar.MONDAY), hour, minute)
        }
}
