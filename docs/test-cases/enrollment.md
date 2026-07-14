# Test Cases — Enrollment Form (CR-015)

**Screens:** `ui/enrollment/` — EntrySelector, EnrollmentStepper (Consent → Personal Info → Health History → Summary), Complete.
**Design source:** `docs/project-docs/Arogya Sakhi - Revamp/Enrollment form.pdf` (purple/current frames; mobile frames only — no purple tablet frame exists, tablet extends mobile patterns per design-token rules).
**Field spec source:** Google Sheet "Revised App Form Final 20.3.26" (Drive ID `1N8cms-5YlNdwqrUm7qe6P-mM7Gm2ef50fjIl8uLARsc`), tab `Registration_PW_D` — ANC Enrollment form Q1–65.
**Scope agreed (2026-07-13):** Pregnant Woman flow only; UI + validation + in-memory save; media (video/audio/photo) stubbed; duplicate detection, ANC schedule generation, risk tagging, real camera — deferred.
**Runnable tests:** JVM units only per repo convention — `EnrollmentViewModelTest` (+ `StaticEnrollmentRepositoryTest` in CR-015d). UI/NAV cases below are spec, verified via batched visual QA.

Status legend: ☐ not yet implemented · ☑ implemented & passing.

---

# CR-015a — Entry selector + stepper scaffold + Consent step + Complete screen

## 1. ViewModel — `EnrollmentViewModelTest` (unit, coroutine test)

### Entry selector

| # | Name | Given / When / Then |
|---|---|---|
| VM-1 | ☐ `initial state has no beneficiary type selected` | Given a fresh ViewModel; Then `beneficiaryType = null`, `currentStep = ENTRY`, continue not possible. |
| VM-2 | ☐ `selecting pregnant woman enables flow` | When `selectBeneficiaryType(PREGNANT_WOMAN)`; Then state reflects selection and `startEnrollment()` moves `currentStep` to `CONSENT`. |
| VM-3 | ☐ `selecting child emits coming soon event` | When `selectBeneficiaryType(CHILD)` and `startEnrollment()`; Then a one-shot `ComingSoon` event is emitted and `currentStep` stays `ENTRY` (Child flow out of scope). |

### Consent step (Excel Q1–4 + design checkboxes)

| # | Name | Given / When / Then |
|---|---|---|
| VM-4 | ☐ `consent step starts incomplete` | Given step CONSENT; Then all 4 willingness checkboxes false, consent answer null, video not played, photo not taken, Next (Personal Info) disabled. |
| VM-5 | ☐ `toggling willingness checkboxes updates state` | When each of the 4 checkboxes (share personal info / share health history / diagnostic tests / referral logic) is toggled; Then state reflects each independently. |
| VM-6 | ☐ `video play stub marks video as played` | When `onPlayVideo()` (stub); Then `videoPlayed = true` and a `ComingSoon` media event is emitted (real playback deferred). |
| VM-7 | ☑ `consent photo capture stores uri and gates progression` | (UPDATED 2026-07-13: photo is functional via system-camera `TakePicture` + FileProvider, live capture only, no gallery — Excel Q4.) Given all checkboxes ticked but no photo; Then `isComplete=false`; When `onConsentPhotoCaptured(uri)`; Then `photoTaken=true`, `photoUri=uri`, `isComplete=true`. |
| VM-7b | ☑ `retake replaces the previous photo uri` | Given a captured photo; When captured again with a new uri; Then `photoUri` = the new uri (same pill retakes; capture overwrites the app-private file). |
| VM-8 | ☑ `consent answer no blocks progression` | Given "Did we receive consent?" = No (Q3: No → stop form); When `goToPersonalInfo()`; Then step does not advance and state exposes a `consentRefused` flag for the blocking message. (Design has no Q3 radio — VM API only; unanswered Q3 does not block.) |
| VM-9 | ☑ `next requires all checkboxes, photo, consent not refused` | Given any checkbox false OR photo missing; Then `isComplete = false`; Given all 4 true AND photo captured AND consent not refused; Then `isComplete = true`. |
| VM-10 | ☐ `next advances to personal info when complete` | Given `isConsentComplete = true`; When `goToPersonalInfo()`; Then `currentStep = PERSONAL_INFO` (placeholder step in 015a). |
| VM-11 | ☐ `back from consent returns to entry selector` | Given step CONSENT; When `goBack()`; Then `currentStep = ENTRY`, previously entered consent state retained (draft survives in-session back-nav). |

