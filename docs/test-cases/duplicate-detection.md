# Test cases — Duplicate detection & second pregnancy / new case handling

CR-033 · SRS FR-S-2.4 (duplicate) and FR-S-2.5 (re-enrolment) · Sakhi mobile app
Scope: the two live dynamic enrolment flows (MOTHER_REGISTRATION / CHILD_REGISTRATION).
The legacy static 4-step enrolment flow is unreachable from navigation and out of scope.

## Background

`POST /beneficiaries` answers a possible duplicate with **409** in two shapes:

| Situation | Envelope | App behaviour |
|---|---|---|
| Hard duplicate — matched case has no delivery **or** no closure | `409`, no `fieldErrors` | **Blocked.** Fixed localised message. `acknowledgeDuplicate` is never sent. |
| Re-enrolment — earlier journey complete, delivery recorded, LMP differs | `409` + `fieldErrors: {reason: "RE_ENROLLMENT", existingBeneficiaryId, resolution}` | **Prompt.** On confirm, resubmit with `acknowledgeDuplicate: true` and `case.previousBeneficiaryId`. |

A `409` can arrive at two moments: immediately on Submit while online, or later during a manual
Data Upload when the Sakhi is no longer on the form. Both must be answerable.

## 1. Parsing the 409 — `DuplicateOutcomeParserTest`

| # | Given | When | Then |
|---|---|---|---|
| 1.1 | A 409 with `reason=RE_ENROLLMENT` and `existingBeneficiaryId` | parsed | `NewPregnancyPrompt(existingBeneficiaryId)` |
| 1.2 | A 409 with no `fieldErrors` | parsed | `HardDuplicate` |
| 1.3 | `reason=RE_ENROLLMENT` but **no** `existingBeneficiaryId` | parsed | `HardDuplicate` — an unlinked "new pregnancy" would silently break her history, so blocking is the safer failure |
| 1.4 | `existingBeneficiaryId` present but blank | parsed | `HardDuplicate` |
| 1.5 | An unrecognised `reason` | parsed | `HardDuplicate` — never guessed at |
| 1.6 | `reason=re_enrollment` (lower case) | parsed | `NewPregnancyPrompt` |
| 1.7 | Empty body / HTML gateway page | parsed | `HardDuplicate`, no exception |

## 2. No internals on screen — `SubmitErrorCopyTest`

| # | Given | When | Then |
|---|---|---|---|
| 2.1 | `fieldErrors` holds only `reason` / `existingBeneficiaryId` / `resolution` | banner copy built | Generic sentence — never `RE_ENROLLMENT` or `Resubmit with acknowledgeDuplicate: true…` |
| 2.2 | Those keys alongside a real `pii.firstName` error | banner copy built | The real field message wins |

## 3. Request payload — `DynamicFormSubmissionMapperTest`

| # | Given | When | Then |
|---|---|---|---|
| 3.1 | No acknowledgement (first attempt) | request built | `acknowledgeDuplicate` and `case.previousBeneficiaryId` both absent, so the backend runs its own detection |
| 3.2 | Acknowledgement for `earlier-case-uuid` | request built | `acknowledgeDuplicate = true`, `previousBeneficiaryId = earlier-case-uuid`, rest of the payload unchanged |

## 4. Sync executor — `DynamicFormSyncExecutorTest`

| # | Given | When | Then |
|---|---|---|---|
| 4.1 | Re-enrolment 409 | `runOne` | `DuplicateConflict(NewPregnancyPrompt("earlier-case-uuid"))` |
| 4.2 | Re-enrolment 409 during a background `run()` | run completes | Prompt persisted on the draft's encrypted payload; row is `DUPLICATE_CONFLICT` |
| 4.3 | Hard-duplicate 409 | `runOne` | `DuplicateConflict(HardDuplicate)`; **no** prompt persisted |
| 4.4 | A normal draft | `runOne` | Request carries neither duplicate field |
| 4.5 | A draft whose payload holds an acknowledgement | `run()` | Request carries `acknowledgeDuplicate = true` + the earlier case link; row ends `SYNCED` |

## 5. Repository — `RoomDynamicFormDraftRepositoryTest`

