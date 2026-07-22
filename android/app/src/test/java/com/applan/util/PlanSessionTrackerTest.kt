package com.applan.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanSessionTrackerTest {
    @Test fun `switching allowed apps closes the prior segment`() {
        val tracker = PlanSessionTracker()
        assertEquals(emptyList<PlanUsage>(), tracker.onForeground("a", "plan", setOf("a", "b"), 1_000))
        assertEquals(listOf(PlanUsage("plan", "a", 60)), tracker.onForeground("b", "plan", setOf("a", "b"), 61_000))
    }

    @Test fun `leaving a plan flushes the active segment`() {
        val tracker = PlanSessionTracker()
        tracker.onForeground("a", "plan", setOf("a"), 1_000)
        assertEquals(listOf(PlanUsage("plan", "a", 30)), tracker.stop(31_000))
    }
}
