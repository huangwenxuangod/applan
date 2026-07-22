# applan Policy Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add passive time profiles and locally enforced AI plans, remove unintended foreground relaunches, and add a token-authenticated single-device policy backend.

**Architecture:** A persistent `PolicyRepository` owns schedules, plans, and the passive/active decision. `PolicyEngine` is pure Kotlin and determines the effective allowlist at every lifecycle or accessibility event. FastAPI stores one versioned policy document and audit events in SQLite.

**Tech Stack:** Kotlin, Android SharedPreferences, AlarmManager, AccessibilityService, FastAPI, SQLite, httpx, JUnit 4.

## Global Constraints

- `Settings.ACTION_APPLICATION_SETTINGS` is the sole self-start settings target; no vendor component, app-details fallback, or all-applications-management target.
- A normal exit is passive and must not start `MainActivity` or show `BlockOverlay`.
- Enforcement works offline from the last locally stored policy.
- Time profiles use device local time and weekdays `1` through `7` for Monday through Sunday.
- Server authorization uses `Authorization: Bearer <DEVICE_TOKEN>` and is mandatory outside local mock mode.

---

## File Structure

- Create `android/app/src/main/java/com/applan/util/PolicyModels.kt`: schedule and AI-plan contracts.
- Create `android/app/src/main/java/com/applan/util/PolicyRepository.kt`: persistent policy JSON and evaluator.
- Create `android/app/src/test/java/com/applan/util/PolicyRepositoryTest.kt`: time-window and plan-intersection tests.
- Modify `android/app/src/main/java/com/applan/util/{AppConfig,AppState,AutoStartHelper}.kt`: persistent passive state and Apps settings target.
- Modify `android/app/src/main/java/com/applan/service/{ScreenReceiver,BootReceiver,KeepAliveService,DaemonService,KeepAliveJobService,LockAccessibilityService}.kt`: no foreground relaunch when passive and policy-driven enforcement.
- Modify `android/app/src/main/java/com/applan/network/{ApplanClient,ChatMessage}.kt` and `android/app/src/main/java/com/applan/ui/{App.kt,chat/ChatViewModel.kt}`: persist and enforce AI plans.
- Modify `server/{proxy_backend.py,Dockerfile,docker-compose.yml,.env.example}` and create `server/test_proxy_backend.py`: SQLite policy, event, and authentication endpoints.

### Task 1: Persisted Time Profiles

**Files:**
- Create: `android/app/src/main/java/com/applan/util/PolicyModels.kt`
- Create: `android/app/src/main/java/com/applan/util/PolicyRepository.kt`
- Test: `android/app/src/test/java/com/applan/util/PolicyRepositoryTest.kt`

**Interfaces:**

```kotlin
data class TimeProfile(val id: String, val weekdays: Set<Int>, val startMinute: Int, val endMinute: Int, val allowedPackages: Set<String>)
data class AiPlan(val allowedPackages: Set<String>, val purpose: String, val expiresAt: Long)
data class EffectivePolicy(val scheduled: Boolean, val planActive: Boolean, val allowedPackages: Set<String>)
fun PolicyRepository.evaluate(now: ZonedDateTime): EffectivePolicy
```

- [ ] Write tests for a Monday 09:00-12:00 profile, a Friday 22:00-Saturday 07:00 profile, expired plans, and plan/profile allowlist intersection.
- [ ] Run `cd android && gradle testDebugUnitTest --tests com.applan.util.PolicyRepositoryTest`; expect compilation failure before implementation.
- [ ] Persist profiles and the optional plan in device-protected `app_config` preferences as JSON. Evaluate active windows with `startMinute <= endMinute` for same-day ranges and prior-weekday matching for overnight ranges. Intersect a non-expired plan with active profile allowlists; an active profile with an empty allowlist blocks every non-system package.
- [ ] Run `cd android && gradle testDebugUnitTest --tests com.applan.util.PolicyRepositoryTest`; expect `BUILD SUCCESSFUL`.
- [ ] Commit `feat: add persisted policy evaluator`.

### Task 2: Passive Exit and Public Apps Settings

**Files:**
- Modify: `android/app/src/main/java/com/applan/util/{AppConfig,AppState,AutoStartHelper}.kt`
- Modify: `android/app/src/main/java/com/applan/service/{ScreenReceiver,BootReceiver,KeepAliveService,DaemonService,KeepAliveJobService}.kt`
- Test: `android/app/src/test/java/com/applan/util/AppStateTest.kt`

**Interfaces:**

```kotlin
fun PolicyRepository.mayInterruptUser(now: ZonedDateTime): Boolean = evaluate(now).scheduled || evaluate(now).planActive
fun AppConfig.setPassive(value: Boolean)
fun AppConfig.isPassive(): Boolean
```

