# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Instructions

- Always explain any code you write in great detail — walk through what each part does and why.
- The user is new to Kotlin. Whenever explaining anything, keep this in mind: define Kotlin-specific concepts as they come up (coroutines, extension functions, flows, etc.), and don't assume prior Kotlin familiarity.
- Documentation in this repo uses **Doxygen-style** comments (`/** */` with `@file`, `@brief`, `@details`, `@param`, `@return`, `@note`, `@warning`, `@property`, `@code`/`@endcode`, and cross-refs via `@ref Fully.Qualified.Name` / `#member`) — **not** Kotlin-native KDoc. Match this style when adding or editing docs. Every existing source file follows it; keep it consistent.

## What Kempt is

Kempt is an Android app (Kotlin + Jetpack Compose) that blocks distracting apps with one tap; getting back in requires a passcode held by an accountability partner, and bypass attempts are reported to that partner. It is **early/pre-alpha** — the README (`README.md`) describes the intended design; the code is scaffolding plus a working local lock. The accountability backend does not exist yet, so the network layer is a logged stub (see below).

Read `README.md` for the full design rationale (why UsageStats+overlay over AccessibilityService, why anti-tamper is social rather than technical, the privacy model, and Play Store constraints). It is the authoritative design doc.

## Build & run

**Requires JDK 17.** The system default Java is 24, which AGP 8.13 does **not** support, so plain `./gradlew` fails. Point `JAVA_HOME` at JDK 17–21. A working JDK 17 is cached locally from a prior foojay toolchain download:

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home
./gradlew :app:assembleDebug     # build the debug APK
./gradlew :app:installDebug      # build + install to a connected device/emulator
./gradlew :app:lint              # Android lint
```

- Toolchain: Gradle 8.14.5, AGP 8.13.2, Kotlin 2.3.0, `compileSdk`/`targetSdk` 36, `minSdk` 26.
- Android SDK is expected at `~/Library/Android/sdk` (set in `local.properties`, untracked).
- **No tests exist yet** — only `app/src/main` is present. `testInstrumentationRunner` is declared but there are no `src/test` / `src/androidTest` sources.
- Firebase/FCM is compiled in but **not wired**: `KemptMessagingService` never fires at runtime until you add a Firebase project + `app/google-services.json` and uncomment the `google-services` plugin (see the commented lines in `build.gradle.kts`, `app/build.gradle.kts`, and `gradle/libs.versions.toml`).
- Debug the monitor with `adb logcat -s AppMonitor` (foreground-app detection) / `-s Accountability` (stubbed network calls).

## Architecture

Single-module Android app under `app/src/main/java/com/kempt/app/`, wired together with **Hilt** DI. There is one Activity (`MainActivity`) hosting all Compose UI; the real work happens in a foreground service and supporting components.

**The lock lifecycle** is the core flow to understand — it spans several files:

1. **Arm** — `HomeViewModel.lockDown()` sets the armed flag in `LockStateStore`, starts `AppMonitorService`, schedules `HeartbeatWorker`, and records a `LOCK_ARMED` event.
2. **Monitor** — `AppMonitorService` (a `specialUse` foreground service) runs a 1-second polling loop. Each tick it reads the armed flag, checks usage access is still granted (revocation mid-lock = tamper → `USAGE_ACCESS_LOST` event), determines the foreground app from `UsageStatsManager.queryEvents()`, and sends a heartbeat.
3. **Enforce** — when a blocked app is foregrounded, the service draws `LockOverlay` (a `TYPE_APPLICATION_OVERLAY` window) asking for the passcode. The system **hides overlays over the Settings app**, so for force-blocked packages the service instead launches `LockActivity` (a real full-screen Activity that can't be suppressed). Settings is force-blocked while armed (see `forceBlockedPackages`) precisely because it's where a user would revoke permissions or force-stop the app.
4. **Disarm** — a correct passcode (verified against `LockStateStore`) anywhere (`LockOverlay`, `LockActivity`, or the in-app `HomeViewModel.disarm()`) clears the armed flag, stops the service, cancels the heartbeat, and records `UNLOCK_SUCCESS`. A wrong code records `UNLOCK_FAILED` (a signal to the partner).

**Foreground-app detection is subtle** — see `AppMonitorService.updateLockTarget()`. `lockTarget` is a *latch*, not a per-tick snapshot: a blocked app's own `ACTIVITY_RESUMED` arms it and only that same app's `ACTIVITY_PAUSED`/`STOPPED` releases it. This deliberately ignores bogus "android"/launcher/SystemUI foreground reports and survives quiet idle stretches — the naive "latest RESUMED wins" logic tore locks down incorrectly. Events are consumed incrementally from `lastEventTime` forward. Read the method's Doxygen comment before touching this.

**Persistence is split two ways** (`data/`):
- **Room** (`KemptDatabase`) holds the durable tables: `block_rules` (which apps to block) and `block_events` (the accountability event log — the *only* data that ever leaves the device). DAOs expose both `Flow` observers (for the UI) and `suspend` reads (for the monitor).
- **DataStore** (`LockStateStore`) holds the cheap, hot lock flags (`armed`, `armed_since`) and the partner passcode. The passcode is stored **only as a random-salted SHA-256 hash** and verified with a constant-time compare — never in the clear. Kept separate from Room so the monitor and `BootReceiver` can answer "is a lock active right now?" without touching the database.

**The accountability seam** (`sync/`): `AccountabilityService` is the one-way device→backend interface (heartbeat, event report, FCM token registration). The shipped implementation is `StubAccountabilityService` — a logged no-op that reports everything as "delivered" so the local pipelines run end-to-end without a server. **To add a real backend, implement this interface and change the `@Binds` in `di/AppModule.kt`; nothing else in the app changes.** `HeartbeatWorker` (WorkManager, every 15 min) pings the backend and flushes unsynced events; `KemptMessagingService` (FCM) receives partner unlock approvals / alerts.

**Anti-tamper is social, not technical** (by design — Android can't stop the device owner). Enforcement instead relies on: force-blocking Settings while armed, `BootReceiver` re-arming an active lock after reboot (`BOOT_REARM`), and the server heartbeat so the partner is alerted if the monitor goes silent (force-stop, revoked access, OEM battery-killer, uninstall). Do not try to build a technical cage — the model is that every bypass becomes a signal.

**Permissions** (`util/Permissions.kt`): the three special-access permissions (Usage Access, Draw-over-other-apps, battery-optimization exemption) can't be requested with an in-app dialog — the user must toggle each in a Settings screen. This object centralizes both the checks and the Settings intents; the UI re-checks them via a "refresh" tick after the user returns from Settings.

### File map (by responsibility)

- `KemptApplication.kt` — `@HiltAndroidApp` entry point.
- `MainActivity.kt` — the only Activity; all Compose UI (home screen, permission/passcode/blocklist cards, app picker, event log).
- `ui/HomeViewModel.kt` — UI state (`combine`d from lock flags + rules + events into a `StateFlow`) and all user actions (set passcode, toggle app, lock down, disarm, load installed apps).
- `monitor/` — `AppMonitorService` (polling loop + enforcement), `LockOverlay` (overlay lock), `LockActivity` (Settings-proof full-screen lock), `BootReceiver` (reboot re-arm).
- `data/` — `KemptDatabase` (Room entities/DAOs), `LockStateStore` (DataStore + passcode hashing).
- `sync/` — `AccountabilityService`/`StubAccountabilityService`, `HeartbeatWorker`, `KemptMessagingService`.
- `di/AppModule.kt` — Hilt singletons (DB, DAOs, store) and the `AccountabilityService` binding.

### DI note

Framework-owned components that Hilt can't constructor-inject use one of two patterns: services/activities use `@AndroidEntryPoint` + `@Inject lateinit` fields (`AppMonitorService`, `LockActivity`, `MainActivity`, `KemptMessagingService`); `BroadcastReceiver`/`Worker` use a Hilt `@EntryPoint` interface + `EntryPointAccessors` (`BootReceiver`, `HeartbeatWorker`) to avoid extra boilerplate/dependencies.
