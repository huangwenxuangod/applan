import json
from collections import Counter
from datetime import datetime, timedelta, timezone
from typing import Iterable, Optional, Tuple


MAX_DAILY_TEMPORARY_PASSES = 5
MAX_DASHBOARD_RANGE_MS = 90 * 24 * 60 * 60 * 1000


def validate_range(start: int, end: int) -> None:
    if isinstance(start, bool) or isinstance(end, bool) or not isinstance(start, int) or not isinstance(end, int):
        raise ValueError("from and to must be integer epoch milliseconds")
    if start < 0 or end <= start:
        raise ValueError("to must be greater than from, and both must be non-negative")
    if end - start > MAX_DASHBOARD_RANGE_MS:
        raise ValueError("dashboard range must not exceed 90 days")


def utc_today_range(now: Optional[datetime] = None) -> Tuple[int, int]:
    current = now.astimezone(timezone.utc) if now else datetime.now(timezone.utc)
    start = current.replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + timedelta(days=1)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def summarize_events(rows: Iterable[Tuple[str, int]], start: int, end: int) -> dict:
    validate_range(start, end)
    blocked_count = 0
    temporary_pass_count = 0
    plan_started_count = 0
    plan_ended_early_count = 0
    plan_expired_count = 0
    focus_seconds = 0
    app_counts: Counter[str] = Counter()

    for body, occurred_at in rows:
        if not isinstance(occurred_at, int) or occurred_at < start or occurred_at >= end:
            continue
        try:
            event = json.loads(body)
        except (TypeError, json.JSONDecodeError):
            continue
        if not isinstance(event, dict):
            continue
        event_type = event.get("type")
        if event_type == "app_blocked":
            blocked_count += 1
            package_name = event.get("packageName")
            if isinstance(package_name, str) and package_name:
                app_counts[package_name] += 1
        elif event_type == "temporary_pass_granted":
            temporary_pass_count += 1
        elif event_type == "plan_started":
            plan_started_count += 1
        elif event_type == "plan_ended_early":
            plan_ended_early_count += 1
        elif event_type == "plan_expired":
            plan_expired_count += 1
        elif event_type == "plan_app_usage":
            seconds = event.get("durationSeconds")
            if isinstance(seconds, int) and seconds > 0:
                focus_seconds += seconds

    return {
        "from": start,
        "to": end,
        "blockedCount": blocked_count,
        "temporaryPassCount": temporary_pass_count,
        "temporaryPassRemaining": max(0, MAX_DAILY_TEMPORARY_PASSES - temporary_pass_count),
        "planStartedCount": plan_started_count,
        "planEndedEarlyCount": plan_ended_early_count,
        "planExpiredCount": plan_expired_count,
        "focusMinutes": focus_seconds // 60,
        "topApps": [
            {"packageName": package_name, "count": count}
            for package_name, count in sorted(app_counts.items(), key=lambda item: (-item[1], item[0]))
        ],
    }
