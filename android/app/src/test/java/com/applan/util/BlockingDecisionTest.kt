package com.applan.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingDecisionTest {

    private val scheduledPolicy = EffectivePolicy(
        isScheduled = true,
        isPlanActive = false,
        allowedPackages = setOf("com.tencent.mm")
    )

    @Test
    fun `normal exit makes an active schedule passive outside Plan Mode`() {
        assertTrue(
            BlockingDecision.isAllowed(
                packageName = "com.sankuai.meituan",
                policy = scheduledPolicy,
                exitGranted = true,
                planModeEnabled = false,
                temporaryPass = null
            )
        )
    }

    @Test
    fun `Plan Mode still enforces an active schedule after exit`() {
        assertFalse(
            BlockingDecision.isAllowed(
                packageName = "com.sankuai.meituan",
                policy = scheduledPolicy,
                exitGranted = true,
                planModeEnabled = true,
                temporaryPass = null
            )
        )
    }

    @Test
    fun `temporary pass allows only its package`() {
        val pass = TemporaryPass("com.sankuai.meituan", System.currentTimeMillis() + 300_000)

        assertTrue(
            BlockingDecision.isAllowed(
                packageName = "com.sankuai.meituan",
                policy = scheduledPolicy,
                exitGranted = false,
                planModeEnabled = true,
                temporaryPass = pass
            )
        )
        assertFalse(
            BlockingDecision.isAllowed(
                packageName = "com.eg.android.AlipayGphone",
                policy = scheduledPolicy,
                exitGranted = false,
                planModeEnabled = true,
                temporaryPass = pass
            )
        )
    }

    @Test
    fun `background triggers without a foreground package stay passive`() {
        assertEquals(
            BlockingCoordinator.Decision.PASSIVE,
            BlockingCoordinator.evaluate(
                packageName = null,
                policy = scheduledPolicy,
                exitGranted = false,
                planModeEnabled = true,
                temporaryPass = null
            )
        )
    }

    @Test
    fun `active allowlist blocks only an unlisted foreground package`() {
        assertEquals(
            BlockingCoordinator.Decision.BLOCKED,
            BlockingCoordinator.evaluate(
                packageName = "com.sankuai.meituan",
                policy = scheduledPolicy,
                exitGranted = false,
                planModeEnabled = false,
                temporaryPass = null
            )
        )
    }
}
