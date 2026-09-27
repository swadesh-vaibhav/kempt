# Kempt — Firebase Backend Spec

> **Purpose.** This is the contract between the Android **client** and the **Firebase**
> backend, documenting *what exists today* so the two can be developed independently.
> The client depends only on what's written here (collection paths, document shapes, the
> auth `uid`, and FCM message types); the backend may be reworked freely as long as it
> honours this contract. When Firebase itself is eventually swapped for a custom server,
> this is the surface that must be preserved.
>
> **Status:** pre-alpha. Firestore data model, security rules, Auth, and the client write
> path exist; Cloud Functions and the client's remote-unlock read path do **not** yet.

---

## 1. Project & environments

| | |
|---|---|
| Firebase project ID | `kempt1` |
| Project number | `423895631216` |
| Default CLI alias | `kempt1` (see `.firebaserc`) |
| Firestore database | `(default)`, location **`nam5`** (US multi-region) — *permanent* |

Config files at the repo root:

| File | Role |
|---|---|
| `.firebaserc` | Maps the `default` alias → project `kempt1`. |
| `firebase.json` | What the CLI deploys/emulates: Firestore rules+indexes, the `functions` codebase (TypeScript; `predeploy` runs `lint` + `build`), and the emulator ports. |
| `firestore.rules` | **Source of truth for access control** (see §5). Must be deployed to take effect. |
| `firestore.indexes.json` | Composite indexes — currently **empty** (none needed yet). |
| `functions/` | Cloud Functions codebase (TypeScript). **No functions implemented yet** — `src/index.ts` is boilerplate. |

`app/google-services.json` (client config) is **git-ignored** and required to build — see the README "Getting started" for local + CI setup.

---

## 2. Firebase products

| Product | Status | Notes |
|---|---|---|
| **Authentication** | ✅ In use | Google provider enabled; client signs in via Credential Manager (§4). |
| **Cloud Firestore** | ✅ In use | Data model §3, rules §5. Client writes via the accountability service (§6). |
| **Cloud Messaging (FCM)** | ◐ Partial | Client *receives* pushes (`KemptMessagingService`); backend *send* is not built. |
| **Cloud Functions** | ⛔ Scaffolded only | Directory + toolchain ready; zero functions written. Pairing + watchdog + dispatch are pending (§8). |
| **App Check** | ⛔ Not set up | Recommended hardening before launch. |

---

## 3. Firestore data model

Everything a user owns is nested under `users/{uid}` (uid = Firebase Auth id). Types are
Firestore types (`timestamp`, not epoch millis). "Immutable" = cannot change after create.

### `users/{uid}`
| Field | Type | Req | Notes |
|---|---|---|---|
| `displayName` | string (1–80) | ✓ | Safe, non-PII display name. |
| `createdAt` | timestamp | ✓ | Immutable. |

### `users/{uid}/devices/{deviceId}` — `deviceId` = `Settings.Secure.ANDROID_ID`
| Field | Type | Req | Notes |
|---|---|---|---|
| `platform` | string (1–20) | ✓ | Immutable; currently always `"android"`. |
| `armed` | bool | ✓ | Current lock state on the device. |
| `createdAt` | timestamp | ✓ | Immutable. |
| `fcmToken` | string (≤4096) | – | Push credential. Owner-only readable. |
| `lastHeartbeatAt` | timestamp | – | Updated each heartbeat. |

### `users/{uid}/events/{eventId}` — append-only accountability log
| Field | Type | Req | Notes |
|---|---|---|---|
| `type` | string enum | ✓ | See enum below. |
| `at` | timestamp | ✓ | Occurrence time; must be ≤ now + 5 min (allows offline backfill, blocks future-dating). |
| `packageName` | string (1–255) | – | Blocked app, when relevant. |
| `deviceId` | string (1–128) | – | Reporting device. |

