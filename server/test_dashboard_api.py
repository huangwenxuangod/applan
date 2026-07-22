import os
import shutil
import tempfile
import unittest
from datetime import datetime, timezone

import httpx

os.environ.setdefault("DEEPSEEK_API_KEY", "test")
os.environ.setdefault("DEVICE_TOKEN", "test-device-token")

import proxy_backend


class DashboardEndpointTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        proxy_backend.STATE_DB = os.path.join(self.temp_dir, "state.db")
        proxy_backend.DEVICE_TOKEN = "test-device-token"
        proxy_backend.ALLOW_INSECURE_LOCAL = False
        proxy_backend._init_state_db()
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=proxy_backend.app),
            base_url="http://testserver",
        )
        self.headers = {"Authorization": "Bearer test-device-token"}

    async def asyncTearDown(self):
        await self.client.aclose()
        shutil.rmtree(self.temp_dir)

    async def test_batch_events_are_available_from_the_authenticated_range_dashboard(self):
        response = await self.client.post(
            "/v1/events/batch",
            headers=self.headers,
            json=[
                {"id": "one", "type": "app_blocked", "occurredAt": 1_000, "packageName": "com.tencent.mm"},
                {"id": "two", "type": "temporary_pass_granted", "occurredAt": 1_001},
                {"id": "three", "type": "plan_started", "occurredAt": 1_002},
            ],
        )
        self.assertEqual(response.status_code, 202)

        dashboard = await self.client.get("/v1/dashboard/range?from=1000&to=2000", headers=self.headers)

        self.assertEqual(dashboard.status_code, 200)
        self.assertEqual(
            dashboard.json(),
            {
                "from": 1_000,
                "to": 2_000,
                "blockedCount": 1,
                "temporaryPassCount": 1,
                "temporaryPassRemaining": 4,
                "planStartedCount": 1,
                "planEndedEarlyCount": 0,
                "planExpiredCount": 0,
                "focusMinutes": 0,
                "topApps": [{"packageName": "com.tencent.mm", "count": 1}],
            },
        )

    async def test_dashboard_rejects_missing_token_and_invalid_range(self):
        unauthorized = await self.client.get("/v1/dashboard/range?from=0&to=1")
        invalid_range = await self.client.get(
            "/v1/dashboard/range?from=0&to=7776000001",
            headers=self.headers,
        )

        self.assertEqual(unauthorized.status_code, 401)
        self.assertEqual(invalid_range.status_code, 400)

    async def test_today_dashboard_returns_authenticated_utc_day_summary(self):
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        await self.client.post(
            "/v1/events/batch",
            headers=self.headers,
            json=[{"id": "today", "type": "app_blocked", "occurredAt": now, "packageName": "com.tencent.mm"}],
        )

        dashboard = await self.client.get("/v1/dashboard/today", headers=self.headers)

        self.assertEqual(dashboard.status_code, 200)
        self.assertEqual(dashboard.json()["blockedCount"], 1)
        self.assertLessEqual(dashboard.json()["from"], now)
        self.assertGreater(dashboard.json()["to"], now)

    async def test_policy_uses_compare_and_swap_versions(self):
        first = await self.client.put(
            "/v1/policy",
            headers=self.headers,
            json={"baseVersion": 0, "profiles": [], "planModeEnabled": True},
        )
        stale = await self.client.put(
            "/v1/policy",
            headers=self.headers,
            json={"baseVersion": 0, "profiles": [], "planModeEnabled": False},
        )

        self.assertEqual(first.status_code, 200)
        self.assertEqual(first.json()["version"], 1)
        self.assertTrue(first.json()["planModeEnabled"])
        self.assertEqual(stale.status_code, 409)
        self.assertEqual(stale.json()["version"], 1)

    async def test_policy_requires_device_token(self):
        response = await self.client.get("/v1/policy")

        self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
