# Passive Exit and Fragmented Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a normal exit suppress every blocker source outside Plan Mode, while supporting multiple allowlist time profiles and a one-app, five-minute temporary pass.

**Architecture:** Persist user policy in `PolicyRepository` and temporary package passes in device-protected preferences. A small `BlockingDecision` owns precedence: reboot clears exit; non-Plan-Mode exit disables blocking; an unexpired temporary pass allows only its package; scheduled profiles and AI plans otherwise restrict foreground packages. Every overlay source asks this one decision before showing UI.

**Tech Stack:** Kotlin, Android SharedPreferences, Compose Material 3, Robolectric/JUnit.

## Global Constraints

- Do not add a backend dependency or a new Android permission.
- `exitGranted=true` suppresses overlays and app blocking unless Plan Mode is enabled; reboot remains the only automatic reset.
- A temporary pass expires after exactly five minutes and applies to one package only.
- Time profiles are allowlists: an active profile blocks packages not in its allowed set.
- Existing saved `daily-global-block` data remains readable.

---

### Task 1: Central blocking decision

**Files:**
- Modify: `android/app/src/main/java/com/applan/util/PolicyModels.kt`
- Modify: `android/app/src/main/java/com/applan/util/PolicyRepository.kt`
- Create: `android/app/src/test/java/com/applan/util/BlockingDecisionTest.kt`

**Interfaces:**
- Produces `BlockingDecision.shouldBlock(packageName, policy, exitGranted, planModeEnabled, temporaryPass)`.
- Produces `TemporaryPass(packageName, expiresAt)` and repository methods `getTemporaryPass`, `saveTemporaryPass`, `clearTemporaryPass`.

- [ ] Write failing tests proving normal exit overrides an active schedule, Plan Mode does not override an active schedule, and an unexpired temporary pass allows only its package.
- [ ] Run `gradle :app:testDebugUnitTest --tests com.applan.util.BlockingDecisionTest` and confirm failure.
- [ ] Implement `BlockingDecision` and device-protected temporary-pass persistence with expiry cleanup on read.
- [ ] Run the same command and confirm success.

### Task 2: Route every overlay source through the decision

**Files:**
- Modify: `android/app/src/main/java/com/applan/service/LockAccessibilityService.kt`
- Modify: `android/app/src/main/java/com/applan/service/ScheduleBoundaryReceiver.kt`
- Modify: `android/app/src/main/java/com/applan/service/KeepAliveService.kt`
- Modify: `android/app/src/main/java/com/applan/service/KeepAliveJobService.kt`
- Modify: `android/app/src/main/java/com/applan/service/DaemonService.kt`
- Create: `android/app/src/test/java/com/applan/service/ScheduleBoundaryReceiverTest.kt`

**Interfaces:**
- Consumes `BlockingDecision` and `PolicyRepository` policy/pass state.
- `ScheduleBoundaryReceiver` must hide an existing overlay when the global decision is passive.

- [ ] Write a failing receiver test proving an active scheduled profile does not show an overlay after normal exit.
- [ ] Run `gradle :app:testDebugUnitTest --tests com.applan.service.ScheduleBoundaryReceiverTest` and confirm failure.
- [ ] Move the exit, plan, temporary-pass, and profile precedence checks ahead of all `BlockOverlay.show` calls.
- [ ] Run the receiver test and then `gradle :app:testDebugUnitTest`.

### Task 3: Temporary-pass action in the blocking overlay

**Files:**
- Modify: `android/app/src/main/java/com/applan/service/BlockOverlay.kt`
- Modify: `android/app/src/main/java/com/applan/service/LockAccessibilityService.kt`
- Create: `android/app/src/test/java/com/applan/util/PolicyRepositoryTest.kt`

**Interfaces:**
- `BlockOverlay.show(context, blockedPackage: String? = null)` renders `临时放行 5 分钟` only when a real blocked package is supplied.
- Clicking it saves `TemporaryPass(blockedPackage, now + 300_000)`, hides the overlay immediately, and does not foreground applan.

- [ ] Write a failing persistence test proving a temporary pass round-trips and expires.
- [ ] Run the focused test and confirm failure.
- [ ] Pass the foreground package into the overlay and implement the action.
- [ ] Run focused tests and `gradle :app:testDebugUnitTest`.

### Task 4: Multiple allowlist profile editor

**Files:**
- Modify: `android/app/src/main/java/com/applan/ui/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/java/com/applan/util/AppPackageResolver.kt` only if a reusable installed-app query is missing.
- Modify: `android/app/src/test/java/com/applan/util/PolicyRepositoryTest.kt`

