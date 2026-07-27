# API Integration Standard — Static → Live (Sakhi mobile app)

> Purpose: convert an already-built screen from static/mock data to real backend API
> integration **predictably, with zero UI changes**. This exists to stop the mistake we
> hit once (a new screen rebuilt instead of reusing the existing one). Follow this for
> every screen: Beneficiaries, Dashboard, Visit Tracker, Profile, Beneficiary Profile,
> and every future beneficiary type (Child, Adolescent, …).

## Golden rule

**Do not touch the UI. Only swap the data source.** The screen, ViewModel, validation,
navigation, and all UI states stay exactly as they are. No new screen, no redesign.

## Why this is cheap here (the architecture already supports it)

Every screen depends on a **repository interface** (`DashboardRepository`,
`BeneficiaryRepository`, …), never on an API directly. ViewModels already model
`Loading / Error / Success` with `try/catch`. So integrating an API is:

1. Write one `Remote<X>Repository` implementing the existing interface.
2. Flip **one line** in `di/<X>Module.kt`: bind `Static<X>Repository` → `Remote<X>Repository`.

Nothing in `ui/` changes. Proven templates already live in the repo:
`RemoteAuthRepository`, `RemoteLookupRepository`, `RemoteFormsRepository`,
`RoomEnrollmentRepository` (offline-first).

## Intake — what to provide before I start (per endpoint)

No assumptions. If any of these is missing, it will be requested first:

1. **Endpoint** — HTTP method + URL (e.g. `GET /beneficiaries`).
2. **Request** — body/payload, path & query params, required headers. (Read screens often have none.)
3. **Response** — a real sample success JSON, plus error shape / status codes if available.

## Workflow (approval-gated — matches the repo CLAUDE.md)

1. **Verify the code** — open the screen's repository interface, model, and ViewModel.
   State exactly what is reused and what stays unchanged. Confirm: no new screen.
2. **Field mapping** — a table: API field → existing model field → transform (dates,
   enums, numeric parsing). Watch the **type-coercion trap** (see dynamic-form contract).
3. **Test cases** — *STOP for approval.* Cover success, empty, error, offline, and
   mapping edge cases.
4. **Implement** — write `Remote<X>Repository`, flip the DI binding, add offline handling
   (see below), and ship unit tests in the **same** delivery.
5. **Summary** — files changed, the mapping, and the command to verify.

## Offline-first requirement (do not skip)

These are field-worker screens. A `Remote<X>Repository` must not just call the network
blind — follow the Login/Enrollment pattern: check `ConnectivityChecker`, serve cached
data when offline, and (for writes) queue with an idempotency key + background sync.
Reads: cache last successful response. Writes: local-first + `X-Idempotency-Key` +
sync worker (never create duplicates on retry).

## Reuse Declaration (mandatory in every plan/PR)

To make a UI rebuild impossible, every plan and PR for an integration must state, up front:

- **Screen reused:** `<path to Screen + ViewModel + interface>`
- **New files:** only `Remote<X>Repository` (+ API interface / DTOs if needed) + tests
- **UI changes:** none

## Current status (as of 2026-07-23)

| Screen / Concern | Data source | Status |
|---|---|---|
| Login | Remote (`RemoteAuthRepository`) + offline cache | ✅ Integrated + offline |
| Pregnant-Woman registration | Dynamic form (`RemoteFormsRepository` + sync) | ✅ Integrated + offline * |
| Lookup / master data | Remote (`RemoteLookupRepository`) | ✅ Integrated |
| Dynamic Forms engine | Remote (`RemoteFormsRepository`) | ✅ Integrated + offline |
| Home / Dashboard | `StaticDashboardRepository` | 🔴 Needs `getSummary()` API |
| My Beneficiaries | `StaticBeneficiaryRepository` | 🔴 Needs `getBeneficiaries()` API |
| Visit Tracker (+ Geography) | `StaticVisitRepository` / `StaticGeographyRepository` | 🔴 Needs visits + geography API |
| Menu / Sakhi Profile | `StaticProfileRepository` | 🔴 Needs profile API |
| Beneficiary Profile | `StaticBeneficiaryProfileRepository` | 🔴 Needs `getBeneficiary(id)` API |

\* Known stub: consent video playback is deferred (placeholder marks it watched).

**Suggested order:** Dashboard → My Beneficiaries → Beneficiary Profile → Visit Tracker
(also needs Geography API) → Profile. Do the first one *with* the offline pattern so it
becomes the copy-paste template.

## Verify

No Android SDK in the agent sandbox — always build/test on a dev machine:

```
./gradlew :app:testDebugUnitTest
```
