# Test Cases — Children Register (CR-020, tentative)

**Screens / packages:** `ui/childregistration/` (`DynamicChildRegistrationScreen`, `DynamicChildRegistrationViewModel`), `data/childregistration/` (`ChildRegistrationSubmissionMapper`, `ChildRegistrationSubmissionCoordinator`, `ChildFormSyncExecutor`/`Worker`/`Scheduler`, `ChildFormDraftEntity`/`Dao`/`RoomChildFormDraftRepository`), `di/ChildRegistrationModule`. Entry via the existing beneficiary-type chooser (`ui/enrollment/EnrollmentScreen` — additive: Child option wired, Pregnant Woman untouched).
**Schema source:** `GET /forms/CHILD_REGISTRATION/active-version` (v2, published 2026-07-23) — sections **Consent** (8), **Personal Info** (32), **Infant Details** (11). Fields render at runtime; no local field list.
**Standalone fork:** reuses only the form-agnostic engine (`DynamicFormRenderer`, `FormsRepository`, `FormNumericRangeValidator`, `FormVisibilityEvaluator`, `FormComputedFieldEvaluator`, `GeographyFieldOptionsResolver`). No mother submit/sync/mapper/screen file is modified.
**APIs:** `GET /forms/CHILD_REGISTRATION/active-version` (schema) · `POST /beneficiaries` (create, call 1) · `POST /forms/CHILD_REGISTRATION/submissions` (answers, call 2). Idempotent on `case.localCaseUuid` / `localSubmissionUuid`.

**Scope & decisions locked (2026-07-23):**
- Standalone child engine; mother flow untouched.
- **Path question** `who_are_you_registering_in_the_program` drives conditional logic (client-side, since `validationJson` is empty):
  - `child_directly...` → `mother_beneficiary_id` **hidden & not required**, `motherBeneficiaryId = null`, eligibility **0–365 days**.
  - `child_of_a_registered_pregnant_woman` → `mother_beneficiary_id` **shown & required**, sent as `case.motherBeneficiaryId`, eligibility **0–183 days** (SRS FR-S-2.3).
- **Eligibility enforced client-side** from `date_of_birth_of_infant`.
- `beneficiary_id` + `unique_id` are **hidden** (server-owned; added to non-renderable set). `unique_id` computed `UNIQUE_ID`; `current_age_of_infant_in_days` computed `CHILD_AGE_MONTHS` (new evaluator case).
- `caseType=CHILD` + `beneficiaryTypeLookupId`/`caseTypeLookupId` resolved via `LookupRepository`, never hardcoded.
- Full answer set (all `question_code`s) posted to `/submissions`; `POST /beneficiaries` gets only `pii` + `case` + `childDetails` (`.strict()`).

**Runnable tests:** JVM units only per repo convention — `DynamicChildRegistrationViewModelTest`, `ChildRegistrationSubmissionMapperTest`, `ChildFormSyncExecutorTest`. UI/NAV rows are spec, verified via batched visual QA.

Status legend: ☐ not yet implemented · ☑ implemented & passing.

> **Delivery status (2026-07-23):** All runnable JVM tests are written — `ChildRegistrationSubmissionMapperTest` (15), `ChildFormSyncExecutorTest` (9), `DynamicChildRegistrationViewModelTest` (18). They were NOT compiled/run in the authoring environment (no Android SDK / no network for the Gradle wrapper). Run `./gradlew detekt :app:testDebugUnitTest` locally to confirm green before merge; the ☐/☑ marks below are not yet flipped because passing is unverified.

---

## 1. Navigation & entry — spec

| # | Name | Given / When / Then |
|---|---|---|
| NAV-1 | ☐ `child option opens child registration` | Given the chooser; When Child is selected and continue; Then route `child-registration` opens. |
| NAV-2 | ☐ `pregnant woman path unchanged` | When Pregnant Woman is selected; Then route `enrollment/mother-registration` (regression — no behaviour change). |
| NAV-3 | ☐ `back from child form returns to chooser/home` | System back inside the child form → chooser; back on chooser → Home. |
| NAV-4 | ☐ `submit navigates home, subgraph cleared` | On successful submit/queue; Then Home, child sub-graph popped so system back does not re-enter it. |