### Stepper & completion scaffold

| # | Name | Given / When / Then |
|---|---|---|
| VM-12 | ☐ `step order is fixed` | Steps advance strictly ENTRY → CONSENT → PERSONAL_INFO → HEALTH_HISTORY → SUMMARY → COMPLETE; no step skipping via `goToStep()` beyond the furthest completed step. |
| VM-13 | ☐ `tab back-navigation to completed steps allowed` | Given furthest step = PERSONAL_INFO; When `goToStep(CONSENT)`; Then allowed; When `goToStep(SUMMARY)`; Then ignored. |
| VM-14 | ☐ `complete screen state after submit stub` | Given step SUMMARY reached (via test scaffold); When `submit()` (015a stub); Then `currentStep = COMPLETE`. |
| VM-15 | ☐ `start visit form emits coming soon` | Given COMPLETE; When `onStartVisitForm()`; Then `ComingSoon` event (Visit Form not built). |
| VM-16 | ☐ `draft cleared on exit confirm` | When `exitEnrollment()`; Then state resets to initial (no persistence in 015a — in-memory draft only). |

## 2. Compose UI — spec (verified via batched visual QA; no runnable Compose tests per repo convention)

| # | Name | Given / When / Then |
|---|---|---|
| UI-1 | ☐ `entry card renders both options` | "Register New Beneficiary as:" card with Child + Pregnant Woman radio rows, icons per design; PW selectable, purple selected state. |
| UI-2 | ☐ `child option shows coming soon` | Tapping Child → "coming soon" toast; radio does not stay latched on Child. |
| UI-3 | ☐ `header matches design` | Back arrow + "Home Page" caption, "Enrollment Form" title, today's date, Sakhi avatar — matches BackHeader pattern. |
| UI-4 | ☐ `stepper tabs render four steps` | Consent / Personal Info / Health History / Summary tabs; active tab purple-underlined; future tabs visually disabled. |
| UI-5 | ☐ `consent step layout` | Instruction line, "Welcome to Arogyasakhi Program!" video placeholder w/ play button, "Ensure that the beneficiary" + 4 checkboxes, "Play consent guidelines" pill, "Take photo of consent form" pill, footer "Personal Info →" primary button. |
| UI-6 | ☐ `consent refused message` | Consent = No → blocking message shown, Personal Info button disabled. |
| UI-7 | ☐ `complete screen layout` | Green check circle, "Enrollment Complete!", "Start Visit Form →" primary button; back returns Home. |
| UI-8 | ☐ `tablet layout` | 800×1280: content column centred with tablet width tokens, stepper tabs full-width; mobile 376×884 matches purple mobile frames. |
| UI-9 | ☐ `strings in EN + MR` | All new strings present in both `values/` and `values-mr/`. |

## 3. Navigation — integration (spec)

| # | Name | Given / When / Then |
|---|---|---|
| NAV-1 | ☐ `register new on home opens enrollment` | Home "Register New +" button (currently no-op in `HomeContent.kt`) → route `enrollment`. |
| NAV-2 | ☐ `system back from entry returns home` | Back on entry selector → Home, no draft retained. |
| NAV-3 | ☐ `back mid-flow confirms exit` | System back inside stepper → exit-confirmation (draft would be lost); confirm → Home. |
| NAV-4 | ☐ `complete back goes home not stepper` | From COMPLETE, back → Home (stepper popped from back stack). |

---

# CR-015b — Personal Info step (Excel Q5–34)

**Scope:** full Personal Info form state + validation in `EnrollmentViewModel` (or a
`PersonalInfoValidator` it delegates to), static geography data via a new
`GeographyRepository` (`StaticGeographyRepository`, Hilt-bound, models named as future
API responses), UI per the design's Personal Info frame patterns (dropdown / radio /
date / text field, inline errors, "Complete all necessary fields" banner).
**Out of scope:** duplicate detection (015-deferred), persistence (015d), risk tagging.

