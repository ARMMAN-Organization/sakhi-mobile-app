# CR-031 — Mother link & prefill (child enrollment) — test cases

**Scope:** `who_are_you_registering_in_the_program = child_of_a_registered_pregnant_woman`
→ mother picker → prefill from `GET /api/v1/beneficiaries` (+ `/beneficiaries/:id` for consent).
**Out of scope:** socio-demographic prefill from `MOTHER_REGISTRATION.formData` (CR-032),
delivery-section prefill (needs the Delivery form), consent media/photo inheritance (CR-028).

Legend: ☐ pending · ☑ passing. Layer — `REPO` repository, `MAP` prefill mapper,
`VM` ViewModel, `UI` Compose (manual QA — no Compose test infra exists yet, see CR-036).

---

## REPO — `RemoteMotherLinkRepository`

| # | Case | Expected |
|---|---|---|
| ☐ REPO-01 | `getRegisteredMothers()` on a 200 envelope with mixed `caseType` | Only `caseType == "MOTHER"` rows returned. The two `CHILD` rows in the live sample are dropped even though the query already filters, so a backend that ignores the param can't leak children into the picker. |
| ☐ REPO-02 | Response contains a row with `currentStatus != "ACTIVE"` | Dropped. |
| ☐ REPO-03 | Rows returned in `createdAt desc` (backend order) | Preserved as-is; repository does not re-sort. |
| ☐ REPO-04 | `pii.dateOfBirth = "2001-07-29T00:00:00.000Z"` | `dateOfBirth == LocalDate(2001,7,29)`. The `T…Z` suffix is stripped, no timezone shift. |
| ☐ REPO-05 | `pii.dateOfBirth` absent/null/unparseable | Row still returned, `dateOfBirth == null`. A bad DOB must never drop a selectable mother. |
| ☐ REPO-06 | Successful fetch | Result persisted to `SecureKeyValueStore` under `mother_link_cache`. |
| ☐ REPO-07 | Fetch fails (offline/500/timeout) with a prior successful fetch | Returns the persisted list. No exception. |
| ☐ REPO-08 | Fetch fails with nothing ever cached | Returns `null` (distinct from `emptyList()` — the UI must say "connect once" not "no mothers"). |
| ☐ REPO-09 | Fetch returns `success: false` | Treated as a failure → cache fallback. |
| ☐ REPO-10 | Fetch returns 200 with `data: []` | Returns `emptyList()` **and overwrites the cache**. A Sakhi whose last mother was closed must not keep seeing a stale list. |
| ☐ REPO-11 | Persisted cache JSON is corrupted | Treated as absent → `null`, no crash. |
| ☐ REPO-12 | Two concurrent `getRegisteredMothers()` calls | Serialized by the mutex; one network call. |
| ☐ REPO-13 | `getMotherDetail(id)` 200 with `consentRecords: [{consentStatus: "GIVEN"}]` | `consentGiven == true`. |
| ☐ REPO-14 | `getMotherDetail(id)` with `consentRecords: []` or `REFUSED` | `consentGiven == false`. |
| ☐ REPO-15 | `getMotherDetail(id)` fails (offline/404) | Returns `null`. Selection must still succeed with geography/name prefill — consent simply isn't inherited. **Not** an error state. |

## MAP — `MotherPrefill` (pure function, no Android/network)