## 2. Schema load — `DynamicChildRegistrationViewModelTest`

| # | Name | Given / When / Then |
|---|---|---|
| VM-1 | ☐ `loads active version online` | Given online; When screen opens; Then `getActiveVersion("CHILD_REGISTRATION")` is called and 3 sections/tabs render in order Consent → Personal Info → Infant Details. |
| VM-2 | ☐ `renders from cache offline` | Given a previously cached version and no connectivity; Then the form renders from cache (no crash, no blank). |
| VM-3 | ☐ `no cache offline shows recoverable error` | Given offline and no cached version; Then a "couldn't load form — try again online" state (not a crash, not an empty form). |
| VM-4 | ☐ `loading state precedes content` | While the fetch is in flight; Then `isLoading = true`; after; `isLoading = false`. |
| VM-5 | ☐ `unknown input_type does not break the form` | Given a field with an unrecognised `input_type`; Then it is skipped/placeholdered, the rest of the form still renders. |

## 3. Field behaviour — `DynamicChildRegistrationViewModelTest`

| # | Name | Given / When / Then |
|---|---|---|
| VM-6 | ☐ `hidden fields never render` | `beneficiary_id` and `unique_id` are in the non-renderable set → never shown, never block submit. |
| VM-7 | ☐ `unique_id computed` | Given required inputs for `UNIQUE_ID`; Then `unique_id` is auto-populated and included in `formData`. |
| VM-8 | ☐ `age computed from DOB` | Given `date_of_birth_of_infant` set; Then `current_age_of_infant_in_days` auto-fills via `CHILD_AGE_MONTHS`; changing DOB recomputes it. |
| VM-9 | ☐ `multiselect stored as list` | `did_the_baby_have_any_complications...` selections stored as a list of `value_code`s. |
| VM-10 | ☐ `multiselect_date stores code+date` | `vaccination_taken_at_birth` stores each selected code with its date; "None" clears the others. |

## 4. Conditional visibility (path question) — `DynamicChildRegistrationViewModelTest`

| # | Name | Given / When / Then |
|---|---|---|
| VM-11 | ☐ `direct path hides mother id` | When `who_are_you_registering_in_the_program = child_directly...`; Then `mother_beneficiary_id` is hidden and not required; submit allowed without it. |
| VM-12 | ☐ `registered-mother path shows+requires mother id` | When path = `child_of_a_registered_pregnant_woman`; Then `mother_beneficiary_id` is shown and required; submit blocked until filled. |
| VM-13 | ☐ `switching path clears stale mother id` | Given a mother id entered, then path switched to direct; Then the value is cleared and excluded from the payload. |

## 5. Validation — `DynamicChildRegistrationViewModelTest`

| # | Name | Given / When / Then |
|---|---|---|
| VM-14 | ☐ `required fields block submit` | Given any visible required field empty; Then submit disabled and the offending field/section is flagged. |
| VM-15 | ☐ `numericRange — family members` | `how_many_family_members...`: 1 → invalid, 2 → valid, 15 → valid, 16 → invalid (min 2 / max 15). |
| VM-16 | ☐ `numericRange — child length` | `child_length_at_birth_in_cm`: 34.9 invalid, 35 valid, 60 valid, 60.1 invalid. |
| VM-17 | ☐ `numericRange — child weight` | `child_weight_at_birth_in_kg`: 0.4 invalid, 0.5 valid, 15 valid, 15.1 invalid. |
| VM-18 | ☐ `eligibility — direct 0–365 days` | Direct path: DOB age 0 valid, 365 valid, 366 invalid (blocked with message); future DOB invalid. |
| VM-19 | ☐ `eligibility — registered-mother 0–183 days` | Registered-mother path: DOB age 183 valid, 184 invalid. |
| VM-20 | ☐ `consent = No blocks submit` | `did_we_receive_consent = no` → submit blocked with the consent-refused message. |
| VM-21 | ☐ `consent media gating` | `arogya_sakhi_video` + `consent_audio` (`requirePlaybackComplete`) must complete before the section can be passed. |
| VM-22 | ☐ `consent photo required (live camera)` | `consent_form_photo` required; capture is live-camera-only (no gallery); missing → submit blocked. |
| VM-23 | ☐ `mobile number format` | `mobile_number` must be a valid 10-digit number (if repo mobile-format rule applies); invalid → flagged. |

