# Kempt

**One-tap lockdown for distracting apps — where only someone else can let you back in.**

Kempt is an Android app that blocks distracting apps with a single tap. Getting back
in requires a passcode from an accountability partner, and every attempt to break or
tamper with the block is reported to that partner. The bet is simple: discipline you
owe to another person holds far better than discipline you owe only to yourself.

> Status: **early planning / pre-alpha.** This README captures the intended design, not
> a shipped product.

---

## The problem

If the discipline and accountability of someone's phone usage is left entirely to
themselves, the problem is close to unsolvable — willpower loses to the reflex to
scroll. People find it much easier to stay disciplined when other people know about
it. Social accountability is the lever.

## The idea

Lock down all distracting apps with one tap. Opening them again requires a
passcode/OTP held by someone else (a partner, friend, or family member). The point
isn't a technical cage — it's that breaking the block is no longer a private act.

## Design decisions

### Blocking: UsageStats + overlay, not AccessibilityService

Kempt detects the foreground app by polling `UsageStatsManager` and, when a blocked
app comes forward during an active lock, draws a full-screen lock overlay via
`SYSTEM_ALERT_WINDOW`. This is deliberately chosen over the more convenient
`AccessibilityService` approach because:

- Google Play tightened its AccessibilityService policy (enforcement from **Jan 28,
  2026**); non-accessibility uses face rigorous review or removal.
- Android 17's **Advanced Protection Mode** blocks non-accessibility apps from using
  the accessibility API entirely.

Polling costs a little latency and battery, but it keeps Kempt publishable and keeps
it working even under Advanced Protection Mode.

### Anti-tamper is social, not technical

On Android, the device owner can always force-stop, revoke permissions, or uninstall
the app — no API prevents this (unlike iOS `ManagedSettings`). So Kempt does not try
to win a technical arms race. Instead, **every bypass becomes a signal to the
partner.** The device sends a heartbeat to the backend; if heartbeats stop during an
active lock (force-stop, revoked usage access, an OEM battery-killer, uninstall
attempt), the backend notices the silence and notifies the partner. Device Admin is
used only to make uninstall inconvenient — as friction, not a wall.

### Accountability model

- **Notify-on-break / notify-on-tamper** (the differentiator): partner is pushed a
  notification whenever a block is broken or the app is tampered with. Async, and
  degrades gracefully when the partner is unreachable.
- **Partner-set passcode** to unlock. Works offline.
- **Real-time remote OTP approval** is deferred to v2 (fragile — hard-depends on the
  partner being reachable that second).

### Privacy

Raw app-usage data stays **on-device**. Only accountability events (block broken,
tamper detected, heartbeat) sync to the backend. This keeps the Data Safety
declaration simple and is a genuine trust feature.

---

## Architecture

```
User's phone — Kempt app
├── App monitor        polls UsageStatsManager for the foreground app
├── Block engine       draws the SYSTEM_ALERT_WINDOW lock overlay
├── Rules & state      Room (blocklist, schedules, event log) + DataStore (lock flags)
└── Sync client        FCM + REST: pushes events/heartbeats, receives unlock decisions
        │
        ▼
Kempt backend          auth · pairing · event log · heartbeat watchdog · push dispatch
        │
        ▼
Partner's phone        Kempt (partner mode): receives alerts, approves unlocks
```

## Tech stack

- **Language / UI:** Kotlin, Jetpack Compose
- **Async:** Coroutines + Flow
- **Local storage:** Room + DataStore
- **Background:** Foreground Service (the monitor) + WorkManager (watchdog, periodic
  sync) + AlarmManager (schedule triggers, heartbeat)
- **DI:** Hilt
- **Push:** Firebase Cloud Messaging
- **Backend:** Firebase (Auth + Firestore + Cloud Functions + FCM) for speed, or a
  small Ktor / Cloud Run + Postgres service for more control

## MVP scope

- UsageStats monitor + overlay block
- Manual app selection
- Schedules + one-tap lockdown
- Partner static passcode to unlock
- Notify-on-break and notify-on-tamper via FCM
- Boot persistence (re-arm active blocks on reboot)
- Permission onboarding flow
- Device Admin uninstall protection (friction + a tamper signal when it's removed)

## Later (post-MVP)

- Real-time remote OTP / partner approval
- Website blocking (local VPN — a separate Play policy conversation)
- AccessibilityService "strong mode" (with a proper policy declaration)
- Usage insights / stats
- iOS (a very different build — Family Controls / ManagedSettings)

## Play Store requirements to design around

- **`targetSdk 36`** — new apps must target Android 16 (API 36) since Aug 31, 2026.
  `minSdk 26` is a sane floor.
- **Foreground service type** — likely `specialUse` (needs a Play Console
  declaration); `dataSync` has a 6-hour/day cap on API 35+ and won't sustain a
  persistent monitor.
- **`QUERY_ALL_PACKAGES`** — needed for the app picker; restricted permission,
  requires a declaration.
- **Special-access permissions** — Usage Access, overlay, and battery-optimization
  exemption each require sending the user into Settings; design a proper
  permission-priming flow.
- **OEM battery-killers** — Xiaomi / Oppo / Vivo / Samsung; request the battery
  exemption and rely on the server heartbeat as the backstop.
- **Privacy policy + Data Safety form** — mandatory once the backend handles data.

---

## Getting started

_TBD._ Android project scaffolding, `applicationId` (to be derived from a secured
domain), and backend setup to follow.