| # | Case | Expected |
|---|---|---|
| ☐ MAP-01 | Mother with full `pii`, geography array contains every matching unit | Prefills `mother_beneficiary_id`, `caregiver_name_first_name_middle_name_last_name`, `age_or_dob_of_the_mother`, `name_of_the_state`, `name_of_district`, `name_of_block_taluka`, `name_of_the_revenue_village_grampanchayat`, `beneficary_pada_name`, `beneficary_phc_name`, `name_of_sub_center`. |
| ☐ MAP-02 | `name_of_block_taluka` source | Maps from `pii.talukaId`, **not** `pii.healthBlockId` — the submission mapper sends `talukaId` from this answer and `healthBlockId = null`. |
| ☐ MAP-03 | `project_name` | **Never prefilled.** Its option `valueCode` is the project *name* from the Sakhi profile, not `projectId`; writing the UUID would produce an answer matching no option. Already handled by `prefillAutoSelectedGeography`. |
| ☐ MAP-04 | Mother's `villageId` is **not** present in `FormVersion.geography` | That field is skipped. Guard against submitting a `geographyUnitId` the backend's `/beneficiaries` validation rejects (the `pii.phcId does not refer to a known geography unit` 422 class of bug). |
| ☐ MAP-05 | Mother's `padaId` present in geography but under a different `geoType` | Skipped — match requires both id and geoType. |
| ☐ MAP-06 | `pii.dateOfBirth == null` | `age_or_dob_of_the_mother` not written; every other field still prefills. |
| ☐ MAP-07 | `pii.fullName` is blank | Name field not written (a blank answer would satisfy nothing and clear an existing one). |
| ☐ MAP-08 | Returned `prefilledCodes` | Exactly the set of codes actually written — never codes that were skipped. This set drives the "From mother's record" hint. |
| ☐ MAP-09 | An answer already exists for a target code (resumed draft) | **Overwritten** by the mother's value, and the code is marked prefilled. Selecting a mother is an explicit act; it wins over an earlier auto-selected geography value. |
| ☐ MAP-10 | `consentGiven == true` and `INHERIT_CONSENT_FROM_MOTHER == true` | `did_we_receive_consent = "yes"`, code included in `prefilledCodes`. |
| ☐ MAP-11 | `consentGiven == true` and `INHERIT_CONSENT_FROM_MOTHER == false` | `did_we_receive_consent` untouched. One-constant reversal, per risk R1 pending ARMMAN sign-off. |
| ☐ MAP-12 | `consentGiven == false` | `did_we_receive_consent` untouched — never auto-answer "no" and trip the consent-refused gate on the Sakhi's behalf. |
| ☐ MAP-13 | `MotherPrefill.clear(answers, prefilledCodes, …)` | Removes only codes still in `prefilledCodes`; a code the Sakhi edited (already removed from the set) is left alone. |

## VM — `DynamicChildRegistrationViewModel`

| # | Case | Expected |
|---|---|---|
| ☐ VM-01 | Path = registered-mother, `loadMothers()` succeeds | `motherOptions` populated, `motherLoadFailed == false`. |
| ☐ VM-02 | Path = registered-mother, repository returns `null` | `motherLoadFailed == true`, `motherOptions` empty. Screen shows "connect once to link a mother". |
| ☐ VM-03 | Path = registered-mother, repository returns `emptyList()` | `motherLoadFailed == false`, `motherOptions` empty → "no registered mothers" (different message from VM-02). |
| ☐ VM-04 | Mother list is fetched | Only on first switch to the registered-mother path — **not** on form load, and not again on re-selecting the same path. Direct-path registrations make zero network calls. |
| ☐ VM-05 | `selectMother(m)` | Answers gain every mapped value; `prefilledCodes` matches `MAP-08`; `selectedMotherId == m.id`. |
| ☐ VM-06 | `selectMother` then `setAnswer(caregiver_name, "X")` | `caregiver_name` removed from `prefilledCodes`; value is `"X"`; every other prefilled code stays marked. |
| ☐ VM-07 | `selectMother(a)` then `selectMother(b)` | Codes the Sakhi edited between the two selections are **overwritten** by b (an explicit re-selection is authoritative); `prefilledCodes` is recomputed for b. |
| ☐ VM-08 | Path switches registered-mother → direct after a selection | `selectedMotherId == null`; untouched prefilled answers cleared; Sakhi-edited answers retained (MAP-13); `mother_beneficiary_id` cleared. |
| ☐ VM-09 | Path switches direct → registered-mother | No stale `mother_beneficiary_id` from a previous selection; picker starts empty. |
| ☐ VM-10 | `mother_beneficiary_id` value shape | The beneficiary **UUID**, never a display name. Asserts the submission mapper's `motherBeneficiaryId` passes the backend's `z.string().uuid()`. |
| ☐ VM-11 | `isSectionReady` for the section holding `mother_beneficiary_id`, registered-mother path, no mother selected | `false` — the field is required and unanswered. |
| ☐ VM-12 | Same, direct path | `true` — `hiddenByDirectPathFallback` still hides the field; CR-031 must not regress the CR-020 fallback. |
| ☐ VM-13 | Prefilled `age_or_dob_of_the_mother` violates the 10–50 year rule (live data has a mother with `dateOfBirth` in 2026) | Value is still written; `FormDateRuleset` renders its inline error and blocks Next. Do **not** silently drop it — the Sakhi must see and fix it. |
| ☐ VM-14 | Prefilled infant-irrelevant fields | `date_of_birth_of_infant`, `name_of_the_child`, `sex_of_child` and every Infant Details field are never written by prefill. |
| ☐ VM-15 | `selectMother` when `version == null` | No-op, no crash. |
| ☐ VM-16 | Eligibility window after selecting a mother | Still 183 days (`INELIGIBLE_MOTHER`), unchanged by prefill. |
| ☐ VM-17 | `displayNameForSelectedMother()` when the selected id is missing from `motherOptions` (resumed draft, mother closed) | Falls back to the raw id rather than blank — the Sakhi can see *something* and re-pick. |