## 6. Mapper → `POST /beneficiaries` — `ChildRegistrationSubmissionMapperTest`

| # | Name | Given / When / Then |
|---|---|---|
| MAP-1 | ☐ `pii name split` | `caregiver_name...` "Asha Devi Patil" → `firstName=Asha, middleName=Devi, lastName=Patil`; two-token and single-token names handled without crashing. |
| MAP-2 | ☐ `pii contact + address` | `mobile_number` → `pii.phone`; `enter_the_beneficiary_address` → `pii.addressLine`. |
| MAP-3 | ☐ `geography ids resolved` | The 7 geo selects → `pii.stateId/districtId/talukaId/villageId/padaId/phcId/healthSubCentreId` using the response `geography` array (ids, not labels). |
| MAP-4 | ☐ `childDetails mapping` | `date_of_birth_of_infant`→`dateOfBirth`, `sex_of_child`→`sex`, `child_weight_at_birth_in_kg`→`birthWeightKg`, `child_length_at_birth_in_cm`→`birthLengthCm`. |
| MAP-5 | ☐ `prematureFlag from term_of_delivery` | `pre_term`→`prematureFlag=true`; `full_term`/`post_term`→`false`. |
| MAP-6 | ☐ `case fields` | `case.caseType=CHILD`; `beneficiaryTypeLookupId`/`caseTypeLookupId` from `LookupRepository` (fail fast if unresolved — no hardcoded UUID). |
| MAP-7 | ☐ `motherBeneficiaryId by path` | Direct → `null`; registered-mother → the entered `mother_beneficiary_id`. |
| MAP-8 | ☐ `localCaseUuid stable across retries` | The same draft maps to the same `localCaseUuid` on every attempt (idempotency). |
| MAP-9 | ☐ `strict payload — no extra keys` | Only `pii`/`case`/`childDetails`/`consent`/`acknowledgeDuplicate` are sent; socioeconomic/feeding/vaccination answers are NOT in this body. |

## 7. Mapper → `/submissions formData` — `ChildRegistrationSubmissionMapperTest`

| # | Name | Given / When / Then |
|---|---|---|
| SUB-1 | ☐ `full answer set included` | `formData` contains every answered visible `question_code` (avoids 422 on required fields). |
| SUB-2 | ☐ `hidden/server fields excluded` | `beneficiary_id`/`unique_id` handled per contract (computed `unique_id` included if required; server-owned `beneficiary_id` not fabricated). |
| SUB-3 | ☐ `uses server beneficiaryId` | `beneficiaryId` in the submission = the id returned by `POST /beneficiaries`, not the local UUID. |
| SUB-4 | ☐ `formVersionId is the answered version` | The `formVersionId` sent = the version answered against at save time, not re-fetched at sync. |

## 8. Offline submit + sync (two-call) — `ChildFormSyncExecutorTest`

| # | Name | Given / When / Then |
|---|---|---|
| SYNC-1 | ☐ `online submit — happy path` | Online: `POST /beneficiaries` 2xx → `POST /submissions` 2xx → draft `SYNCED`, remote ids stored, Sakhi sees success. |
| SYNC-2 | ☐ `offline submit queues` | Offline: draft saved `PENDING`, `QueuedOffline` returned, no network call; background worker syncs later. |
| SYNC-3 | ☐ `409 on beneficiaries → duplicate` | `POST /beneficiaries` 409 → `DuplicateConflict` surfaced, held (not auto-retried); Sakhi must acknowledge (FR-S-2.4/2.5). |
| SYNC-4 | ☐ `acknowledge duplicate re-submits` | After acknowledge; Then re-submit sends `acknowledgeDuplicate=true` and proceeds. |
| SYNC-5 | ☐ `422 on submissions → failed` | `POST /submissions` 422 → `Failed` with the backend message; draft `FAILED`, not silently retried. |
| SYNC-6 | ☐ `drop between calls is retry-safe` | Connectivity drops after call 1 succeeds; retry re-runs both calls; idempotency keys prevent a duplicate beneficiary/submission. |
| SYNC-7 | ☐ `transient network → retryable` | IO error mid-call → `Retryable`; WorkManager retries with backoff; no hard error shown. |
| SYNC-8 | ☐ `draft lifecycle` | Status transitions PENDING → SYNCING → (SYNCED | DUPLICATE | FAILED); `retryCount`/`lastAttemptAt`/`lastErrorMessage` updated. |

