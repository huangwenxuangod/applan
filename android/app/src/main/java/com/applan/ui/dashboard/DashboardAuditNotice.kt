package com.applan.ui.dashboard

import com.applan.network.DashboardAuditVerification

data class AuditNotification(val key: String, val message: String)

object DashboardAuditNotice {
    fun forVerification(result: DashboardAuditVerification): AuditNotification? = when (result) {
        DashboardAuditVerification.Aligned,
        DashboardAuditVerification.Offline -> null
        DashboardAuditVerification.Unauthorized -> AuditNotification("unauthorized", "数据同步授权失败")
        DashboardAuditVerification.Failed -> AuditNotification("failed", "数据同步异常，稍后重试")
        DashboardAuditVerification.Mismatch -> AuditNotification("mismatch", "云端记录不完整，稍后重试")
    }
}