**RESOLVED (user, 2026-07-13):** Excel Q5 makes LMP Mandatory with no "No" path for
"Does the woman know her LMP?". Decision: the No radio stays clickable but selecting it
is a **no-op** — `lmpKnown` remains Yes, no message, no state change. Revisit when
ARMMAN defines the LMP-unknown flow.

## 1. ViewModel / validation — `EnrollmentViewModelTest` additions (unit)

### LMP / EDD / GA (Q5–7)

| # | Name | Given / When / Then |
|---|---|---|
| PI-1 | ☐ `lmp known defaults to yes` | Fresh Personal Info state → `lmpKnown = true`, LMP field visible & empty, EDD/GA empty. |
| PI-2 | ☐ `selecting lmp not known is a no-op` | When `setLmpKnown(false)`; Then `lmpKnown` stays `true` — the No radio is tappable but has no effect (resolved decision above); LMP field remains visible and required. |
| PI-3 | ☐ `future lmp rejected` | When `setLmp(today + 1d)`; Then LMP field error, EDD/GA not computed. |
| PI-4 | ☐ `lmp under 30 days before registration rejected` | `setLmp(today − 29d)` → error (Excel: diff must be >30 days). |
| PI-5 | ☐ `lmp of 240 days or more rejected` | `setLmp(today − 240d)` → error (must be <240 days). |
| PI-6 | ☐ `valid lmp computes edd and ga` | `setLmp(today − 90d)` → no error, `edd = lmp + 280d`, `gestationalAgeWeeks = 12` (floor(90/7)). |
| PI-7 | ☐ `changing lmp recomputes edd and ga` | Second `setLmp` overwrites both derived values. |

### Identity (Q8–10, 19–22)