## 9. Security & persistence — `ChildFormSyncExecutorTest` / repo test

| # | Name | Given / When / Then |
|---|---|---|
| SEC-1 | ☐ `PII in encrypted store only` | The answer payload (PII) lives in `SecureKeyValueStore`; the `child_registration_drafts` Room table holds only non-PII sync metadata. |
| SEC-2 | ☐ `draft survives app restart` | A queued draft persists across process death and is picked up by the next sync run. |

## 10. Compose UI — spec (batched visual QA)

| # | Name | Given / When / Then |
|---|---|---|
| UI-1 | ☐ `three section tabs` | Consent / Personal Info / Infant Details tabs; active tab purple-underlined; forward tabs gated until current is valid. |
| UI-2 | ☐ `field types render per design` | radio/select/number/date/text/text_geo/multiselect/multiselect_date/media/image render per the design-token rules. |
| UI-3 | ☐ `conditional mother id row` | Mother-id row appears/disappears with the path question, no layout jump. |
| UI-4 | ☐ `validation + eligibility messaging` | Range/required/eligibility/consent errors are inline and legible; submit disabled while invalid. |
| UI-5 | ☐ `duplicate + failure dialogs` | 409 duplicate → acknowledge dialog; 422 → failure message with retry affordance. |
| UI-6 | ☐ `tablet + mobile` | 800×1280 and 376×884 both match the design-token layout rules. |
| UI-7 | ☐ `strings EN + MR` | Every static string present in `values/` and `values-mr/`. |

---

## Open items — resolved (2026-07-23)
1. **`project_name`** → resolved from the Sakhi **session/assignment** (`session.projectId`), same as the mother path's `GeographyFieldOptionsResolver.PROJECT_NAME` — not a separate server lookup category.
2. **`CHILD` `caseTypeLookupId` / `beneficiaryTypeLookupId`** → from `LookupRepository` (`CASE_TYPE` / `BENEFICIARY_TYPE` categories). Mapper fails fast with a clear message if unseeded. *Confirm the exact seeded `value_code`s (assumed `CHILD` under both).* 
3. **`mobile_number`** → reuse the existing enrollment 10-digit validation.
4. **Error copy** → drafted below (final wording subject to ARMMAN content team).

### Error copy (draft — EN + MR, become string resources)

| Key | English | Marathi |
|---|---|---|
| `child_reg_consent_refused` | Registration cannot continue without the beneficiary's consent. | लाभार्थीच्या संमतीशिवाय नोंदणी पुढे करता येणार नाही. |
| `child_reg_dob_future` | Date of birth cannot be in the future. | जन्मतारीख भविष्यातील असू शकत नाही. |
| `child_reg_ineligible_direct` | This child is older than 12 months (365 days) and cannot be registered directly. | हे बाळ १२ महिन्यांपेक्षा (३६५ दिवस) मोठे आहे, त्यामुळे थेट नोंदणी करता येणार नाही. |
| `child_reg_ineligible_mother` | A child of a registered mother must be registered within 6 months (183 days) of birth. | नोंदणीकृत मातेच्या बाळाची नोंदणी जन्मानंतर ६ महिन्यांच्या (१८३ दिवस) आत करणे आवश्यक आहे. |
| `child_reg_duplicate` | A matching child record already exists. Continue registering this as a new record? | जुळणारे बाळाचे रेकॉर्ड आधीच अस्तित्वात आहे. नवीन रेकॉर्ड म्हणून नोंदणी सुरू ठेवायची का? |
| `child_reg_submit_failed` | Registration could not be submitted. Please review the form and try again. | नोंदणी सबमिट करता आली नाही. कृपया फॉर्म तपासून पुन्हा प्रयत्न करा. |
