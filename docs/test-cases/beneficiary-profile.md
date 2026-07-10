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
