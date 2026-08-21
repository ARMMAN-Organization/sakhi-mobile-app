# Test Cases — Beneficiary Profile (CR-014)

**Screen:** `ui/beneficiaryprofile/BeneficiaryProfileScreen` + `BeneficiaryProfileViewModel`
**Design source:** `docs/project-docs/Arogya Sakhi - Revamp/Beneficiary Profile Page.pdf` (updated 2026-07-03 16:49) + `…-1.pdf` (Mother + Child, mobile + tablet).
**Type:** Developer unit (ViewModel) + UI (Compose) + navigation integration.
**Note (design bug to fix in Figma):** the identity grid labels the second date "LMP" twice; the second is **EDD** — implemented as EDD.

Status legend: ☐ not yet implemented · ☑ implemented & passing.

---

## 1. ViewModel — `BeneficiaryProfileViewModelTest` (unit, coroutine test)

| # | Name | Given / When / Then |
|---|---|---|
| VM-1 | ☐ `emits loading then success for id` | Given a valid id in `SavedStateHandle`; When init; Then first state `isLoading=true`, then `isLoading=false`, `profile != null`, `hasError=false`. |
| VM-2 | ☐ `maps all profile fields` | Given repo returns a known record; Then every field (name, ageLabel, village, pada, husbandName, mobileNumber, status, riskLevel, diagnoses, lastVisitStats) matches the source. |
| VM-3 | ☐ `mother variant populates lmp and edd` | Given a MOTHER id; Then `lmp` & `edd` non-null, `dob` & `weight` null. |
| VM-4 | ☐ `child variant populates dob and weight` | Given a CHILD id; Then `dob` & `weight` non-null, `lmp` & `edd` null. |
| VM-5 | ☐ `repository error sets error state` | Given repo throws; Then `hasError=true`, `isLoading=false`, `profile=null` (no exception message surfaced). |
| VM-6 | ☐ `unknown or missing id sets error state` | Given id absent from `SavedStateHandle` (or not found); Then error state (defensive, no crash). |
| VM-7 | ☐ `retry after error loads successfully` | Given prior error; When `loadProfile()`; Then Loading then Success. |

## 2. Compose UI — `BeneficiaryProfileScreenTest` (compose-ui-test)

| # | Name | Given / When / Then |
|---|---|---|
| UI-1 | ☐ `shows loading indicator` | State loading → progress indicator displayed. |
| UI-2 | ☐ `shows error with retry` | State error → error text + Retry shown; Retry click invokes reload. |
| UI-3 | ☐ `header title date and back` | "See Beneficiaries" + today's date shown; back arrow click → `onBack`. |
| UI-4 | ☐ `identity name age active edit` | Renders "Aishwarya Pawar | 25", **Active** chip, **Edit** pill. |
| UI-5 | ☐ `mother shows lmp and edd not dob` | MOTHER profile → LMP + EDD labels/values shown; DOB/Weight absent. |
| UI-6 | ☐ `child shows dob and weight not lmp` | CHILD profile → DOB + Weight shown; LMP/EDD absent. |
| UI-7 | ☐ `diagnosis chips render` | One chip per diagnosis ("Sickle Cell", "Chronic Diabetes"). |
| UI-8 | ☐ `diagnosis section hidden when empty` | Empty diagnoses → no Diagnosis label/chips. |
| UI-9 | ☐ `risk badge reflects level` | riskLevel HIGH → red "High Risk" badge. |
| UI-10 | ☐ `stats tile per vital` | One tile per `VitalStat` with value + caption. |
| UI-11 | ☐ `abnormal stat highlighted` | Abnormal tile red-highlighted; normal tile not. |
| UI-12 | ☐ `stats card hidden when empty` | Empty `lastVisitStats` → no stats card. |
| UI-13 | ☐ `edit shows coming soon` | Edit click → "coming soon" toast (callback invoked). |
| UI-14 | ☐ `footer actions coming soon` | Delivery Form / Closure Form click → "coming soon" (CR-014b). |
| UI-15 | ☐ `tablet uses grid layout` | Width ≥600dp → 3-column identity grid + 2×2 stats grid. |
| UI-16 | ☐ `mobile uses stacked layout` | Width <600dp → stacked identity + tiles. |
| UI-17 | ☐ `long name does not break row` | Very long name ellipsizes; Edit pill stays visible. |

