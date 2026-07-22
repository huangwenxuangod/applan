# applan Policy Engine Design

## Goal

Make applan an AI-plan-first Android blocker that keeps running passively but never launches its activity merely because the user unlocked the device. Time profiles control when blocking applies; an AI plan grants a short, locally enforced subset of application access. A single-device backend manages the durable policy and audit trail.

## Decisions

- The self-start settings command opens only `Settings.ACTION_APPLICATION_SETTINGS`, the public Apps-category intent. It must not use vendor activities, app-details intents, or the all-applications-management intent.
- `exitGranted` means passive: no activity launch, overlay, watchdog restart, or permission enforcement. It does not erase scheduled policies.
- An active time profile blocks passively. A blocked foreground application gets the existing overlay; applan becomes visible only after an explicit user action on that overlay.
- An AI plan contains an app allowlist, a purpose, and an expiry. It may narrow an active profile but cannot broaden the profile's allowlist.
- The local device is the policy execution authority. The server is a single-device control plane and must not be required for a block to occur.
- A single SQLite database and one bootstrap device token are sufficient. No account, multi-device conflict resolution, remote unlock, or push notification is in scope.

## State Model

`PASSIVE` is the normal post-exit state. `SCHEDULED` is selected when at least one time window is active. `PLAN` is selected while an unexpired AI plan exists. `PLAN` is constrained by the active schedule. Each state is persisted; static `AppState` fields are cache/UI signals and never the policy source of truth.

At every accessibility event, boot event, app resume, time-change event, and scheduled boundary, `PolicyEngine.evaluate(now)` recomputes the effective policy. The next boundary alarm reduces latency but is not relied upon as the only source of truth.

## Error Handling

- Without exact-alarm permission, the app uses an inexact boundary alarm and records that status; foreground-app evaluation still enforces the policy.
- A failed backend sync preserves the last valid local policy and queues audit events.
- Invalid server policy JSON is rejected by the server and ignored by the client; the previous policy remains active.
- A failed Apps-category settings launch falls back only to `Settings.ACTION_SETTINGS`.

## Acceptance Checks

- After `exit_app` outside an active time profile, unlock, boot recovery, job execution, and service restarts never bring `MainActivity` forward.
- A Monday 09:00-12:00 allowlist profile blocks a non-allowed app without foregrounding applan; a plan inside that window cannot add an app outside the profile allowlist.
- A cross-midnight profile such as Friday 22:00-Saturday 07:00 is active on both sides of midnight.
- The self-start settings row opens the public Apps-category intent without a vendor-specific component.
- The backend accepts a token-authenticated policy document and audit event, and unauthenticated calls receive HTTP 401.
