package com.applan.network

import com.applan.util.PolicyEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApplanClientDashboardAuditTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApplanClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApplanClient().apply {
            updateConfig(server.url("/").toString().trimEnd('/'), "device-token")
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `verification uploads then accepts a matching range summary`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":1}"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"from":1000,"to":2000,"blockedCount":1,"temporaryPassCount":0,"planStartedCount":0,"planEndedEarlyCount":0,"planExpiredCount":0,"topApps":[{"packageName":"com.tencent.mm","count":1}]}"""
            )
        )

        val result = client.verifyDashboardAudit(
            events = listOf(PolicyEvent("event", "app_blocked", 1_000, "com.tencent.mm")),
            from = 1_000,
            to = 2_000
        )

        assertEquals(DashboardAuditVerification.Aligned, result)
        assertEquals("/v1/events/batch", server.takeRequest().path)
        val dashboardRequest = server.takeRequest()
        assertEquals("/v1/dashboard/range?from=1000&to=2000", dashboardRequest.path)
        assertEquals("Bearer device-token", dashboardRequest.getHeader("Authorization"))
    }

    @Test
    fun `verification reports unauthorized without reading a dashboard response`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.verifyDashboardAudit(
            events = listOf(PolicyEvent("event", "app_blocked", 1_000, "com.tencent.mm")),
            from = 1_000,
            to = 2_000
        )

        assertEquals(DashboardAuditVerification.Unauthorized, result)
        assertTrue(server.requestCount == 1)
    }

    @Test
    fun `verification reports failed for an invalid dashboard response`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":1}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        val result = client.verifyDashboardAudit(
            events = listOf(PolicyEvent("event", "app_blocked", 1_000, "com.tencent.mm")),
            from = 1_000,
            to = 2_000
        )

        assertEquals(DashboardAuditVerification.Failed, result)
    }

    @Test
    fun `verification reports a mismatch when the remote count differs`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":1}"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"from":1000,"to":2000,"blockedCount":2,"temporaryPassCount":0,"planStartedCount":0,"planEndedEarlyCount":0,"planExpiredCount":0,"topApps":[{"packageName":"com.tencent.mm","count":2}]}"""
            )
        )

        val result = client.verifyDashboardAudit(
            events = listOf(PolicyEvent("event", "app_blocked", 1_000, "com.tencent.mm")),
            from = 1_000,
            to = 2_000
        )

        assertEquals(DashboardAuditVerification.Mismatch, result)
    }
}
