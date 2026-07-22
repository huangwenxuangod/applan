package com.applan.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditNoticeGateTest {
    private lateinit var gate: AuditNoticeGate

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dashboard_audit_notice", Context.MODE_PRIVATE).edit().clear().commit()
        gate = AuditNoticeGate(context)
    }

    @Test
    fun `same problem is shown once within six hours`() {
        val now = 1_700_000_000_000L

        assertTrue(gate.shouldNotify("unauthorized", now))
        assertFalse(gate.shouldNotify("unauthorized", now + 1))
        assertTrue(gate.shouldNotify("unauthorized", now + AuditNoticeGate.THROTTLE_MS))
    }

    @Test
    fun `different problems have independent throttles`() {
        val now = 1_700_000_000_000L

        assertTrue(gate.shouldNotify("unauthorized", now))
        assertTrue(gate.shouldNotify("mismatch", now + 1))
    }
}