| # | Name | Given / When / Then |
|---|---|---|
| PI-8 | ☐ `beneficiary id is a local uuid` | On entering the step, `beneficiaryId` is a valid UUID, stable across recompositions (ERD: UUIDs; Excel's State-District-Block format superseded). |
| PI-9 | ☐ `registration date defaults to today` | `registrationDate == LocalDate.now()`, not editable. |
| PI-10 | ☐ `names reject special characters` | `setFirstName("R33ma!")` → field error; letters + spaces accepted; first/middle/last validated independently. |
| PI-11 | ☐ `dob or age either one required` | Both empty → incomplete; DOB set → age auto-calculated (floor, from today); age set directly → accepted without DOB. |
| PI-12 | ☐ `age outside 10 to 50 rejected` | DOB giving age 9 or 51 → error; boundaries 10 and 50 accepted. Age-risk tagging (Severe <19 / ≥35) is OUT of scope (risk engine deferred) — only range validation here. |
| PI-13 | ☐ `mobile must be 10 digits` | 9 or 11 digits or non-numeric → error; exactly 10 → valid. |
| PI-14 | ☐ `address required` | Empty address → field error contributing to the banner. |

### Geography cascade (Q11–18) — `StaticGeographyRepositoryTest` + VM

| # | Name | Given / When / Then |
|---|---|---|
| PI-15 | ☐ `repo returns children per parent` | Districts for a state, blocks for a district, villages for a block, padas/PHCs/SCs for a village — fixture is hierarchical and non-empty. |
| PI-16 | ☐ `geography prefilled from sakhi assignment` | On step entry, state/district/block default to the Sakhi's assigned values (static profile), lists loaded. |
| PI-17 | ☐ `changing parent resets descendants` | Given village selected; When district changed; Then village/pada/PHC/SC selections clear and their option lists reload. |
| PI-18 | ☐ `phc and sc auto populate from village or pada` | Selecting a village (or pada) fills PHC + SC options; single-option lists auto-select. |

### Demographics (Q23–34)

| # | Name | Given / When / Then |
|---|---|---|
| PI-19 | ☐ `all dropdowns store selections` | Phone owner, network availability, education (self + partner), partner occupation, migration pattern, income, religion, category — each selection reflected in state; response stored as option index/code (Excel: numeric storage) with display text separate. |
| PI-20 | ☐ `years in village numeric` | Non-numeric rejected; integer accepted. |
| PI-21 | ☐ `household size range 2 to 15` | 1 and 16 → error; 2/15 accepted. |
| PI-22 | ☐ `children under five within household size` | childrenUnder5 > householdMembers → cross-field error (1-digit per Excel). |

### Step completion & navigation

| # | Name | Given / When / Then |
|---|---|---|
| PI-23 | ☐ `incomplete step shows banner on next attempt` | Any mandatory field missing/invalid; When `goToHealthHistory()`; Then step does not advance, `showValidationBanner = true` ("Complete all necessary fields"). |
| PI-24 | ☐ `banner clears once fields become valid` | Fixing the last invalid field hides the banner. |
| PI-25 | ☐ `complete step advances and updates furthest` | All mandatory valid → `currentStep = HEALTH_HISTORY`, `furthestStep` updated. |
| PI-26 | ☐ `back to consent retains personal info draft` | `goBack()` → CONSENT; returning shows all entered values. |

## 2. Compose UI — spec (batched visual QA)

| # | Name | Given / When / Then |
|---|---|---|
| PI-UI-1 | ☐ `field patterns match design` | Labels `labelLarge`; dropdowns/inputs match the design's Personal Info frame (Education, Address, Mobile w/ +91 prefix cell, LMP date w/ edit icon); red error border + red helper text on invalid fields ("This field is compulsory"). |
| PI-UI-2 | ☐ `lmp radio row` | "Does the woman know her LMP ?" with Yes/No radios, purple selected state. |
| PI-UI-3 | ☐ `edd and ga read-only` | Auto-computed EDD/GA displayed non-editable. |
| PI-UI-4 | ☐ `banner style` | Red "Complete all necessary fields" banner per frame, shown only after a blocked Next attempt. |
| PI-UI-5 | ☐ `footer back and health history` | Footer: Back (left) + "Health History →" (right, disabled until valid per design's greyed pattern — confirm with frame). |
| PI-UI-6 | ☐ `tablet and mobile insets` | 48dp/24dp insets per QA'd tokens; fields full content width; strings EN + MR. |

## 3. Deviations / notes

- Unique ID `State(2)-District(3)-Block(3)-ID(6)` (Q9) NOT implemented — ERD mandates UUIDs (plan-approved deviation).
- Geo-tagging for address (Q21 "Varchar + Geo, Geo optional") deferred with media/camera CRs.
- Risk calculations (age risk) deferred to the GoRules risk CR.

# CR-015c — Health History step (Excel Q35–65)

**Scope (approved 2026-07-14):** full Health History form state + validation in
`EnrollmentViewModel` (delegating to a new `HealthHistoryState`), UI following the
015a/b field-composable patterns (no Figma frame exists for this step), the Health
History review card added to Summary, and the Q35–65 fields added to `EnrollmentRecord`
+ `toEnrollmentRecord()`. Reusable `AppRadioGroup` + `AppCheckboxGroup` (exclusive-option
aware) are promoted into `components/FormFields.kt`.
**Out of scope (confirmed):** risk-severity calculation and referral / health-message
actions (Excel "Risk condition" / "Risk action" columns) — deferred to the GoRules risk
CR. Media/camera unaffected.
**Storage conventions:** dropdown/radio answers as 1-based Int codes; each multi-select
condition as its own Boolean ("separate variable per condition" developer note); dates
`dd-mm-yyyy`.

**Design/spec deviations flagged for approval:**
- No purple frame exists — visuals derive from 015a/b patterns (token-driven).
- Q35 Trimester is auto-derived from gestational age (Reg−LMP): `<14w → 1st`,
  `14–27w → 2nd`, `≥28w → 3rd` — read-only, like EDD/GA in 015b.
- Q50 Dead Children & Q64 malnutrition have no "Mandatory" marking in the Excel →
  treated as **optional**; Q65 Remarks optional. All other Q35–63 mandatory (subject to
  their conditional visibility).
- Gravida cross-total: Excel `Gravida = Live + Abortion + Still + 1` reduces to
  `Gravida = Para + Abortions + 1` (since Para = Live + Still). Validated only when
  Gravida/Para/Abortions all entered; individual ranges 0–14 (Gravida 1–14) always
  enforced. Multi-selects modelled as `Set<Int>` of 1-based codes (API layer expands to
  per-condition booleans).

## 1. ViewModel / state — `EnrollmentViewModelTest` additions (unit)

### Current pregnancy (Q35–44)

| # | Name | Given / When / Then |
|---|---|---|
| HH-1 | ☑ `trimester auto-derives from gestational age` | Given LMP set in Personal Info; Then `trimester` = 1 when GA<14w, 2 when 14–27w, 3 when ≥28w; field is read-only (not user-set). |
| HH-2 | ☑ `planned pregnancy stores code` | `setPlannedPregnancy(2)` → stored as `2`; mandatory. |
| HH-3 | ☑ `treatment type shown only when treatment taken` | `setTookTreatment(false)` → Q38 hidden and not required; `setTookTreatment(true)` → Q38 (treatment type) required. |
| HH-4 | ☑ `rch number required only for card-available option` | `setRchStatus(1)` (Registered – card available) → Q40 RCH number required; any other option → Q40 hidden, not required. |
| HH-5 | ☑ `anc1 date and conditions shown only when anc1 completed` | `setAncStatus(=Not started)` → Q42/Q43 hidden; `setAncStatus(=ANC-1 completed)` → Q42 date + Q43 conditions required. |
| HH-6 | ☑ `anc1 date not in the future` | A future date → error; a past date → valid. NOTE: the "after LMP" half of the Excel rule is deferred — LMP is not mirrored into `HealthHistoryState` yet; add when the risk/date CR needs it. |
| HH-7 | ☑ `q43 conditions exclusive options` | Selecting "No known condition" (or "Don't know") clears + disables all other Q43 flags; selecting any specific condition clears + disables those two. Stored as per-condition booleans. |
| HH-8 | ☑ `td none is exclusive` | Selecting Td "None received yet" clears + disables Td-1/Td-2/Booster; selecting any dose clears "None". |
| HH-9 | ☑ `td dose dates enabled only when dose ticked and ordered` | Date enabled only for a ticked dose; future date rejected; requires Td1 ≤ Td2 ≤ Booster when present. |

### Past obstetric history (Q45–50)

| # | Name | Given / When / Then |
|---|---|---|
| HH-10 | ☑ `gravida range 1 to 14` | 0 or 15 → error; 1 and 14 accepted. |
| HH-11 | ☑ `para at most gravida` | Para > Gravida → error; Para ≤ Gravida valid; range 0–14. |
| HH-12 | ☑ `abortions at most gravida` | Abortions > Gravida → error. |
| HH-13 | ☑ `dead children at most living` | Q50 dead children > living children → error (Q50 optional otherwise). |
| HH-14 | ☑ `gravida cross-total enforced when all entered` | Excel `Gravida = Live+Abortion+Still+1` reduces to `Gravida = Para + Abortions + 1` (Para = Live+Still). With Para+Abortions+1 ≠ Gravida → cross-field error; equal → valid; unchecked while Gravida/Para/Abortions any blank. |

### Last pregnancy (Q51–57) — only if Gravida > 1

| # | Name | Given / When / Then |
|---|---|---|
| HH-15 | ☑ `last pregnancy section hidden when gravida is 1` | Gravida = 1 → Q51–57 not shown and not required; Gravida > 1 → Q51–56 required. |
| HH-16 | ☑ `birth weight shown only for live-birth outcome` | Q56 = Live birth → Q57 birth weight required; Q56 = Still birth → Q57 hidden. |
| HH-17 | ☑ `changing gravida to 1 clears last-pregnancy answers` | Given Q51–57 filled with Gravida>1; When Gravida set to 1; Then those answers reset (no stale data persisted). |

### Self & family medical (Q58–65)

| # | Name | Given / When / Then |
|---|---|---|
| HH-18 | ☑ `q58 self conditions exclusive options` | Same exclusivity rule as HH-7 for Q58 (self medical history). |
| HH-19 | ☑ `q61 substance use exclusive no option` | "No" (and "Don't know / Not willing") exclusive against specific substances. |
| HH-20 | ☑ `sickle cell stores code` | Q60 dropdown selection stored as code; mandatory. |
| HH-21 | ☑ `family conditions shown only when family history yes` | Q62 = No → Q63 hidden, not required; Q62 = Yes → Q63 required. |
| HH-22 | ☑ `optional fields do not block completion` | Q50 dead children, Q64 malnutrition, Q65 remarks left empty → step still completable. |

### Completion & navigation

| # | Name | Given / When / Then |
|---|---|---|
| HH-23 | ☑ `incomplete step blocks summary and shows banner` | Any mandatory (visible) field missing/invalid; When `goToSummary()`; Then step stays HEALTH_HISTORY, `showValidationBanner = true`. |
| HH-24 | ☑ `banner clears once fields valid` | Fixing the last invalid field hides the banner. |
| HH-25 | ☑ `complete step advances to summary` | All mandatory valid → `currentStep = SUMMARY`, `furthestStep` updated. |
| HH-26 | ☑ `back to personal info retains health history draft` | `goBack()` → PERSONAL_INFO; returning to Health History shows entered values. |
| HH-27 | ☑ `submitted record carries health history answers` | After completing HH and submitting, the saved `EnrollmentRecord` contains Q35–65 (codes/booleans), consistent with SM-7. |

## 2. Compose UI — spec (batched visual QA)

| # | Name | Given / When / Then |
|---|---|---|
| HH-UI-1 | ☐ `section grouping` | Four labelled groups (Current Pregnancy / Past Obstetric / Last Pregnancy / Self & Family Medical) using 015b field patterns and spacing tokens. |
| HH-UI-2 | ☐ `radio + checkbox group styling` | `AppRadioGroup`/`AppCheckboxGroup` match the existing consent/LMP radio + checkbox visuals (purple selected, `ic_radio_*` / `ic_checkbox*`). |
| HH-UI-3 | ☐ `conditional fields appear inline` | Q38/Q40/Q42-43/Q57/Q63 and the whole Last-Pregnancy block appear/disappear without layout gaps. |
| HH-UI-4 | ☐ `td dose date pickers` | Per-dose date field enabled only when its checkbox is ticked. |
| HH-UI-5 | ☐ `read-only trimester` | Trimester shown non-editable (like EDD/GA). |
| HH-UI-6 | ☐ `banner + footer` | "Complete all necessary fields" banner after blocked Next; footer Back + "Summary →". |
| HH-UI-7 | ☐ `summary shows health history card` | Summary review adds a Health History card (Edit → HEALTH_HISTORY) beside Personal Info. |
| HH-UI-8 | ☐ `tablet + mobile insets, EN + MR` | 48/24dp insets; all new strings in `values/` and `values-mr/`.

# CR-015d — Summary + submit + `StaticEnrollmentRepository`

**Scope (approved 2026-07-14):** Summary step ("Review Details") with a Personal Info
review card + Edit jump-back, Submit gated on `personalInfo.isComplete`, first
persistence piece — `EnrollmentRepository` interface + in-memory
`StaticEnrollmentRepository` (Hilt-bound, forklift-friendly), "Data has been saved"
toast, then COMPLETE. **015c is skipped for now:** Health History tab keeps its
pending placeholder and its Next advances ungated to SUMMARY; the Health History
review card is omitted until 015c lands.
**Out of scope:** Health History card content, real API/DB persistence, duplicate
detection, risk tagging, PDF/consent-photo upload.

## 1. ViewModel — `EnrollmentViewModelTest` additions (unit)

### Reaching Summary

| # | Name | Given / When / Then |
|---|---|---|
| SM-1 | ☑ `health history placeholder advances ungated to summary` | Given step HEALTH_HISTORY (015c skipped); When `goToSummary()`; Then `currentStep = SUMMARY`, `furthestStep = SUMMARY` (no validation gate on the placeholder). |
| SM-2 | ☑ `summary exposes personal info draft for review` | Given a completed Personal Info draft; When SUMMARY reached; Then UI state provides all entered Q5–34 values (names, dates, codes, geography ids) unchanged from the draft. |

### Edit jump-back

| # | Name | Given / When / Then |
|---|---|---|
| SM-3 | ☑ `edit personal info returns to that step` | Given SUMMARY; When `goToStep(PERSONAL_INFO)` (Edit pill); Then `currentStep = PERSONAL_INFO`, full draft retained, `furthestStep` still SUMMARY. |
| SM-4 | ☑ `re-entering summary after edit shows updated values` | Given SM-3; When a field is changed and user navigates forward to SUMMARY again; Then the review state reflects the new value. |
| SM-5 | ☑ `edited draft failing validation blocks return to summary` | Given SM-3 and a mandatory field cleared; When `goToHealthHistory()`; Then step does not advance and the validation banner shows (existing PI-23 gate still applies on the way back). |

### Submit & persistence

| # | Name | Given / When / Then |
|---|---|---|
| SM-6 | ☑ `submit disabled while personal info incomplete` | Given SUMMARY reached with an invalidated draft (via Edit); Then `canSubmit = false`; `submit()` is a no-op (no save, step unchanged). |
| SM-7 | ☑ `submit saves draft through repository` | Given valid draft; When `submit()`; Then `EnrollmentRepository.saveEnrollment(...)` is invoked exactly once with a record carrying beneficiary UUID, consent state, and all Personal Info answers (codes, not display strings). |
| SM-8 | ☑ `successful submit shows saved toast then complete` | Given save succeeds; Then a one-shot `DataSaved` event is emitted (green "Data has been saved" toast) and `currentStep = COMPLETE`. |
| SM-9 | ☑ `failed submit stays on summary with error` | Given repository returns failure; When `submit()`; Then `currentStep = SUMMARY`, error state exposed (no silent loss), no `DataSaved` event, retry possible. |
| SM-10 | ☑ `duplicate submit prevented while save in flight` | Given `submit()` in progress (suspended); When `submit()` called again; Then only one repository call occurs (`isSubmitting` guard). |
| SM-11 | ☑ `exit after submit clears draft` | Given COMPLETE after successful submit; When `exitEnrollment()`; Then in-memory draft resets; the saved record remains in the repository. |

## 2. Repository — `StaticEnrollmentRepositoryTest` (unit)

| # | Name | Given / When / Then |
|---|---|---|
| SR-1 | ☑ `save stores record retrievable by id` | When `saveEnrollment(record)`; Then `Result.success`, `getEnrollment(record.beneficiaryId)` returns an equal record. |
| SR-2 | ☑ `save is idempotent per beneficiary id` | Saving the same beneficiary id twice overwrites (one record, latest values) — re-submit safety. |
| SR-3 | ☑ `records survive across screen scope` | Repository is a singleton — records persist across ViewModel recreation within the process (in-memory only; real persistence deferred). |
| SR-4 | ☑ `answers stored as numeric codes` | Stored record keeps dropdown/radio answers as 1-based Int codes and multi-select flags as booleans — matches Excel "store responses as numbers". |

## 3. Compose UI — spec (batched visual QA)

| # | Name | Given / When / Then |
|---|---|---|
| SM-UI-1 | ☐ `review details layout matches frame` | "Review Details" title; white Personal Info card: header row ("Personal Info" + `Edit ✎` outlined pill), label/value rows with hairline dividers; labels `labelLarge` grey, values `bodyLarge`; purple frame is authoritative. |
| SM-UI-2 | ☐ `values render display strings not codes` | Dropdown answers shown as option labels (e.g. "10th Pass", not `4`); dates as `dd MMM yyyy` (e.g. "10 Jan 2026"); mobile with no +91 duplication; address as entered. |
| SM-UI-3 | ☑ `health history card absent` | (Superseded by HH-UI-7 once CR-015c lands — Summary then shows a Health History card too.) No Health History card while 015c was skipped; layout left no orphaned gap. |
| SM-UI-4 | ☐ `footer back and submit` | Footer: Back (secondary, left) + "Submit" (primary purple, right); Submit replaces Next only on SUMMARY. |
| SM-UI-5 | ☐ `saved toast style` | Green "Data has been saved" pill/toast per frame, shown on successful submit before COMPLETE. |
| SM-UI-6 | ☐ `complete screen shows after submit` | Existing COMPLETE screen (green check, "Enrollment Complete!", "Start Visit Form →") reached with real saved data; back → Home. |
| SM-UI-7 | ☐ `tablet and mobile insets` | 48dp/24dp insets per tokens; card full content width; strings EN + MR. |
