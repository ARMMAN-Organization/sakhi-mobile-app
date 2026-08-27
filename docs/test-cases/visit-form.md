# Test Cases — ANC Visit Form + Pre-Visit Health History (CR-016)

**Screens:** `ui/previsithealthhistory/` (read-only reference screen, FR-S-4.6) +
`ui/visitform/` — 4-tab stepper (Visit Data → Summary → Health Info → Referral).
**Design source:** `docs/project-docs/Arogya Sakhi - Revamp/Pre visit health History.pdf`,
`Visit Form.pdf` (purple/current frames, mobile + tablet variants both present).
**Field spec source:** Google Sheet "Revised App Form Final 20.3.26" (Drive ID
`1N8cms-5YlNdwqrUm7qe6P-mM7Gm2ef50fjIl8uLARsc`), tab `ANC visit form` — Q1–56
(see memory `arogya-sakhi-anc-visit-form-cr016` for the full extracted spec).
**Scope agreed (2026-07-14):** ANC Visit (routine) only — Delivery/PP/NN combined
session, INC, CCV, Referral-Follow-up-form deferred to their own CRs. Risk detection
uses static thresholds taken verbatim from the Excel spec's "Risk condition
calculations" column (GoRules pack not built yet — swap later, no interface change
expected since a repository already abstracts the source).
**Runnable tests:** JVM units only per repo convention —
`PreVisitHealthHistoryViewModelTest`, `VisitFormViewModelTest`,
`StaticVisitFormRepositoryTest`, `VisitRiskAssessmentTest` (from CR-016c). UI/NAV
cases below are spec, verified via batched visual QA.

Status legend: ☐ not yet implemented · ☑ implemented & passing.

---

# CR-016a — Pre-Visit Health History (read-only) + Visit Form shell/tabs scaffold

## Scope

- **Pre-Visit Health History screen** (FR-S-4.6): beneficiary header, Summary card
  (LMP/EDD + permanent risk-condition chips e.g. Sickle Cell/Chronic Diabetes), up to
  5 risk-factor trend cards (BP, Hb, Weight, Blood Sugar, Temperature — only the ones
  actually flagged in the last 2 visits, never more than 5), non-risk vitals shown
  plainly. Entirely read-only; no edit affordance anywhere on this screen.
  Skipped outright on a beneficiary's 1st visit (no prior visit data exists yet) —
  the flow goes straight from "Start Visit"/"Fill Form" to the Visit Form.
- **Visit Form shell**: 4-tab stepper (Visit Data / Summary / Health Info / Referral),
  same interaction pattern as the Enrollment stepper (`AppTabRow`, forward/back
  buttons in a pinned footer, discard-on-exit confirmation). Visit Data's own
  Tests/Symptoms/History sub-tabs are scaffolded but their field content is CR-016b.
  Summary/Health Info/Referral bodies are placeholders in 016a (mirrors how
  Enrollment 015a scaffolded Health History/Summary before 015c/d filled them in).
- **Navigation wiring**: `VisitHistoryCard`'s `START_VISIT` and `FILL_FORM` actions
  (currently routed to a generic "coming soon" toast via `onComingSoon` in
  `BeneficiaryProfileScreen.kt`) now route to this flow instead.
- **Discarded-on-exit** per FR-S-4.2: no partial save. Exiting mid-form (system back
  or header back) always confirms and discards the in-memory draft — there is no
  "resume" state, matching Enrollment's behavior.

## 1. ViewModel — `PreVisitHealthHistoryViewModelTest` (unit, coroutine test)

| # | Name | Given / When / Then |
|---|---|---|
| PVH-1 | ☐ `loads beneficiary header and summary` | Given a beneficiary with ≥1 completed prior visit; When the screen loads; Then name/age, Village\|Pada, husband's name, mobile, LMP, EDD are populated from the repository. |
| PVH-2 | ☐ `shows only chronic/permanent condition chips that are actually flagged` | Given a beneficiary with Sickle Cell Disease + Chronic Diabetes flagged; Then both chips render; Given no permanent condition flagged; Then the chip row is empty (not shown). |
| PVH-3 | ☐ `surfaces at most 5 risk-factor cards, only for flagged factors` | Given prior-visit data with 2 of the 5 possible factors (BP, Hb, Weight, Blood Sugar, Temperature) at risk; Then exactly those 2 risk cards render; the other factors (if present as plain vitals) render in the non-risk section instead. |
| PVH-4 | ☐ `each risk-factor card shows last-2-visits + today's reference value` | Given a risk factor's trend data; Then the card shows exactly 3 value slots (visit N-2, visit N-1, "Last Visit") with risk-appropriate color coding, matching the Excel's per-field risk bands (e.g. BP severe ≥160/≥110). |
| PVH-5 | ☐ `screen is skipped entirely on first visit` | Given a beneficiary with zero completed prior visits; When `VisitHistoryCard`'s Start Visit is tapped; Then navigation goes directly to the Visit Form — Pre-Visit Health History is never shown (no empty/placeholder state needed). |
| PVH-6 | ☐ `see profile navigates without discarding anything` | When `onSeeProfile()`; Then navigates to `beneficiary/{id}` (nothing to discard — screen is read-only, no draft exists). |
| PVH-7 | ☐ `start visit navigates to the visit form for this beneficiary+visit` | When `onStartVisit()`; Then navigates to `visit-form/{beneficiaryId}/{visitId}` carrying the correct visit identity forward. |