| # | Given | When | Then |
|---|---|---|---|
| 5.1 | Online, re-enrolment 409 | `submitDraft` | `DuplicateConflict(NewPregnancyPrompt(...))` |
| 5.2 | A prompted draft, online | `confirmNewPregnancy` | Retried with acknowledgement + link; `Synced`; row `SYNCED`; stored prompt cleared |
| 5.3 | A prompted draft, offline | `confirmNewPregnancy` | No API call; `QueuedOffline`; row back to **PENDING** (not `DUPLICATE_CONFLICT`, which manual upload skips); acknowledgement persisted for the next upload |
| 5.4 | An unknown draft id | `confirmNewPregnancy` | `Failed` — never a success for an enrolment that no longer exists |
| 5.5 | A prompted draft | `dismissNewPregnancyPrompt` | Prompt cleared, no acknowledgement written, row stays `DUPLICATE_CONFLICT`, answers intact |
| 5.6 | A prompted draft | `getUploadRecords` | Record exposes `pendingNewPregnancyBeneficiaryId` |
| 5.7 | A hard-duplicate draft | `getUploadRecords` | Record exposes no prompt |

## 6. Mother form — `DynamicMotherRegistrationViewModelTest`

| # | Given | When | Then |
|---|---|---|---|
| 6.1 | Hard duplicate | submit | `SubmissionState.DuplicateBlocked`, no prompt, no override affordance |
| 6.2 | Re-enrolment | submit | `duplicatePrompt` set; state back to `Idle` (a question is pending, nothing is wrong) |
| 6.3 | Prompt on screen | confirm | Repository called with the earlier case id; state `Success` |
| 6.4 | Prompt on screen, offline | confirm | `Success` — the acknowledgement is queued, not lost |
| 6.5 | Prompt on screen, retry still rejected as a hard duplicate | confirm | `DuplicateBlocked`, not `Success` |
| 6.6 | Prompt on screen | decline | Prompt cleared, form still editable with answers intact, stored prompt cleared so Home stops asking |
| 6.7 | No prompt on screen | `onConfirmNewPregnancy` | No-op — a stray call can never acknowledge a duplicate she was never asked about |
| 6.8 | A leftover prompt from a previous attempt | submit again | Prompt cleared before the new attempt |

## 7. Home — queued drafts — `HomeViewModelTest`

| # | Given | When | Then |
|---|---|---|---|
| 7.1 | Drafts pending / hard-duplicate only | observed | No review offered |
| 7.2 | A `DUPLICATE_CONFLICT` draft carrying a prompt | observed | `DuplicateReview(localBeneficiaryId, existingBeneficiaryId, submittedAt)` |
| 7.3 | Two prompted drafts | observed | Oldest first, one at a time |
| 7.4 | A review shown | confirm | That draft acknowledged; nothing dismissed |
| 7.5 | A review shown | decline | That draft's prompt cleared; nothing acknowledged |

## 8. Child flow — `DynamicChildRegistrationViewModelTest`

| # | Given | When | Then |
|---|---|---|---|
| 8.1 | 409 on a child enrolment | submit | `Failed(DUPLICATE)` → fixed localised blocking sentence. The FR-S-2.5 branch is unreachable here: the backend only reaches it when an LMP is supplied, and a child enrolment never sends one. |

## 9. Manual checks (no Compose UI test infrastructure in this repo)

1. **Blocked duplicate** — enrol a mother who already has an ACTIVE case. Expect the blocking
   snackbar, no dialog, no Confirm button, form still filled in.
2. **New pregnancy, online** — enrol a mother whose earlier case has both a delivery and a closure
   date, with a different LMP. Expect the confirmation dialog; confirm; expect the success screen.
   Verify in the backend that a **new** case row exists, `previousBeneficiaryId` points at the
   earlier case, and the earlier case's data is unchanged.
3. **New pregnancy, offline resolution** — same, but turn networking off before Submit, then tap
   Data Upload with the network on. Expect the conflict on Home, then the review dialog; confirm and
   tap Data Upload again; expect it to upload.
4. **Decline then correct** — decline the prompt, fix the LMP, submit again. Expect a normal
   enrolment and **no** review dialog on Home afterwards.
5. **Marathi** — repeat 1 and 2 with the app language set to Marathi; every sentence and both
   buttons must be translated, with no English or raw identifiers anywhere.
6. **Restart mid-flow** — get a conflict during Data Upload, force-stop the app, reopen. The review
   dialog must still be offered (the prompt is persisted, not held in memory).
