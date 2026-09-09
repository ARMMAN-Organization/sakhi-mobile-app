# Test Cases — Form Schema Offline Warmer (CR-Forms-01)

**Scope:** fix "Forms not loading when offline" (Asana task 1218225138592262, reported by Gagan
on Lenovo M9 Tab). Root cause: `RemoteFormsRepository.getActiveVersion(formCode)` only falls back
to a cached schema for a `formCode` that has been fetched successfully at least once on this
device; nothing pre-fetched every known form schema ahead of time, so a Sakhi opening a form type
for the first time with no signal hit a hard, unrecoverable "couldn't load" error. Fix: a
`FormSchemaWarmer`, identical in shape to the existing `ReconnectLookupWarmer`, that pre-fetches
every known form code on every connectivity-regained transition (and once at app start when
already online).

**Files under test:** `data/forms/KnownFormCodes.kt` (new) · `data/forms/FormSchemaWarmer.kt`
(new) · `data/forms/FakeFormsRepository.kt` (updated: added `requestedFormCodes` tracking,
additive/backward-compatible) · `SakhiApplication.kt` (updated: injects and starts the new
warmer alongside the two existing reconnect warmers).

**Type:** JVM unit tests only (matches repo convention — no `androidTest` Compose UI setup).

Status legend: ☐ not yet implemented · ☑ implemented & passing.

---

## 1. `KnownFormCodesTest` — the static list `FormSchemaWarmer` iterates

| # | Name | Given / When / Then |
|---|---|---|
| KC-1 | ☑ `ALL has no duplicate form codes` | Then `KnownFormCodes.ALL.size == KnownFormCodes.ALL.distinct().size`. |
| KC-2 | ☑ `ALL is not empty` | Then the list is non-empty — an empty list would silently warm nothing. |
| KC-3 | ☑ `ALL contains every declared constant` | Then the set of all 14 declared `const val`s exactly equals `ALL.toSet()` — catches a constant added but never added to `ALL`. |

## 2. `FormSchemaWarmerTest` — connectivity-driven pre-fetch behavior

| # | Name | Given / When / Then |
|---|---|---|
| FW-1 | ☑ `warms every known form code the moment connectivity returns` | Given offline at start (positive path, deferred trigger); When connectivity flips to online; Then `FakeFormsRepository.requestedFormCodes` contains every code in `KnownFormCodes.ALL`, exactly once each. Before the flip, nothing was requested. |
| FW-2 | ☑ `already-online at start warms once and a repeat online signal is not double-fired` | Given already online at start (the "app just launched with signal" case); Then the initial warm fires exactly `KnownFormCodes.ALL.size` requests; When a redundant "still online" signal arrives; Then no additional requests are made (negative/edge case — must not double-warm). |
| FW-3 | ☑ `each offline to online transition warms again` | Given online → offline → online; Then the second online transition warms the full list again (request count doubles) — confirms this isn't a one-shot warm, it re-arms every reconnect, which is the actual field scenario (signal drops in and out all day). |
| FW-4 | ☑ `one form code throwing does not stop the rest from being warmed` | Given a `FormsRepository` fake that throws for one specific form code (`REFERRAL_VISIT`); When warmed; Then every other known form code was still attempted, including the ones ordered after the one that threw — negative/resilience case, a single bad response must not leave the rest of the day's forms uncached. |
| FW-5 | ☑ `warmAll fetches every known form code exactly once when called directly` | Direct unit check of `warmAll()` independent of the connectivity flow, isolating the fetch-loop behavior from the Flow-collection wiring. |

## 3. Manual / on-device check (not JVM-testable)

| # | Name | Steps |
|---|---|---|
| MAN-1 | ☐ Fresh-device offline repro no longer reproduces | Fresh install → log in once online → airplane mode → open a form type never opened before on this device (e.g. Beneficiary Reopen, or an ANC visit) → form loads instead of showing the load-error screen, because `FormSchemaWarmer` already warmed it at app start / on first connectivity. |
| MAN-2 | ☑ (code-reviewed, not device-run) `SakhiApplication` wiring | `formSchemaWarmer.start(applicationScope)` is called in `onCreate()` alongside the two existing reconnect warmers — no test harness exists for `Application.onCreate()` in this repo (same precedent as the existing warmers, which are also only unit-tested at the warmer level, not at the `SakhiApplication` level). |

## Explicitly out of scope for this change

- **Error-message copy** ("couldn't load" is still generic, not yet distinguishing "never
  downloaded — connect once" from a transient failure) — flagged as a fast-follow in the CR-Forms-01
  gap analysis doc, not implemented here to keep this change scoped to the actual defect.
- **Centralizing the 7+ existing per-screen `FORM_CODE` constants** to reference `KnownFormCodes`
  instead of their own local `const val` — deliberately not done; `KnownFormCodes` is additive and
  used only by the new warmer, so this change touches no existing screen/ViewModel behavior.
