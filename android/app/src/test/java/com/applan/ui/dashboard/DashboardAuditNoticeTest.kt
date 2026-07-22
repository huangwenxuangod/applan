package com.applan.ui.dashboard

import com.applan.network.DashboardAuditVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardAuditNoticeTest {
    @Test
    fun `aligned and offline verification stay silent`() {
        assertNull(DashboardAuditNotice.forVerification(DashboardAuditVerification.Aligned))
        assertNull(DashboardAuditNotice.forVerification(DashboardAuditVerification.Offline))
    }

    @Test
    fun `actionable verification failures have stable notification copy`() {
        assertEquals(
            AuditNotification("unauthorized", "数据同步授权失败"),
            DashboardAuditNotice.forVerification(DashboardAuditVerification.Unauthorized)
        )
        assertEquals(
            AuditNotification("failed", "数据同步异常，稍后重试"),
            DashboardAuditNotice.forVerification(DashboardAuditVerification.Failed)
        )
        assertEquals(
            AuditNotification("mismatch", "云端记录不完整，稍后重试"),
            DashboardAuditNotice.forVerification(DashboardAuditVerification.Mismatch)
        )
    }
}
