import unittest
from datetime import datetime, timezone

from dashboard import summarize_events, utc_today_range


class DashboardSummaryTest(unittest.TestCase):
    def test_summarizes_supported_events_within_the_requested_range(self):
        result = summarize_events(
            [
                ("{\"type\":\"app_blocked\",\"packageName\":\"com.tencent.mm\"}", 1_000),
                ("{\"type\":\"app_blocked\",\"packageName\":\"com.tencent.mm\"}", 1_100),
                ("{\"type\":\"temporary_pass_granted\"}", 1_200),
                ("{\"type\":\"plan_started\"}", 1_300),
                ("{\"type\":\"plan_ended_early\"}", 1_400),
                ("{\"type\":\"plan_expired\"}", 1_500),
                ("{\"type\":\"plan_app_usage\",\"durationSeconds\":125}", 1_600),
                ("{\"type\":\"app_blocked\",\"packageName\":\"outside\"}", 2_000),
            ],
            1_000,
            2_000,
        )

        self.assertEqual(
            result,
            {
                "from": 1_000,
                "to": 2_000,
                "blockedCount": 2,
                "temporaryPassCount": 1,
                "temporaryPassRemaining": 4,
                "planStartedCount": 1,
                "planEndedEarlyCount": 1,
                "planExpiredCount": 1,
                "focusMinutes": 2,
                "topApps": [{"packageName": "com.tencent.mm", "count": 2}],
            },
        )

    def test_ignores_invalid_bodies_and_never_returns_negative_passes(self):
        result = summarize_events(
            [("not-json", 1_000)] + [("{\"type\":\"temporary_pass_granted\"}", 1_000)] * 6,
            1_000,
            1_001,
        )

        self.assertEqual(result["temporaryPassCount"], 6)
        self.assertEqual(result["temporaryPassRemaining"], 0)
        self.assertEqual(result["blockedCount"], 0)
        self.assertEqual(result["topApps"], [])

    def test_uses_utc_midnight_boundaries_for_today(self):
        start, end = utc_today_range(datetime(2026, 12, 31, 23, 59, tzinfo=timezone.utc))

        self.assertEqual(start, 1_798_675_200_000)
        self.assertEqual(end, 1_798_761_600_000)


if __name__ == "__main__":
    unittest.main()
