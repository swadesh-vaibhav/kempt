# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Instructions

- Always explain any code you write in great detail — walk through what each part does and why.
- The user is new to Kotlin. Whenever explaining anything, keep this in mind: define Kotlin-specific concepts as they come up (coroutines, extension functions, flows, etc.).
- Documentation in this repo uses **Doxygen-style** comments (`/** */` with `@file`, `@brief`, `@details`, `@param`, `@return`, `@note`, `@warning`, `@property`, `@code`/`@endcode`, and cross-refs via `@ref Fully.Qualified.Name` / `#member`) — **not** Kotlin-native KDoc. Match this style when adding or editing docs. Every existing source file follows it; keep it consistent.

## What Kempt is

Kempt is an Android app (Kotlin + Jetpack Compose) that blocks distracting apps with one tap; getting back in requires a passcode held by an accountability partner, and bypass attempts are reported to that partner. It is **early/pre-alpha** — the README (`README.md`) describes the intended design; the code is scaffolding plus a working local lock. A **Firebase backend** (Auth + Firestore + FCM) is now wired: Google Sign-In gates the app and the network layer writes to Firestore (`FirestoreAccountabilityService`); Cloud Functions (pairing, watchdog, push dispatch) are **not built yet**. See `docs/firebase-spec.md` for the authoritative client↔Firebase contract and its implemented-vs-pending status.

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
- Firebase is **wired** (project `kempt1`): the `google-services` plugin is applied and `app/google-services.json` is **required to build** — it's git-ignored, so see the README "Getting started" for local + CI setup. Auth (Google Sign-In), Firestore, and FCM are active; Cloud Functions are scaffolded but empty (`functions/`).
- Debug with `adb logcat -s AppMonitor` (foreground-app detection) / `-s Accountability` (Firestore writes) / `-s AuthRepository` (sign-in).

## Architecture

Single-module Android app under `app/src/main/java/com/kempt/app/`, wired together with **Hilt** DI. There is one Activity (`MainActivity`) — a thin shell that just hosts the `KemptApp` composable; the real work happens in a foreground service and supporting components.

**The UI** (`ui/`) is a Jetpack **Navigation Compose** graph, all built around a single Activity-scoped `HomeViewModel` (obtained once in `KemptApp`, passed down — never re-fetched per destination, which would make duplicate instances). `KemptApp` first gates on **Google Sign-In** (unauthenticated users see `SignInScreen`), then reads `LockStateStore.onboardingComplete` and picks the graph's start: a **first-run onboarding wizard** (`ui/onboarding/`: grant permissions one at a time → pick apps → set passcode → `completeOnboarding()`), or the **main screen** (`ui/main/MainScaffold.kt`: a hamburger drawer + a three-tab bottom bar — Apps / Lockdown / Partner). While a lock is **armed**, `MainScaffold` collapses to just `LockedScreen` (a single "end lockdown" passcode prompt — no tabs or drawer). Screens are stateless composables fed values + lambdas; reusable pieces live in `ui/components/` (app picker, passcode form, permission checklist, event log, section card). See the File map below.

**The lock lifecycle** is the core flow to understand — it spans several files:

1. **Arm** — `HomeViewModel.lockDown()` sets the armed flag in `LockStateStore`, starts `AppMonitorService`, schedules `HeartbeatWorker`, and records a `LOCK_ARMED` event.
2. **Monitor** — `AppMonitorService` (a `specialUse` foreground service) runs a 1-second polling loop. Each tick it reads the armed flag, checks usage access is still granted (revocation mid-lock = tamper → `USAGE_ACCESS_LOST` event), determines the foreground app from `UsageStatsManager.queryEvents()`, and sends a heartbeat.
3. **Enforce** — when a blocked app is foregrounded, the service draws `LockOverlay` (a `TYPE_APPLICATION_OVERLAY` window) asking for the passcode. The system **hides overlays over the Settings app**, so for force-blocked packages the service instead launches `LockActivity` (a real full-screen Activity that can't be suppressed). Settings is force-blocked while armed (see `forceBlockedPackages`) precisely because it's where a user would revoke permissions or force-stop the app.
4. **Disarm** — a correct passcode (verified against `LockStateStore`) anywhere (`LockOverlay`, `LockActivity`, or the in-app `HomeViewModel.disarm()`) clears the armed flag, stops the service, cancels the heartbeat, and records `UNLOCK_SUCCESS`. A wrong code records `UNLOCK_FAILED` (a signal to the partner).

**Foreground-app detection is subtle** — see `AppMonitorService.updateLockTarget()`. `lockTarget` is a *latch*, not a per-tick snapshot: a blocked app's own `ACTIVITY_RESUMED` arms it and only that same app's `ACTIVITY_PAUSED`/`STOPPED` releases it. This deliberately ignores bogus "android"/launcher/SystemUI foreground reports and survives quiet idle stretches — the naive "latest RESUMED wins" logic tore locks down incorrectly. Events are consumed incrementally from `lastEventTime` forward. Read the method's Doxygen comment before touching this.

**Persistence is split two ways** (`data/`):
- **Room** (`KemptDatabase`) holds the durable tables: `block_rules` (which apps to block) and `block_events` (the accountability event log — the *only* data that ever leaves the device). DAOs expose both `Flow` observers (for the UI) and `suspend` reads (for the monitor).
- **DataStore** (`LockStateStore`) holds the cheap, hot lock flags (`armed`, `armed_since`), the partner passcode, and the `onboarding_complete` flag (which gates the first-run wizard). The passcode is stored **only as a random-salted SHA-256 hash** and verified with a constant-time compare — never in the clear. Kept separate from Room so the monitor and `BootReceiver` can answer "is a lock active right now?" without touching the database.

**The accountability seam** (`sync/`): `AccountabilityService` is the one-way device→backend interface (heartbeat, event report, FCM token registration). The bound implementation is now `FirestoreAccountabilityService`, which writes events/heartbeats/tokens to Firestore scoped to the signed-in user's uid — **inert until sign-in**, since the security rules require `request.auth`. `StubAccountabilityService` (a logged no-op) is kept for tests/offline; switch by changing the one `@Binds` in `di/AppModule.kt` — nothing else in the app changes, and this same seam is what would let Firebase later be swapped for a custom server. `HeartbeatWorker` (WorkManager, every 15 min) pings the backend and flushes unsynced events; `KemptMessagingService` (FCM) receives partner unlock approvals / alerts. The Firestore data model + security rules live in `firestore.rules`; `docs/firebase-spec.md` is the authoritative spec for this whole boundary.

**Anti-tamper is social, not technical** (by design — Android can't stop the device owner). Enforcement instead relies on: force-blocking Settings while armed, `BootReceiver` re-arming an active lock after reboot (`BOOT_REARM`), `KemptDeviceAdminReceiver` uninstall protection (a Device Admin registration Android won't let you uninstall over — *friction, not a wall* — that reports `DEVICE_ADMIN_DISABLE_REQUESTED`/`DEVICE_ADMIN_DISABLED` when someone peels it off), and the server heartbeat so the partner is alerted if the monitor goes silent (force-stop, revoked access, OEM battery-killer, uninstall). Do not try to build a technical cage — the model is that every bypass becomes a signal. The device admin declares an **empty policy set** (`res/xml/device_admin.xml`) on purpose: it can *only* block its own uninstall — no wipe/lock/password powers — which is least-privilege and the smallest Play review footprint.

**Permissions** (`util/Permissions.kt`): the three special-access permissions (Usage Access, Draw-over-other-apps, battery-optimization exemption) can't be requested with an in-app dialog — the user must toggle each in a Settings screen. This object centralizes both the checks and the Settings intents; the UI reads live grant state through `rememberPermissionStatus()` (`ui/components/PermissionChecklist.kt`), which re-checks on every `ON_RESUME` so returning from Settings auto-updates the ✓ (and advances the onboarding wizard) with no manual refresh. It also centralizes the Device Admin uninstall-protection grant (`isDeviceAdminActive`/`addDeviceAdminIntent`/`removeDeviceAdmin`): activated on a system consent screen (an optional, *recommended* grant — it does **not** gate `canLockDown`), but unlike the others it can be turned off in-app, since an app may always deactivate its own admin.

### File map (by responsibility)

- `KemptApplication.kt` — `@HiltAndroidApp` entry point.
- `MainActivity.kt` — the only Activity; a thin shell that sets `KemptTheme { KemptApp() }`.
- `ui/KemptApp.kt` — root composable: owns the shared `HomeViewModel` + `AuthViewModel`, requests `POST_NOTIFICATIONS`, and applies two outer gates — **sign-in** (unauthenticated → `ui/auth/SignInScreen.kt`), then **onboarding vs. main** from the `onboarding_complete` flag (nullable so it can render a blank frame until DataStore is read, avoiding a wrong-screen flash).
- `ui/navigation/` — `Destinations.kt` (route `sealed class` + `MainTab` enum), `KemptNavHost.kt` (the `NavHost`: onboarding nested graph + main + settings/permissions).
- `ui/onboarding/` — the first-run wizard: `OnboardingScaffold` (shared chrome), `OnboardingPermissionsStep` (one-grant-at-a-time), `OnboardingSelectAppsStep`, `OnboardingPasscodeStep`.
- `ui/main/` — `MainScaffold` (drawer + top bar + 3-tab bottom bar; branches to `LockedScreen` when armed), `AppsTab`, `LockdownTab`, `PartnerTab`, `LockedScreen`.
- `ui/settings/PermissionsScreen.kt` — drawer destination for full permission management.
- `ui/components/` — reusable composables: `AppPicker`, `PasscodeForm`, `PermissionChecklist` (`PermissionStatus` + `rememberPermissionStatus()` + `PermissionRow`), `EventLog`, `SectionCard`.
- `ui/HomeViewModel.kt` — UI state (`combine`d from lock flags + rules + events into a `StateFlow`), the `onboardingComplete` routing flow, and all user actions (set passcode, toggle app, lock down, disarm, complete onboarding, load installed apps).
- `monitor/` — `AppMonitorService` (polling loop + enforcement), `LockOverlay` (overlay lock), `LockActivity` (Settings-proof full-screen lock), `BootReceiver` (reboot re-arm), `KemptDeviceAdminReceiver` (uninstall protection + device-admin tamper signals; policy set in `res/xml/device_admin.xml`).
- `data/` — `KemptDatabase` (Room entities/DAOs), `LockStateStore` (DataStore + passcode hashing).
- `sync/` — `AccountabilityService` (interface), `FirestoreAccountabilityService` (bound impl, writes to Firestore) + `StubAccountabilityService` (fallback), `FirestoreModel.kt` (Firestore document read-models + the `Fs` field-name constants), `HeartbeatWorker`, `KemptMessagingService`.
- `auth/` — `AuthRepository` (Google Sign-In via Credential Manager → Firebase Auth; exposes `authState`), `AuthViewModel` (auth-gate state); the sign-in screen is `ui/auth/SignInScreen.kt`, gated at the top of `ui/KemptApp.kt`.
- `di/AppModule.kt` — Hilt singletons (DB, DAOs, store, `FirebaseFirestore`, `FirebaseAuth`) and the `AccountabilityService` binding.

### DI note

Framework-owned components that Hilt can't constructor-inject use one of two patterns: services use `@AndroidEntryPoint` + `@Inject lateinit` fields (`AppMonitorService`, `LockActivity`, `KemptMessagingService`); `BroadcastReceiver`/`Worker` use a Hilt `@EntryPoint` interface + `EntryPointAccessors` (`BootReceiver`, `HeartbeatWorker`, `KemptDeviceAdminReceiver` — a `DeviceAdminReceiver` is a `BroadcastReceiver`) to avoid extra boilerplate/dependencies. `MainActivity` is `@AndroidEntryPoint` too, but only so the Compose tree under it can obtain `HomeViewModel` and `AuthViewModel` via `hiltViewModel()` (in `KemptApp`) — it has no injected fields of its own.