**Interfaces:**
- The settings page lists saved `TimeProfile` values, adds a profile with all weekdays and no allowed apps, edits start/end times and allowed packages, and deletes a profile.
- Profile IDs are generated with `UUID.randomUUID().toString()`.

- [ ] Write a failing repository test for saving two independent profiles.
- [ ] Run the test and confirm failure if the existing test does not cover the UI data shape.
- [ ] Replace the single `daily-global-block` editor with a list of profiles and an add/edit dialog; keep the allowed-app picker scoped to installed launchable apps.
- [ ] Run all unit tests and manually confirm a profile can be added, edited, and deleted.

### Task 5: Verification

**Files:**
- Verify all files above.

- [ ] Run `gradle :app:testDebugUnitTest`.
- [ ] Run `gradle :app:assembleDebug`.
- [ ] Install only on explicit request; manual acceptance is: normal exit then open WeChat during an active profile (no overlay), enable Plan Mode and open a non-allowed app (overlay), tap temporary pass (same app usable for five minutes), then confirm a second non-allowed app remains blocked.

### Task 6: Plan lifecycle events and sync compatibility

**Files:**
- Modify: `android/app/src/main/java/com/applan/util/PolicyModels.kt`
- Modify: `android/app/src/main/java/com/applan/util/PolicyRepository.kt`
- Modify: `android/app/src/main/java/com/applan/ui/App.kt`
- Modify: `android/app/src/main/java/com/applan/util/EventAnalytics.kt`
- Modify: `android/app/src/main/java/com/applan/ui/dashboard/DashboardScreen.kt`

**Decision:** The device remains the lifecycle authority. The existing generic `/v1/events/batch` endpoint accepts and deduplicates arbitrary event names, so no server migration is needed for `plan_started`, `plan_ended_early`, or `plan_expired`.

- [x] Add stable plan ID and start time while preserving old saved plan JSON.
- [x] Record plan start, early end, and expiry locally for later batch sync.
- [x] Show today's plan-start count in Dashboard.
- [x] Verify with `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`.

### Task 7: Server dashboard aggregation (P3.1)

**Files:**
- Create: `server/dashboard.py`
- Create: `server/test_dashboard.py`
- Create: `server/test_dashboard_api.py`
- Modify: `server/proxy_backend.py`
- Modify: `server/Dockerfile`
- Modify: `server/docker-compose.yml`

**Decision:** The server remains an audit and aggregation layer. It reads only deduplicated `synced_events`; Android remains the source of truth for enforcement and renders local data without waiting for a response.

**Interfaces:**
- `GET /v1/dashboard/today`: authenticated summary for the current UTC day.
- `GET /v1/dashboard/range?from=<epoch-ms>&to=<epoch-ms>`: authenticated summary for a caller-defined range up to 90 days. Android should use this endpoint when it needs the device-local calendar day.
- Both return event counts, remaining temporary-pass quota, plan lifecycle counts, and ranked blocked packages.

- [x] Add pure aggregation tests, including invalid stored JSON and quota clamping.
- [x] Add endpoint tests covering event ingestion, bearer authentication, invalid date range, and current-day output.
- [x] Add range validation and read only the existing deduplicated event store.
- [x] Keep image and Compose bindings coherent for the new aggregation module; correct the Dockerfile comment syntax so Docker can parse it.
- [x] Verify with `python -m unittest test_dashboard.py test_dashboard_api.py` in an isolated FastAPI/httpx environment and `python -m py_compile dashboard.py proxy_backend.py`.

### Task 8: Silent dashboard audit reconciliation (P3.2)

**Files:**
- Create: `android/app/src/main/java/com/applan/util/DashboardAudit.kt`
- Create: `android/app/src/main/java/com/applan/util/AuditNoticeGate.kt`
- Create: `android/app/src/main/java/com/applan/ui/dashboard/DashboardAuditNotice.kt`
- Modify: `android/app/src/main/java/com/applan/network/ApplanClient.kt`
- Modify: `android/app/src/main/java/com/applan/ui/dashboard/DashboardScreen.kt`

**Decision:** Dashboard remains local-first and must not render sync badges, timestamps, loading text, success toasts, or offline copy. After batch upload it silently compares the server range summary with the same local calendar-day data. Only actionable `401/403`, server/parse failure, or mismatched records may show a top toast, once per category every six hours. Offline is silent.

- [x] Add tests for local/remote summary alignment, throttling, client request order, authentication, malformed server data, and mismatched summaries.
- [x] Return typed audit outcomes from `ApplanClient` without allowing server data to overwrite local Dashboard data.
- [x] Use a device-local start/end range rather than server UTC today for reconciliation.
- [x] Wire only actionable outcomes to the existing top toast; leave successful and offline checks visually silent.
- [x] Verify with `gradle :app:testDebugUnitTest :app:assembleDebug`.