## 2. ViewModel — `VisitFormViewModelTest` (unit, coroutine test)

### Shell / stepper

| # | Name | Given / When / Then |
|---|---|---|
| VF-1 | ☐ `initial state opens on Visit Data tab` | Given a fresh ViewModel for a given beneficiary+visit; Then `currentStep = VISIT_DATA`, `furthestStep = VISIT_DATA`. |
| VF-2 | ☐ `step order is fixed` | Steps advance strictly VISIT_DATA → SUMMARY → HEALTH_INFO → REFERRAL; `goToStep()` beyond `furthestStep` is ignored (mirrors Enrollment `EnrollmentStep` gating). |
| VF-3 | ☐ `tab back-navigation to completed steps allowed` | Given `furthestStep = SUMMARY`; When `goToStep(VISIT_DATA)`; Then allowed; When `goToStep(REFERRAL)`; Then ignored. |
| VF-4 | ☐ `visit data sub-tabs default to Tests` | Given step VISIT_DATA; Then the active sub-tab is `TESTS` (per the design's default focus state); `goToSubTab(SYMPTOMS)`/`goToSubTab(HISTORY)` switch freely (sub-tabs are not gated — only the top-level stepper is). |
| VF-5 | ☐ `next from Visit Data placeholder is ungated in 016a` | Given step VISIT_DATA (016a placeholder body, no fields yet); When `goToSummary()`; Then advances — real field-completeness gating arrives with 016b (flag this explicitly as a KNOWN 016a LIMITATION in the delivery summary, same pattern as Enrollment's HEALTH_HISTORY/SUMMARY note). |
| VF-6 | ☐ `back from visit data returns to caller` | Given step VISIT_DATA (the first stepper step); When `goBack()`; Then the screen exits (there is no step before VISIT_DATA inside this stepper — unlike Enrollment, there's no separate ENTRY step). |
| VF-7 | ☐ `in-flow back moves one step` | Given step SUMMARY; When `goBack()`; Then `currentStep = VISIT_DATA`, draft retained. |

### Discard-on-exit (FR-S-4.2)

| # | Name | Given / When / Then |
|---|---|---|
| VF-8 | ☐ `mid-flow exit requires confirmation` | Given any step; When system back or header back is pressed; Then an exit-confirmation dialog appears (never a silent exit), same UX as Enrollment's `ExitConfirmationDialog`. |
| VF-9 | ☐ `confirmed exit discards the entire draft` | When exit is confirmed; Then all in-memory Visit Data/Summary/Health Info/Referral state is cleared and the visit remains in `OPEN` state (FR-S-4.2 — no partial-form persistence, ever). |
| VF-10 | ☐ `cancelled exit resumes exactly where the user was` | When the exit dialog is dismissed; Then `currentStep` and all draft state are unchanged. |

### Critical-condition mid-form banner (FR-S-4.4) — scaffold only in 016a

| # | Name | Given / When / Then |
|---|---|---|
| VF-11 | ☐ `critical banner API exists but is unwired in 016a` | The ViewModel exposes a `criticalCondition: CriticalCondition?` state slot and a `dismissCritical()` action (scaffold for 016b/c, which will set it from real clinical thresholds); Then in 016a nothing ever populates it — confirms the shape without wiring detection logic yet. |
| VF-12 | ☐ `dismissing a critical banner closes the form` | Given `criticalCondition` is non-null (test sets it directly, since nothing produces it yet in 016a); When `dismissCritical()`; Then the form discards and exits to the referral flow per FR-S-4.4 Option B (closing the banner closes the form) — this behavior is unit-testable now even though no 016a code path triggers it yet. |

## 3. Compose UI — spec (verified via batched visual QA; no runnable Compose tests per repo convention)

| # | Name | Given / When / Then |
|---|---|---|
| UI-1 | ☐ `pre-visit header matches design` | Back arrow "See Beneficiaries" + today's date, Sakhi avatar (BackHeader pattern); beneficiary name + age, Village\|Pada / Husband's Name / Mobile No rows. |
| UI-2 | ☐ `pre-visit summary card` | "Summary" title + "High Risk"/appropriate risk-level pill (right-aligned), LMP/EDD two-up tiles, condition chips row below. |
| UI-3 | ☐ `pre-visit risk-factor cards` | Each card: colored header bar with factor name + risk-level pill, 3 value tiles (2 dated + "Last Visit"), value color matches risk severity (red/amber/green per token, never inline hex). |
| UI-4 | ☐ `pre-visit non-risk vitals` | Weight/Temperature (when not flagged as risk) render as plain white tiles, no risk pill. |
| UI-5 | ☐ `pre-visit footer` | Pinned "See Profile" (secondary) + "Start Visit →" (primary) buttons, divider above. |
| UI-6 | ☐ `visit form header + tabs` | "Visit Tracker" back caption + date; "Visit N Form" title (N from the visit identity passed in); 4-tab `AppTabRow` (Visit Data/Summary/Health Info/Referral) matching Enrollment's tab styling. |
| UI-7 | ☐ `visit data sub-tabs` | Tests/Symptoms/History pill-style sub-tab row per the design (visually distinct from the outer 4-tab stepper). |
| UI-8 | ☐ `016a placeholder bodies` | Visit Data/Summary/Health Info/Referral each show a neutral "step pending" placeholder text (same component as Enrollment's 015a `enrollment_step_pending` pattern) until their respective sub-CR lands. |
| UI-9 | ☐ `tablet layout` | 800×1280: content column with tablet width tokens, tab rows full-width distributed evenly (same `distributeEvenly` pattern as Enrollment). |
| UI-10 | ☐ `strings in EN + MR` | All new strings present in both `values/` and `values-mr/`. |

## 4. Navigation — integration (spec)

| # | Name | Given / When / Then |
|---|---|---|
| NAV-1 | ☐ `start visit / fill form routes to pre-visit or visit form` | `VisitHistoryCard`'s `START_VISIT`/`FILL_FORM` action, currently `onComingSoon` in `BeneficiaryProfileScreen.kt`, now routes to `previsit-health-history/{beneficiaryId}/{visitId}` if prior visit data exists, else directly to `visit-form/{beneficiaryId}/{visitId}`. |
| NAV-2 | ☐ `pre-visit start visit opens visit form` | From Pre-Visit Health History, "Start Visit →" → `visit-form/{beneficiaryId}/{visitId}`. |
| NAV-3 | ☐ `pre-visit see profile opens beneficiary profile` | "See Profile" → `beneficiary/{beneficiaryId}`, no confirmation needed (nothing to lose). |
| NAV-4 | ☐ `visit form back from first step confirms exit to caller` | System back from `VISIT_DATA` → exit-confirmation → confirm → returns to whichever screen launched the flow (Pre-Visit Health History or Beneficiary Profile, not always Home). |
| NAV-5 | ☐ `other visit-history actions unaffected` | `SEE_DATA` and `REFERRAL` actions on already-completed visits keep routing to `onComingSoon` in 016a (Visit Stats detail and standalone Referral Follow-up form are separate, not-yet-scoped CRs). |

---

# CR-016b — Visit Data fields (Q1–56)

**Scope agreed (2026-07-14):** all 56 fields from the `ANC visit form` Excel tab,
grouped into Tests (Q12–32) / Symptoms (Q17, Q18–22) / History (Q1–11, Q33–56),
with the full FR-S-4.7 validation framework and critical-pathway detection
(FR-S-4.4) wired to the `reportCriticalCondition`/`dismissCritical` scaffold from
016a. Risk-level computation and the Summary banner stay out of scope (016c) —
016b only captures and validates raw values. HIV test is intentionally NOT
included (enrollment-only per Excel spec — confirmed, not a gap). Field spec:
see memory `arogya-sakhi-anc-visit-form-cr016` / Drive tab `ANC visit form`.

**New state:** `VisitDataState` (mirrors `HealthHistoryState`'s pattern — raw
1-based codes for dropdown/radio, `Set<Int>` for multi-select, `showValidationBanner`,
per-field error properties, `isComplete` gate) added to `VisitFormUiState.visitData`.
`goToSummary()` becomes gated on `visitDataState.isComplete` (replacing 016a's
ungated placeholder advance).

## 1. ViewModel — `VisitFormViewModelTest` additions (unit)

### Visit tracking & pregnancy dating (Q1–11)

| # | Name | Given / When / Then |
|---|---|---|
| VD-1 | ☐ `visit date defaults to today and rejects future dates` | Q1 pre-filled with today; editing to a future date → error, not saved. |
| VD-2 | ☐ `met beneficiary no ends the form` | Q3 = No; When any forward navigation attempted; Then form is blocked at Q4 (reason dropdown, mandatory) and Visit Data cannot complete — mirrors FR-S-4.2 discard (this visit produces no clinical data). |
| VD-3 | ☐ `rch number and lmp auto-populate from registration` | Q5/Q6 pre-filled from the beneficiary's stored Enrollment/Personal Info draft; editable. |
| VD-4 | ☐ `editing lmp recalculates ga and edd` | Q7=Yes/Q8 LMP edited; Then current gestational age (Q10) and EDD (Q11) recompute (Category 4 auto-calc), matching Enrollment's `setLmp` mirror pattern. |
| VD-5 | ☐ `sonography photo required when lmp is edited` | Q7=Yes path; Q9 sonography image mandatory before the edited LMP is accepted (Category 6 — live capture only, no gallery, same `TakePicture`+`FileProvider` pattern as consent photo). |

### Tests sub-tab — Anthropometry & Diagnostics (Q12–32)

| # | Name | Given / When / Then |
|---|---|---|
| VD-6 | ☐ `height captured once, read-only thereafter` | Q12 editable only on the beneficiary's first visit; auto-populated (non-editable) on subsequent visits, range 120–190cm. |
| VD-7 | ☐ `bmi auto-calculated from height and weight` | Q14 = weight / height(m)², computed not entered; baseline BMI reused on later visits per Q12's rule. |
| VD-8 | ☐ `bp systolic/diastolic range validation` | Systolic 70–300, Diastolic 40–130 (Category 2); out-of-range → inline error before proceeding. |
| VD-9 | ☐ `hb range and stale-value confirmation prompt` | Range 1–18 g/dl; if new value differs from last visit's by more than ±2, a confirmation prompt appears ("please confirm the values or redo the test") without blocking submission once confirmed. |
| VD-10 | ☐ `blood glucose and urine test range/options` | Glucose 40–400 mg/dl; urine test multi-select where selecting "Normal" (per Excel note) disables the other options (Category 5). |
| VD-11 | ☐ `fetal movements/heart rate/fundal height hidden before 20 weeks` | Given current GA < 20 weeks; Then Q30–32 are not shown and not required; Given GA ≥ 20 weeks; Then shown and mandatory (Category 5). |
| VD-12 | ☐ `temperature range validation` | 94–105°F; out-of-range → inline error. |

### Symptoms sub-tab (Q17, Q18–22)

| # | Name | Given / When / Then |
|---|---|---|
| VD-13 | ☐ `danger sign checklist is multi-select with none-of-the-above exclusivity` | Q17: selecting "No abnormal signs and symptoms" clears/disables all other options and vice versa (same `toggleMulti` exclusivity pattern as Enrollment Q43/Q58). |
| VD-14 | ☐ `palm nails sclera skin are independent single-selects` | Q18/Q19/Q20 each an independent 3-option dropdown (Pale/Yellow/Normal), no cross-field exclusivity. |
| VD-15 | ☐ `swelling and dehydration multi-select store per-option booleans` | Q21/Q22 store one boolean per listed option, per the Excel's "create variable for each option" note (same pattern as Enrollment's multi-select fields). |

### History sub-tab (Q33–56)

| # | Name | Given / When / Then |
|---|---|---|
| VD-16 | ☐ `td vaccination status mirrors enrollment td pattern` | Q33: None exclusive against Td1/Td2/Booster; date enabled only for a ticked dose; ordering Td1 ≤ Td2 ≤ Booster; no future dates — identical rules to Enrollment `HealthHistoryState.tdDateError`, reused not reimplemented. |
| VD-17 | ☐ `ifa non-consumption reason required only when ifa is no` | Q36=No → Q38 reasons multi-select becomes mandatory; Q36=Yes → Q37 tablet count (0–35) mandatory instead, Q38 hidden. |
| VD-18 | ☐ `sickle cell and advised delivery place auto-populate, editable` | Q42/Q43 pre-filled from the previous visit's answer; editable this visit. |
| VD-19 | ☐ `usg detail fields conditional on usg done` | Q48=Yes → Q49/Q50/Q51 mandatory (date ≤ today, > LMP); Q48=No → Q49–51 hidden. |
| VD-20 | ☐ `mental health and birth-preparedness are independent yes/no fields` | Q45/Q46/Q52/Q53/Q54 each independently mandatory Yes/No, no conditional relationships between them. |
| VD-21 | ☐ `counselling checklist is optional-per-item multi-select` | Q56: each guidance topic stored as an independent boolean; the field as a whole does not block completion if left fully unchecked (advisory checklist, not a diagnostic gate). |

### Critical-pathway detection (FR-S-4.4)

| # | Name | Given / When / Then |
|---|---|---|
| VD-22 | ☐ `severe hypertension with symptoms reports a critical condition` | BP systolic > 160 AND (dizziness OR severe breathlessness OR swelling from Q17/Q21); Then `reportCriticalCondition(...)` is invoked with the hypertension message; dismissing it discards and exits the form (reuses 016a's `dismissCritical()`). |
| VD-23 | ☐ `severe anaemia with dizziness reports a critical condition` | Hb < 7 AND dizziness (Q17); Then critical condition reported. |
| VD-24 | ☐ `hypoglycaemia with symptoms reports a critical condition` | Blood glucose < 70 AND (dizziness OR cold sweat, Q17); Then critical condition reported. |
| VD-25 | ☐ `any danger sign selected reports a critical condition` | Any Q17 option other than "No abnormal signs" ticked; Then critical condition reported immediately (Excel: "under any condition ... stop filling form and accompany to health facility"). |
| VD-26 | ☐ `mild/moderate values do not trigger critical condition` | BP 140/90 (moderate, not severe), Hb 9 (mild) with no accompanying symptom; Then `criticalCondition` stays null — moderate/mild cases are a 016c risk-banner concern, not a critical-pathway one. |

### Completion & navigation

| # | Name | Given / When / Then |
|---|---|---|
| VD-27 | ☐ `incomplete visit data blocks summary and shows banner` | Any mandatory (visible) field missing/invalid; When `goToSummary()`; Then step stays VISIT_DATA, `showValidationBanner = true` (replaces 016a's ungated advance). |
| VD-28 | ☐ `banner clears once fields valid` | Fixing the last invalid/missing field hides the banner. |
| VD-29 | ☐ `complete visit data advances to summary` | All mandatory (and conditionally-visible) fields valid → `currentStep = SUMMARY`, `furthestStep` updated. |
| VD-30 | ☐ `sub-tab switching does not lose entered values` | Entering Tests values, switching to Symptoms then back to Tests → Tests values unchanged (single shared `VisitDataState`, sub-tab is display-only). |
| VD-31 | ☐ `exit discards visit data same as any other step` | `exitForm()` clears `visitData` along with the rest of the draft (FR-S-4.2 — no partial save, ever). |

## 2. Compose UI — spec (batched visual QA)

| # | Name | Given / When / Then |
|---|---|---|
| VD-UI-1 | ☐ `tests sub-tab layout` | Grouped clinical fields per the design's Tests frame; numeric fields show the unit + valid range as helper text. |
| VD-UI-2 | ☐ `symptoms sub-tab layout` | Danger-sign checklist + palm/sclera/skin dropdowns + swelling/dehydration checklists per the design. |
| VD-UI-3 | ☐ `history sub-tab layout` | Visit tracking, Td/IFA/calcium, ANC/USG, birth-preparedness and counselling sections, matching Enrollment's Health History section-grouping pattern. |
| VD-UI-4 | ☐ `conditional fields appear inline without layout gaps` | Q9/Q30–32/Q38/Q41/Q49–51/Q57 (birth weight) appear/disappear cleanly, mirroring Enrollment `HH-UI-3`. |
| VD-UI-5 | ☐ `immediate urgency banner` | A reported critical condition shows a full-width red "Immediate Urgency" banner (FR-S-4.4); closing it triggers the same exit-confirmation-free discard as `dismissCritical()` — no additional confirm dialog, since the Excel specifies immediate exit. |
| VD-UI-6 | ☐ `banner + footer` | "Complete all necessary fields" banner after a blocked Next (reuses Enrollment's banner component); footer unchanged ("Summary →" primary, no Back on this first step). |
| VD-UI-7 | ☐ `tablet + mobile insets, EN + MR` | 48/24dp insets; all 56 fields' strings present in both `values/` and `values-mr/`. |

## 3. Repository — no changes in 016b

Visit Data has no persistence yet (submit/save lands in 016d) — `VisitDataState`
lives only in `VisitFormViewModel`'s in-memory `uiState`.

---

---

# CR-016c — Summary tab (risk banner + read-only Tests/Symptoms review)

**Scope agreed (2026-07-14):** Summary tab shows an overall risk banner (`RiskBadge`
+ comorbidity chips), a read-only Tests card and a read-only Symptoms card, each row
with an "Edit" affordance that jumps back to the matching Visit Data sub-tab. Risk
tiers use the **exact bands from the Excel `ANC visit form` tab, column I ("Risk
condition calculations")** — extracted verbatim below, not invented:

| Vital | Mild | Moderate | Severe (→ `RiskLevel.HIGH`) |
|---|---|---|---|
| BP (systolic/diastolic) | 135–139 / 85–89 | 140–159 / 90–109 | ≥160 / ≥110 (also: hypotension, systolic <90) |
| Hemoglobin (g/dl) | 10–10.9 | 7–9.9 (or sickle cell positive) | <7 |
| Blood glucose (RBS) | — | — | ≥140 mg/dl → "GDM risk" (single threshold, no graded bands) |
| Temperature | — | — | ≥99°F "fever" / <96°F "hypothermia" (single threshold each) |
| MUAC | — | — | <23 cm → undernutrition risk (single threshold) |
| Height | — | — | <145 cm → short-stature risk (single threshold; only meaningful the visit height is captured, i.e. first visit) |
| BMI | — | — | <18.5 underweight / ≥35 overweight |
| Fetal heart rate | — | — | <120 or >160 bpm (only when shown, GA ≥ 20wk) |
| Fundal height | — | — | outside gestational-age-in-weeks ±2cm (only when shown, GA ≥ 20wk) |

**Assumption flagged (not silently decided):** the Excel's BP Mild band lists an
extra qualifier "+ history of HTN or gestational HTN" that Visit Data doesn't
capture as a discrete field. Mild BP is assessed on the numeric band alone,
ignoring that qualifier — documented here so it isn't mistaken for an oversight.

**Note:** FR-S-4.4's critical-pathway exit (016b) only fires when a severe reading
is *accompanied by a specific symptom* (dizziness/breathlessness/swelling/any
danger sign). An isolated severe reading with no accompanying symptom does **not**
trigger the mid-form exit and the beneficiary *can* reach Summary showing `HIGH` —
this is expected, not a gap; Summary's banner is exactly where an unaccompanied
severe finding is meant to surface.

**Comorbidity chips:** sourced from `BeneficiaryProfile.diagnoses` (free text, e.g.
"Sickle Cell", "Chronic Diabetes") via a new `VisitContext.comorbidities` field —
displayed verbatim, no keyword-remapping onto the design's 5 example labels (that
would risk mis-tagging real health data). Empty list → "No known conditions" text,
not a hidden row.

## 1. Data — `VisitRiskAssessment` (new pure functions, unit tests)

| # | Name | Given / When / Then |
|---|---|---|
| VS-1 | ☐ `bp mild band` | Systolic 135–139 or diastolic 85–89 (and not in a higher band) → `RiskLevel.MILD`. |
| VS-2 | ☐ `bp moderate band` | Systolic 140–159 or diastolic 90–109 → `RiskLevel.MODERATE`. |
| VS-3 | ☐ `bp severe band including hypotension` | Systolic ≥160 or diastolic ≥110 → `RiskLevel.HIGH`; systolic <90 (hypotension) → `RiskLevel.HIGH` with a distinct hypotension label. |
| VS-4 | ☐ `bp normal band` | Systolic <135 and diastolic <85 → `RiskLevel.LOW`. |
| VS-5 | ☐ `hb mild moderate severe bands` | 10–10.9 → MILD; 7–9.9 (or sickle cell positive) → MODERATE; <7 → HIGH; ≥11 → LOW. |
| VS-6 | ☐ `blood glucose gdm risk` | ≥140 mg/dl → `RiskLevel.HIGH` ("GDM risk"); <140 → LOW (single threshold, no mild/moderate tier per spec). |
| VS-7 | ☐ `temperature fever and hypothermia` | ≥99°F → HIGH ("fever"); <96°F → HIGH ("hypothermia"); 96–98.9°F → LOW. |
| VS-8 | ☐ `muac undernutrition risk` | <23cm → HIGH; ≥23cm → LOW. |
| VS-9 | ☐ `height short-stature risk` | <145cm (only when Q12 was captured, i.e. first-visit height) → HIGH; ≥145cm or not this visit's capture → LOW/not assessed. |
| VS-10 | ☐ `bmi underweight overweight` | <18.5 → HIGH (underweight); ≥35 → HIGH (overweight); 18.5–34.9 → LOW. |
| VS-11 | ☐ `fetal heart rate and fundal height only assessed when shown` | Given GA <20wk (fields hidden); Then neither is assessed (excluded from summary, not scored LOW); Given GA ≥20wk; Then FHR <120/>160 → HIGH, fundal height outside GA±2cm → HIGH. |
| VS-12 | ☐ `overall summary risk is the worst individual finding` | Given a mix of LOW/MILD/MODERATE findings; Then overall = MODERATE; Given any HIGH finding; Then overall = HIGH (worst-of aggregation, matches `RiskLevel` ordinal severity). |

## 2. ViewModel — `VisitFormViewModelTest` additions (unit)

| # | Name | Given / When / Then |
|---|---|---|
| VS-13 | ☐ `summary exposes computed findings and overall risk level` | Given completed Visit Data; When Summary is reached; Then `uiState.summaryRiskLevel` and a findings list (vital, value, risk level, reference range) are derived from `visitData` via `VisitRiskAssessment` — no duplicate state, always recomputed from the single source of truth. |
| VS-14 | ☐ `edit tests jumps to visit data tests sub-tab` | When `editTests()`; Then `currentStep = VISIT_DATA`, `visitDataSubTab = TESTS`. |
| VS-15 | ☐ `edit symptoms jumps to visit data symptoms sub-tab` | When `editSymptoms()`; Then `currentStep = VISIT_DATA`, `visitDataSubTab = SYMPTOMS`. |
| VS-16 | ☐ `comorbidity chips come from the beneficiary's diagnoses` | Given `VisitContext.comorbidities = ["Sickle Cell", "Chronic Diabetes"]`; Then Summary state exposes them unchanged (no relabeling); Given an empty list; Then a "no known conditions" flag is set for the UI's fallback text. |

## 3. Repository — `StaticVisitFormRepositoryTest` additions

| # | Name | Given / When / Then |
|---|---|---|
| VS-17 | ☐ `comorbidities are sourced from the beneficiary profile's diagnoses` | Given a beneficiary id with `diagnoses = ["Sickle Cell", "Chronic Diabetes"]` in `StaticBeneficiaryProfileRepository`; Then `getVisitContext(...).comorbidities` returns that same list. |
| VS-18 | ☐ `beneficiary with no diagnoses returns an empty comorbidities list` | Given a beneficiary id with no diagnoses recorded; Then `comorbidities` is empty (not null, not a placeholder string). |

## 4. Compose UI — spec (batched visual QA)

| # | Name | Given / When / Then |
|---|---|---|
| VS-UI-1 | ☐ `risk banner` | "Risk Identified" heading + `RiskBadge` pill (HIGH/MODERATE/MILD/LOW) top-right, comorbidity chips wrapped below (or "No known conditions" text if empty). |
| VS-UI-2 | ☐ `tests card` | One row per assessed vital: label, value with reference range in parens, right-aligned status pill (`RiskBadge`, "Low Risk" label for LOW per the design's own wording, not "Normal"); "Edit" in the card header. |
| VS-UI-3 | ☐ `symptoms card` | Same row/pill/Edit pattern for Q17–22 findings below the Tests card. |
| VS-UI-4 | ☐ `edit affordance navigates correctly` | Tests card "Edit" → Visit Data/Tests; Symptoms card "Edit" → Visit Data/Symptoms; confirmed via `editTests()`/`editSymptoms()` (VS-14/15). |
| VS-UI-5 | ☐ `tablet + mobile insets, EN + MR` | 48/24dp insets; all new strings present in both `values/` and `values-mr/`. |

---

# Deferred to later sub-CRs (tracked, not re-litigated each time)

- **CR-016d** — Health Info tab (week-based static content) + Referral tab (date,
  facility search, Accompanied/Standard) + Submit + completion state.
- **Open question carried from planning:** HIV test field exists in `Registration_PW_D`
  (enrollment) but not in `ANC visit form` — confirm with ARMMAN before 016b whether
  it's enrollment-only by design or a spec gap, since the Tests sub-tab may expect it.

# CR-033 — INC/CCV form codes are a placeholder, not real content (tracked, not fixed here)

**Status: open, pending backend content.** `INC_VISIT` and `CCV_VISIT` exist as real form
codes/endpoints on the backend (`GET/POST /forms/{formCode}/...` both respond), and
`VisitCodeFormResolver` (app/src/main/kotlin/org/armman/sakhi/data/forms/VisitCodeFormResolver.kt)
now routes `VisitCodeType.INC`/`INC_HR`/`CCV`/`CCV_HR` schedule rows to them instead of the old
hardcoded `INFANT_VISIT`. **But** the schema content behind those two codes is currently a direct
copy of `INFANT_VISIT`'s fields — not a real INC/CCV-specific question set. A Sakhi opening an
INC or CCV visit today sees the generic 0–12m infant visit form, not INC/CCV-specific questions.

**Unblocks:** the app-side plumbing (form-code resolution, upload-status labelling — see CR-034's
sibling change in `FormUploadStatusPresentation.kt`) so nothing is hardcoded to `INFANT_VISIT`
anymore; swapping in the real schema later needs no further app change beyond deploying it
server-side.

**What's pending, from the backend team:** the actual INC/CCV clinical field list (referenced in
the SRS as "CCV clinical rules... Prajakta (ARMMAN)", SRS line 179) — this repo has the "High Risk
Protocols — Developer Copy" PDFs (ANC HR, Infant HR, Dashboard) in the project docs, which may be
the right source; confirm with the backend/SRS owner before assuming so.

# CR-034 — HR visits route to their base visit's form (design decision still open)

**Status: open, pending an HR design decision.** `ANC_HR`, `INC_HR`, and `CCV_HR` schedule rows
resolve (via `VisitCodeFormResolver`'s fallback map) to the same form code as their non-HR
counterpart (`ANC_VISIT`, `INFANT_VISIT`) — there is no separate HR form and no `isHighRisk`
flag/section anywhere in any existing schema. Checked every seed/schema file for
`high_risk`/`hr_`/`visibleWhen`/`showIf`/conditional markup — nothing found; the SRS only
describes HR as a *scheduling* trigger (FR-S-5.2), never HR-specific form content.

This is a deliberate, tracked choice, not an oversight: showing the base form is what already
happened before CR-033/CR-034 (HR types weren't distinguished from their base type at all), so
this preserves current behaviour rather than guessing at new HR-specific fields.

**What's pending:** whoever owns the High Risk Protocol docs (ANC HR / Infant HR / Dashboard)
needs to decide — separate HR form codes with extra risk-monitoring questions, or a flag/section
layered on the base form — and supply the extra fields if the latter. No further app change is
needed to prepare for either outcome; `VisitCodeFormResolver`'s map is the single place that
would change.

# CR-035 — Capture-only local audit trail for form open/save/submit (no viewer yet)

**Status: shipped, capture-only.** Every visit-form and registration-form open, offline-draft
save, and successful submission now writes a row to a new local-only `form_audit_events` table
(`org.armman.sakhi.data.audit.FormAuditEventEntity`, via `FormAuditRepository`/
`RoomFormAuditRepository`). This is intentionally capture-only: there is no screen anywhere in the
app that reads this table yet. It exists purely so the trail is being recorded faithfully from day
one, before any viewer is built.

**Every open is logged, not just the first.** `DynamicVisitFormViewModel.load()` calls
`recordOpened()` on every successful load — re-opening the same visit form five times writes five
OPENED rows, by explicit product decision (an accurate trail is the point, not a deduplicated
summary). A failed `load()` (blank ids, beneficiary not found, or no active form version) does NOT
write an OPENED event — only a genuinely-loaded form counts as "opened".

**SAVED** fires unconditionally inside `RoomVisitFormDraftRepository.saveLocally()` and
`RoomDynamicFormDraftRepository.saveLocally()` — both the online-immediate-attempt path and the
offline-queued path go through `saveLocally()` first, so both get a SAVED event regardless of what
happens next.

**SUBMITTED** fires only on a successful `POST /forms/:formCode/submissions` response, from inside
`VisitFormSubmissionCoordinator.submit()` and `DynamicFormSubmissionCoordinator.submit()` — never
on a failed attempt of either the visit-creation/beneficiary-creation call or the submission call
itself.

**`submittedBy` field:** `FormSubmissionApi.CreateSubmissionRequestDto` gained a new nullable
`submittedBy: String?` field, populated from the signed-in Sakhi's session at submission time in
both coordinators above — the same session read each already does for its own `sakhiId`-equivalent
field (e.g. `VisitApi`'s `CreateVisitInstanceRequestDto.sakhiId`). Nullable because a session could
theoretically be absent by the time the DTO is built, though in practice both coordinators already
throw before reaching that point if there is no active session (`NoActiveSession` /
`EnrollmentMappingException.NoActiveSession`), so it is non-null on every request actually sent.

**No automated migration test for `MIGRATION_5_6` (v5→v6, adds `form_audit_events`).** Explicit
team decision, not an oversight — there are no real users on the app yet, and none of the three
prior additive migrations (`MIGRATION_2_3`/`MIGRATION_3_4`/`MIGRATION_4_5`) were automated-tested
either, so this is consistent with the existing project convention rather than a new shortcut. The
migration itself is still hand-written carefully, mirroring `MIGRATION_4_5`'s exact style (raw
`CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` matching Room's generated schema for
`FormAuditEventEntity` column-for-column).

**Out of scope for this pass, tracked for later:**
- No UI anywhere reads `form_audit_events` — capture-only, by design.
- `ChildRegistrationSubmissionCoordinator` (Children Register's own submission flow) was not
  wired for a SUBMITTED event or `submittedBy` — the CR only named
  `VisitFormSubmissionCoordinator`/`DynamicFormSubmissionCoordinator` in scope. Its own
  `CreateSubmissionRequestDto` call is unaffected (the new field defaults to null), but if the
  audit trail should also cover Children Register submissions, that needs its own follow-up.