## 3. Navigation — integration

| # | Name | Given / When / Then |
|---|---|---|
| NAV-1 | ☐ `see profile from beneficiaries navigates` | Tap See Profile on a My Beneficiaries card → route `beneficiary/{id}` with correct id. |
| NAV-2 | ☐ `see profile from visit tracker navigates` | Tap See Profile on a Visit Tracker card → route with correct id. |
| NAV-3 | ☐ `back returns to origin` | Back from profile → previous screen restored. |

---

**Coverage mapping to scope:** VM-1..7 + UI-1..13, 15..17 + NAV-* = **CR-014a** (shell, identity, state/diagnosis, last-visit-stats, nav). UI-14 (footer) and the See Visits list belong to **CR-014b**.

## 4. See Visits list + footer — CR-014b

Repo (unit):

| # | Name | Given / When / Then |
|---|---|---|
| VIS-1 | ☑ `visit history has open lead then completed history` | b01 → visits non-empty; first is OPEN + START_VISIT + `startable=false`; rest COMPLETED; last label "Enrollment". |

UI (Compose):

| # | Name | Given / When / Then |
|---|---|---|
| VIS-2 | ☐ `see visits section renders one card per visit` | Header "See Visits" + one `VisitHistoryCard` per visit. |
| VIS-3 | ☐ `open visit shows days-remaining and Start Visit` | OPEN visit → lavender "N days remaining" chip + Start Visit button. |
| VIS-4 | ☐ `not-due open visit disables Start Visit` | `startable=false` → Start Visit disabled. |
| VIS-5 | ☐ `referral-incomplete shows red tag and Fill Form` | COMPLETED + `referralIncomplete` → red "Referral Followup Incomplete" chip + Fill Form. |
| VIS-6 | ☐ `completed visit shows See Data and risk chip` | COMPLETED + SEE_DATA → See Data (secondary) + red risk chip when `riskLabel` set. |
| VIS-7 | ☐ `visit action shows coming soon` | Any visit action click → "coming soon" toast. |
| VIS-8 | ☐ `footer delivery and closure coming soon` | Delivery Form / Closure Form click → "coming soon". |

---

# Test Cases — Remote Beneficiary Profile Fetch (CR-037)

**Bug fixed:** "Not yet assessed" / remote-only beneficiaries (no local enrolment draft synced to device) failed to open their profile — `ScheduleBackedBeneficiaryProfileRepository.getBeneficiary(id)` fell through to `StaticBeneficiaryProfileRepository`'s 14-sample-ID fixture (`b01`..`b14`) and threw `NoSuchElementException` for any real server id, surfacing as "We couldn't load this beneficiary."
**Files under test:** `data/beneficiaryprofile/ScheduleBackedBeneficiaryProfileRepository.kt`, its new profile mapper, `data/motherlink/BeneficiaryApi.kt` (extended `BeneficiaryDetailDto`).
**Type:** Repository unit tests (JVM), `ScheduleBackedBeneficiaryProfileRepositoryTest`.
**API contract confirmed by BE (this cycle):** `GET /api/v1/beneficiaries/{id}` now returns `riskLevel` (`none/mild/moderate/high`), `riskColor` (`GREEN/YELLOW/RED` — flagged by BE as a new convention, not a confirmed existing rule), `riskConditionSummaries[]` (`conditionCode`/`conditionName` nullable if risk-referral-service is briefly unreachable), `motherCaseDetails`/`childCaseDetails` (child adds `currentPhase`, `ccvOpeningRiskState`), and `lastVisitVitals` (from new `GET /beneficiaries/:beneficiaryId/latest-visit-vitals`, wired in, `null` on failure/no visits yet). `husbandName` intentionally not mapped (hidden field, out of scope).

Status legend: ☐ not yet implemented.

## 1. Core fetch path