- [ ] Add tests proving passive exit does not block with no active schedule and an active schedule overrides passive exit.
- [ ] Run `cd android && gradle testDebugUnitTest --tests com.applan.util.AppStateTest`; expect failure before adding passive-policy delegation.
- [ ] Replace every service/receiver decision that currently uses only `AppState.shouldBlock()` with `PolicyRepository.mayInterruptUser`. `ScreenReceiver` and `BootReceiver` must only re-evaluate/re-arm schedules and never call `startActivity`. `onTaskRemoved`, `onDestroy`, and `KeepAliveJobService` must not show overlays or restart watchdogs while the result is false.
- [ ] Replace `AutoStartHelper.jumpToAutoStartSetting` with `ACTION_APPLICATION_SETTINGS`, falling back only to `ACTION_SETTINGS`; delete vendor target maps and `ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS` use.
- [ ] Run `cd android && gradle testDebugUnitTest`; manually exit outside a schedule, lock/unlock, reboot recovery, and confirm `MainActivity` never foregrounds.
- [ ] Commit `fix: keep normal exit passive`.

### Task 3: Passive Enforcement and AI Plans

**Files:**
- Modify: `android/app/src/main/java/com/applan/service/LockAccessibilityService.kt`
- Modify: `android/app/src/main/java/com/applan/network/{ApplanClient,ChatMessage}.kt`
- Modify: `android/app/src/main/java/com/applan/ui/{App.kt,chat/ChatViewModel.kt}`
- Test: `android/app/src/test/java/com/applan/util/PolicyRepositoryTest.kt`

- [ ] Add tests that a plan expires and that a plan cannot expand an active schedule's allowlist.
- [ ] Run the focused policy test; expect failure until expiry/intersection behavior exists.
- [ ] Evaluate the repository in `LockAccessibilityService.handleWindowEvent` before legacy grants. Permit system packages and `EffectivePolicy.allowedPackages`; use `BlockOverlay.show` for a blocked package and do not call `fallbackPullBack` unless overlay creation throws.
- [ ] On `grant_plan`, reject no resolved packages, clamp timeout to 1-60 minutes, persist `AiPlan`, and leave an active schedule's effective allowlist as the intersection.
- [ ] Run `cd android && gradle testDebugUnitTest`; manually verify an active profile blocks an app with only an overlay and never foregrounds applan.
- [ ] Commit `feat: enforce schedules and ai plans locally`.

### Task 4: Single-Device Backend

**Files:**
- Modify: `server/proxy_backend.py`
- Modify: `server/Dockerfile`
- Modify: `server/docker-compose.yml`
- Modify: `server/.env.example`
- Create: `server/test_proxy_backend.py`

**Interfaces:**

```text
GET  /v1/policy                 -> {version: int, profiles: list, strict: bool}
PUT  /v1/policy                 <- same document; returns the stored document
POST /v1/events                 <- {type: non-empty string, at: integer, ...}
Authorization: Bearer DEVICE_TOKEN
```

- [ ] Add FastAPI tests for missing-token `401`, policy round-trip, invalid `version` rejection, and accepted audit event.
- [ ] Run `cd server && python3 -m pytest test_proxy_backend.py -q`; expect failure before endpoints exist.
- [ ] Add `DEVICE_TOKEN`, `STATE_DB`, and `ALLOW_INSECURE_LOCAL` environment settings. Use standard-library SQLite tables `policy(id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL, body TEXT NOT NULL, updated_at INTEGER NOT NULL)` and `events(id INTEGER PRIMARY KEY, body TEXT NOT NULL, created_at INTEGER NOT NULL)`. Compare bearer tokens with `hmac.compare_digest`.
- [ ] Mount the SQLite file in Compose and document `DEVICE_TOKEN` in `.env.example`; keep the existing DeepSeek proxy endpoints unchanged.
- [ ] Run backend tests and `docker compose --env-file /tmp/applan.env config --quiet`; expect exit 0.
- [ ] Commit `feat: add single device policy backend`.

### Task 5: Client Sync and Full Verification

**Files:**
- Modify: `android/app/src/main/java/com/applan/network/ApplanClient.kt`
- Modify: `android/app/src/main/java/com/applan/util/AppConfig.kt`
- Modify: `README.md`
- Modify: `DEPLOY.md`

- [ ] Add a policy repository test that rejects a remote document with a version lower than the stored version.
- [ ] Run the focused test; expect failure before versioned remote application exists.
- [ ] Add authenticated policy pull/push and queued event upload to `ApplanClient`. Apply only a newer valid policy atomically, re-arm the next boundary, and preserve the last local policy on HTTP, token, or JSON failure.
- [ ] Update deployment docs for HTTPS, `DEVICE_TOKEN`, policy backup, and the Apps-category manual self-start path.
- [ ] Run `cd android && gradle testDebugUnitTest` and `cd server && python3 -m pytest test_proxy_backend.py -q`; manually test settings entry, passive exit, offline schedule enforcement, reboot recovery, and plan/profile intersection.
- [ ] Commit `feat: sync single device policy`.

## Self-Review

- Tasks 1-3 cover persistent policy, passive exit, public Apps settings, passive time enforcement, and AI plans.
- Tasks 4-5 cover authenticated single-device storage, audit, sync, offline behavior, and deployment documentation.
- Shared names are defined before use: `TimeProfile`, `AiPlan`, `EffectivePolicy`, `PolicyRepository.evaluate`, and `PolicyRepository.mayInterruptUser`.