`type` ∈ `lock_armed`, `lock_disarmed`, `block_enforced`, `unlock_success`,
`unlock_failed`, `usage_access_lost`, `boot_rearm`, `device_admin_enabled`,
`device_admin_disable_requested`, `device_admin_disabled` (mirrors
`BlockEvent`'s constants in the client).

### `users/{uid}/grants/{grantId}` — remote unlock/lock commands
| Field | Type | Req | Notes |
|---|---|---|---|
| `action` | string | ✓ | `unlock` or `lock`. |
| `issuedByUid` | string | ✓ | Must equal the creating partner's uid. |
| `createdAt` | timestamp | ✓ | |
| `status` | string | ✓ | `active` on create. |
| `expiresAt` | timestamp | cond. | **Required** for `unlock` (must be `now < expiresAt < now + 30d`); **absent** for `lock`. Timed unlock is enforced on-device by this field. |

### `pairings/{accountableUid}_{partnerUid}` — the user↔partner link (deterministic id)
| Field | Type | Notes |
|---|---|---|
| `accountableUid` | string | The monitored user. |
| `partnerUid` | string | The accountability partner. |
| `status` | string | `pending` / `accepted` / `revoked`. |
| `createdAt` | timestamp | |

---

## 4. Authentication

- **Method:** Google Sign-In through **Credential Manager** (`GetSignInWithGoogleOption`)
  → `GoogleAuthProvider` → `FirebaseAuth.signInWithCredential`. Firebase is the identity of record.
- **Client pieces:** `auth/AuthRepository.kt` (sign-in/out + `authState: Flow<FirebaseUser?>`),
  `auth/AuthViewModel.kt` (gate state), `ui/auth/SignInScreen.kt`.
- **Config:** OAuth *Web* client ID in `app/src/main/res/values/auth.xml`
  (`google_web_client_id`); build SHA-1 registered in the Firebase console.
- **The uid is the security boundary** — every Firestore document is scoped to it. All
  backend writes are **no-ops until a user is signed in** (rules require `request.auth`).
- **Roles:** a signed-in user can be the *accountable user* (owns a device, writes events)
  and/or a *partner* (reads a paired user's log, issues grants). The role is expressed by
  the `pairings` relationship, not a field on the user.

---

## 5. Security rules (access contract)

Defined in `firestore.rules`; deploy with `firebase deploy --only firestore:rules`.
Default-deny; all access requires auth. "Partner" = an **accepted** `pairings` doc exists
between the requester and the owner (and requester ≠ owner).

| Path | Read | Create | Update | Delete |
|---|---|---|---|---|
| `users/{uid}` | owner | owner + valid | owner + valid (createdAt immutable) | ✗ |
| `…/devices/{id}` | **owner only** | owner + valid | owner + valid (platform/createdAt immutable) | owner |
| `…/events/{id}` | owner **or** partner | owner + valid | ✗ | ✗ |
| `…/grants/{id}` | owner **or** partner | **partner only** + valid | ✗ | ✗ |
| `pairings/{id}` | the two parties | ✗ | ✗ | ✗ |

Key invariants the rules guarantee (and the backend/client must not assume around):
- **The accountable user can never unlock themselves** — `grants` create requires an
  accepted-partner check that excludes self.
- **The log is tamper-evident** — `events` are create-only; no edits or deletes.
- **`fcmToken` never leaks to a partner** — it lives only in the owner-only `devices` doc.
- **Pairings are not client-writable** — they must be created by a Cloud Function using
  the Admin SDK (see §8); otherwise a user could grant themselves access to another's data.

---

## 6. Client → Firebase (write contract)

The seam is the `AccountabilityService` interface (`sync/AccountabilityService.kt`),
implemented by `sync/FirestoreAccountabilityService.kt`:

| Interface call | Firestore operation |
|---|---|
| `report(BlockEvent)` | `add` a doc to `users/{uid}/events` (maps `BlockEvent.at` millis → `timestamp`; omits null `packageName`). |
| `sendHeartbeat()` | Transactional upsert of `users/{uid}/devices/{deviceId}` — sets `armed` (from `LockStateStore`) + `lastHeartbeatAt`. |
| `registerToken(token)` | Transactional upsert of the same device doc — sets `fcmToken`. |

Notes:
- Writes carry a **15s timeout**; on failure they return `false` and the event stays
  unsynced in Room for `HeartbeatWorker` to retry.
- **Read models** for future reads live in `sync/FirestoreModel.kt` (`UserDoc`,
  `DeviceDoc`, `EventDoc`, `GrantDoc`, `PairingDoc`) plus field-name constants (`Fs`).
- Writes use explicit maps (not the data classes) to satisfy the rules' strict field sets.

---

## 7. Firebase → Client (push contract)

Received by `sync/KemptMessagingService.kt` (FCM). Data-message `type` values:

| `type` | Client behaviour | Status |
|---|---|---|
| `unlock_approval` | Disarm the lock, cancel the heartbeat, notify the user. | Handler exists; **no backend sender**. |
| *anything with a body* | Show a notification (`title`/`body`). | Handler exists. |

`onNewToken` forwards refreshed FCM tokens to `registerToken` (§6).

---

## 8. Implemented vs. pending

**Implemented**
- Project, `.firebaserc`, `firebase.json`, emulator config.
- Firestore data model + security rules (`firestore.rules`).
- Google Sign-In (client) → Firebase Auth.
- Client write path (events, heartbeat, token) via `FirestoreAccountabilityService`.
- FCM receive path (`KemptMessagingService`).

**Pending — Cloud Functions** (all backend-authoritative; clients can't do these)
- **Pairing establishment** — validate an invite code, write the `pairings` doc via Admin
  SDK. *Blocking:* until this exists, no partner can read logs or issue grants.
- **Heartbeat watchdog** — scheduled function that flags devices gone silent mid-lock and
  pushes a partner alert.
- **FCM dispatch** — send `unlock_approval` / `partner_alert` (e.g. on new `events` or on
  a partner action).

**Pending — client**
- Remote-unlock **grants listener** + on-device `AlarmManager` expiry (reads `grants`,
  enforces `expiresAt`).
- **Sign-out** UI hook (`AuthViewModel.signOut()` exists, no button).
- Partner-mode UI (view a paired user's log, issue unlock/lock).

**Pending — hardening**
- App Check; release/Play App Signing SHA-1; a rules test suite; reconcile the offline
  event-duplication edge (Firestore's write queue vs. the Room+worker retry).

---

## 9. Local dev & deploy

```bash
# Emulators (auth:9099, functions:5001, firestore:8080, + UI)
firebase emulators:start

# Deploy pieces independently
firebase deploy --only firestore:rules
firebase deploy --only firestore:indexes
firebase deploy --only functions
```

Prerequisites (project setup, `google-services.json`, SHA-1, Web client ID) are in the
README "Getting started" section.

---

## 10. The contract boundary (separation of concerns)

**The client assumes only:**
1. A Firebase Auth identity whose `uid` scopes its data.
2. The collection paths and document shapes in §3, enforced by §5.
3. The FCM `type` values in §7.

**The backend assumes only:**
1. The client writes events/heartbeats/tokens exactly as in §6.
2. Grants it (or a partner) writes will be read and enforced on-device.

Because the client talks to a single interface (`AccountabilityService`) and a fixed
document contract, the backend can change — add Functions, restructure internals, or be
replaced by a custom server (e.g. Ktor + Postgres) — without touching the client, as long
as these shapes and semantics are preserved.