## UI — manual QA (no Compose test infra; CR-036)

| # | Case | Expected |
|---|---|---|
| ☐ UI-01 | `mother_beneficiary_id` on the registered-mother path | Renders as a tappable picker row showing the selected mother's **name**, never the UUID, never a free-text keyboard. |
| ☐ UI-02 | Picker sheet row content | Name · age (from DOB) · village name · registration date. Two mothers with identical names are distinguishable. |
| ☐ UI-03 | Mother whose `currentPhase` is `ANC` | Shows the "Delivery not recorded" badge. All mothers in the current environment are `ANC`, so this must not be an error state or a disabled row. |
| ☐ UI-04 | Search box | Filters client-side on name, case-insensitive, substring. The `name=` query param is **not** used (exact HMAC hash — useless for typeahead). |
| ☐ UI-05 | Prefilled field | Shows the "From mother's record" hint below it. |
| ☐ UI-06 | Sakhi edits a prefilled field | Hint disappears immediately for that field only. |
| ☐ UI-07 | Airplane mode, cache warm | Picker opens from cache, no spinner hang, no error. |
| ☐ UI-08 | Airplane mode, cache cold | "Connect once to link a mother" message + a way back to the direct path. |
| ☐ UI-09 | Consent tab after selecting a mother with consent GIVEN | "Did we receive consent?" pre-answered Yes with the hint. Video/audio/photo still require the Sakhi to act (not inherited — CR-028). |
| ☐ UI-10 | Marathi locale | Every new string localized; no English fallback in the picker, hint, or the two empty states. |
| ☐ UI-11 | Long name (40+ chars) | Ellipsized in the field, wraps in the sheet row. No layout break. |

## Regression

| # | Case | Expected |
|---|---|---|
| ☐ REG-01 | Direct path end-to-end | Unchanged from CR-020: field hidden, `motherBeneficiaryId = null`, 365-day window, zero calls to `/beneficiaries` list. |
| ☐ REG-02 | Existing 60 CR-020 cases | Still pass. |
| ☐ REG-03 | Mother registration flow | Untouched — no shared file changed in a behaviour-affecting way. |

---

## Known gaps this CR does not close

- Rows 21–34 (address, mobile, phone owner, education, income, …) stay blank — CR-032.
- Rows 35–49 (delivery details) stay Sakhi-entered — needs the Delivery form.
- Consent **photo** (row 5) cannot be inherited: the mother's photo is never uploaded, only a
  device path sits in her `formData` — CR-028.
- `GET /beneficiaries` is **not row-scoped server-side** (no `sakhiId` in the where-clause). Risk
  R2 — must be verified with a second Sakhi's token before release.
- No pagination; backend `take: 50` is hardcoded. Breaks silently above 50 mothers per Sakhi.