| # | Name | Given / When / Then |
|---|---|---|
| RF-1 | ☐ `local miss falls through to remote fetch` | Given `LocalEnrolmentBeneficiarySource.findLocalBeneficiary(id)` returns null; When `getBeneficiary(id)`; Then `beneficiaryApi.detail(id)` is called (not the static repo). |
| RF-2 | ☐ `local hit never calls remote` | Given local source returns a record; Then `beneficiaryApi.detail` is never invoked. |
| RF-3 | ☐ `not-yet-assessed profile loads without error` | Given API returns `riskConditionSummaries: []`, `riskLevel: "none"`, `lastVisitVitals: null`; Then `getBeneficiary` returns a valid `BeneficiaryProfile` (no exception), `riskLevel = LOW`, `diagnoses = emptyList()`, `lastVisitStats = emptyList()`. |
| RF-4 | ☐ `assessed profile maps risk and diagnoses` | Given API returns `riskLevel: "high"`, `riskColor: "RED"`, one `riskConditionSummaries` entry with `conditionName: "Hypertension (High BP)"`; Then mapped profile has `riskLevel = HIGH`, `diagnoses = ["Hypertension (High BP)"]`. |
| RF-5 | ☐ `null conditionName entries are skipped, not crashed` | Given a `riskConditionSummaries` entry with `conditionName: null` (risk-referral-service degraded); Then that entry is excluded from `diagnoses`, no exception. |

## 2. Last-visit vitals mapping

| # | Name | Given / When / Then |
|---|---|---|
| RF-6 | ☐ `lastVisitVitals null maps to empty stats` | Given `lastVisitVitals: null`; Then `lastVisitStats = emptyList()` (no crash, no placeholder row). |
| RF-7 | ☐ `lastVisitVitals populated maps each vital` | Given a populated `lastVisitVitals` (weight, BP, hemoglobin, etc.); Then each maps to a `VitalStat(value, caption, abnormal)` preserving the `abnormal` flag. |

## 3. Mother / child case mapping

| # | Name | Given / When / Then |
|---|---|---|
| RF-8 | ☐ `mother case maps lmp/edd, not dob/weight` | Given `caseType: MOTHER`, `motherCaseDetails` populated; Then `lmp`/`edd` non-null, `dob`/`weight` null. |
| RF-9 | ☐ `child case maps dob/weight, not lmp/edd` | Given `caseType: CHILD`, `childCaseDetails` populated; Then `dob` (from `pii.dateOfBirth`) non-null, `lmp`/`edd` null; `currentPhase`/`ccvOpeningRiskState` carried through on the profile model even if not yet rendered by UI. |

## 4. Caching

| # | Name | Given / When / Then |
|---|---|---|
| RF-10 | ☐ `successful remote fetch is cached` | Given a successful `getBeneficiary(id)` call; Then the profile is persisted via `SecureKeyValueStore` keyed by id. |
| RF-11 | ☐ `cached profile returned when offline` | Given a prior successful fetch cached id X; When `beneficiaryApi.detail` throws `IOException` (offline) for id X; Then the cached profile is returned instead of an error. |

## 5. Failure handling

| # | Name | Given / When / Then |
|---|---|---|
| RF-12 | ☐ `404 with no cache sets error` | Given remote returns 404 and no cache exists for id; Then `getBeneficiary` throws/returns error state (not the static-repo fallback). |
| RF-13 | ☐ `malformed JSON with no cache sets error` | Given response body fails to deserialize; Then error state, no crash. |
| RF-14 | ☐ `network error with no cache sets error` | Given `IOException` and no cache; Then error state surfaced to ViewModel (existing VM-5 already covers the ViewModel side). |

## 6. Manual QA (not automatable)

| # | Name | Check |
|---|---|---|
| MQ-1 | ☐ | `riskColor` (GREEN/YELLOW/RED from API) visually matches the app's existing `RiskBadge` colors for LOW/MODERATE-MILD/HIGH — flag any mismatch since BE called this "a new convention, not confirmed." |
| MQ-2 | ☐ | On a real device, tap "See Profile" on a genuine "Not yet assessed" beneficiary — confirms end-to-end fix, not just unit coverage. |

---

**Coverage:** RF-1..14 = CR-037 core. MQ-1/2 = manual sign-off before merge.
