# Test Cases — Visit Schedule Generation (CR-022)

**Package:** `data/schedule/` (new) — `ScheduleGenerator`, `ScheduleRuleSource`, `HardcodedRuleSource`, `VisitScheduleEntity`, `VisitScheduleDao`, `RoomVisitScheduleRepository`
**Rules source:** `docs/project-docs/Arogya_Sakhi_SRS_v3.0.md` §3A.2.3 (FR-S-3.1 … 3.7, SR-ANC-01, SR-NN-01), lines 247–329
**Backend contract:** `docs/backend-requests/CR-023-visit-schedule-sync-api.md`
**Plan:** `docs/plans/visit-flow-completion-crs.md` (CR-022)

**Scope agreed (2026-08-04):** Mobile-only. Generation + local persistence + trigger wiring +
supersession. Rules **hardcoded in Kotlin** behind `ScheduleRuleSource` (GoRules deferred to M3,
CR-032). Sync upload is written but cannot be integration-tested until CR-023 deploys — unit-tested
against a fake API. Visit tracker UI is **out of scope** (CR-024, M3).

**Runnable tests:** JVM units only per repo convention. No `androidTest` directory exists.

Status legend: ☐ not yet implemented · ☑ implemented & passing · ⚠ blocked on an open question.

---

## Open questions affecting these cases

| # | Question | Cases affected | Interim behaviour |
|---|---|---|---|
| Q1 | **PP schedule dates** — the SRS table's Anchor / Scheduled / Window-close columns disagree. See §PP note below for the reading we have implemented. | PP-1 … PP-8 | Implemented per the reconciled reading; marked ⚠ |
| Q2 | **ANC-HR trigger** — which conditions generate an HR visit? One per detection or one per pregnancy? | HR-1 … HR-6 | `perDetection = true`; trigger passed in by the caller, not derived |
| Q3 | **`LAPSED` is not in the server enum.** SRS FR-S-3.7 says open ANC visits are "marked as LAPSED" on delivery, but `VisitScheduleStatus` is `GENERATED / OPEN / MISSED / COMPLETED / SUPERSEDED / CANCELLED`. | ANC-14, ANC-15 | Mapped to `CANCELLED` with `reasonCode = "LAPSED_ON_DELIVERY"`. **Needs a ruling — if ARMMAN wants a distinct state, the server enum needs a migration.** |

None of these block starting. All three are single values or a single mapping isolated in
`HardcodedRuleSource`, changeable in one line plus a test update.

---

# CR-022a — Room schema + the rule-source seam

## 1. Room — `VisitScheduleDaoTest` (unit, in-memory DB)

| # | Name | Given / When / Then |
|---|---|---|
| DB-1 | ☐ `insert and read back a schedule row` | Given an empty DB; When one row is inserted; Then it reads back with every field intact, `status = GENERATED`, `serverScheduleId = null`. |
| DB-2 | ☐ `localScheduleUuid is the primary key` | When two rows share a `localScheduleUuid`; Then the second replaces the first (no duplicate row). |
| DB-3 | ☐ `query by beneficiary returns only that beneficiary` | Given rows for two beneficiaries; Then `getForBeneficiary(id)` returns only the matching set. |
| DB-4 | ☐ `query by status filters correctly` | Given GENERATED + COMPLETED + SUPERSEDED rows; Then `getByStatus(GENERATED)` returns only GENERATED. |
| DB-5 | ☐ `rows sort by scheduled date then sequence` | Given rows inserted out of order; Then the query returns `scheduledDate ASC, sequenceNo ASC`. |
| DB-6 | ☐ `unsynced rows are queryable` | Given rows with and without `serverScheduleId`; Then `getUnsynced()` returns only those with null. |
| DB-7 | ☐ `writing serverScheduleId does not alter other fields` | When `markSynced(localUuid, serverId)`; Then only `serverScheduleId` changes. |
| DB-8 | ☐ `migration v3 to v4 preserves existing draft tables` | Given a v3 DB with enrollment / dynamic-form / child drafts populated; When migrated to v4; Then all three tables survive intact and `visit_schedules` exists and is empty. |
| DB-9 | ☐ `rows survive process death` | Given rows written; When the DB is closed and reopened; Then all rows are present. |

## 2. The seam — `ScheduleRuleSourceTest` + `ScheduleGeneratorSeamTest`

These prove CR-032 (GoRules, M3) will be a binding swap rather than a rewrite. **If these fail or
are weak, the M3 estimate is invalid.**

| # | Name | Given / When / Then |
|---|---|---|
| RS-1 | ☐ `generator produces a full schedule against a fake rule source` | Given a `FakeRuleSource` returning interval 10 instead of 30; When an ANC schedule is generated; Then visits are 10 days apart. Proves the generator reads the interval rather than knowing it. |
| RS-2 | ☐ `generator reads window width from the rule source` | Given a fake returning ±2; Then every window is 5 days wide, not 11. |
| RS-3 | ☐ `generator reads visit count from the rule source` | Given a fake returning 3; Then exactly 3 rows are produced regardless of EDD. |
| RS-4 | ☐ `ruleVersion from the source is stamped on every row` | Given a fake with `ruleVersion = "test-v9"`; Then every row carries `generatedByRuleVersion = "test-v9"`. |
| RS-5 | ☐ `HardcodedRuleSource exposes the seeded v1 rule version id` | Then `ruleVersion` equals the UUID seeded by CR-023 §3. **Fails until backend supplies it** — assert against a constant so the failure is obvious. |
| RS-6 | ☐ `no scheduling constant exists outside HardcodedRuleSource` | Static check: no bare integer literal other than 0 or 1 in `data/schedule/` excluding `HardcodedRuleSource.kt`. Enforced in review; implemented as a lint/unit assertion if practical. |

---

# CR-022b — ANC schedule (FR-S-3.1 … 3.7, SR-ANC-01)

Fixture unless stated: LMP `2026-01-01`, EDD `2026-10-08` (LMP + 280).

## 3. Count formula — `AncScheduleGeneratorTest`

Formula: `((EDD − registrationDate) / 30) + 1`, uncapped, integer division.

| # | Name | Given / When / Then |
|---|---|---|
| ANC-1 | ☐ `registration on LMP date yields 10 visits` | Registration `2026-01-01`, EDD `2026-10-08` → (280/30)+1 = 10. |
| ANC-2 | ☐ `registration 30 days before EDD yields 2 visits` | Registration `2026-09-08` → (30/30)+1 = 2. |
| ANC-3 | ☐ `registration 29 days before EDD yields 1 visit` | Registration `2026-09-09` → (29/30)+1 = 1 (integer division floors). |
| ANC-4 | ☐ `registration on EDD yields 1 visit` | Registration `2026-10-08` → 1 (ANC1 only). |
| ANC-5 | ☐ `registration after EDD yields 1 visit and no negative count` | Registration `2026-10-20` → 1, no crash, no negative rows. |
| ANC-6 | ☐ `mid-pregnancy registration` | Registration `2026-05-01`, 160 days to EDD → (160/30)+1 = 6. |

## 4. Dates and windows

| # | Name | Given / When / Then |
|---|---|---|
| ANC-7 | ☐ `ANC1 is the registration date itself` | Then `ANC1.scheduledDate == registrationDate`. |
| ANC-8 | ☐ `ANC1 window is one-sided Day 0 to Day +5` | Then window = `[regDate, regDate+5]` — **not** ±5. Regression guard: `windowStart != regDate − 5`. |
| ANC-9 | ☐ `ANC2 onwards are 30 days from the previous scheduled date` | Then ANC2 = ANC1+30, ANC3 = ANC2+30, … Chained from the *previous scheduled date*, never recomputed from the anchor (no cumulative drift). |
| ANC-10 | ☐ `ANC2 onwards use a ±5 window` | Then window = `[scheduled−5, scheduled+5]`, 11 days wide. |
| ANC-11 | ☐ `visitCode and sequenceNo agree` | Then row *n* has `visitCode = "ANC$n"`, `sequenceNo = n`, `visitType = ANC`. |
| ANC-12 | ☐ `anchorType is REGISTRATION for all regular ANC rows` | Then every regular ANC row has `anchorType = REGISTRATION`. |
| ANC-13 | ☐ `no ANC visit is scheduled beyond EDD` | For every generated regular ANC row, `scheduledDate <= EDD`. |

## 5. Delivery lapsing (FR-S-3.7) ⚠ Q3

| # | Name | Given / When / Then |
|---|---|---|
| ANC-14 | ⚠ `delivery submit lapses all open ANC visits` | Given 10 ANC rows, 3 COMPLETED, 7 GENERATED/OPEN; When the delivery form is submitted; Then all 7 → `CANCELLED` + `reasonCode = LAPSED_ON_DELIVERY`, and the 3 COMPLETED are untouched. |
| ANC-15 | ⚠ `a visit inside its window is still lapsed` | Given an ANC row whose window contains today; When delivery is submitted; Then it is lapsed regardless — FR-S-3.7 says "regardless of status or window position". |
| ANC-16 | ☐ `lapsing does not delete rows` | Then row count before == row count after. History must survive. |

## 6. Post-EDD visit (SR-ANC-01)

| # | Name | Given / When / Then |
|---|---|---|
| PE-1 | ☐ `no delivery form by EDD+7 generates one post-EDD visit` | Then exactly one row, `scheduledDate = EDD+8`, `visitType = ANC_POST_EDD`. |
| PE-2 | ☐ `delivery form filled by EDD+7 generates nothing` | Then no `ANC_POST_EDD` row exists. |
| PE-3 | ☐ `delivery form filled exactly on EDD+7 generates nothing` | Boundary — EDD+7 counts as "by". |
| PE-4 | ☐ `window is one-sided EDD+8 to EDD+13` | Then window = `[EDD+8, EDD+13]`, **not** ±5. |
| PE-5 | ☐ `naming with 8 regular ANC visits is ANC9` | Then `visitCode = "ANC9"`. |
| PE-6 | ☐ `naming with 10 regular ANC visits is ANC11` | Then `visitCode = "ANC11"` (SRS worked example). |
| PE-7 | ☐ `naming counts only regular ANC, not HR rows` | Given 8 regular + 3 ANC_HR; Then still `ANC9`. |
| PE-8 | ☐ `only one post-EDD visit is ever generated` | Given generation runs twice; Then exactly one `ANC_POST_EDD` row. |

## 7. ANC-HR (FR-S-3.4) ⚠ Q2

| # | Name | Given / When / Then |
|---|---|---|
| HR-1 | ⚠ `HR anchors to the ACTUAL completion date, not the scheduled date` | Given ANC3 scheduled `2026-03-01` but completed `2026-03-06`; Then ANC_HR scheduled = `2026-03-21` (actual + 15), **not** `2026-03-16`. The single most-likely-to-be-misread rule in the SRS. |
| HR-2 | ⚠ `HR window is ±2 days` | Then window = `[scheduled−2, scheduled+2]`, 5 days wide. |
| HR-3 | ⚠ `anchorType is ACTUAL_VISIT and anchorVisitLocalUuid points at the trigger` | Then both are set and the anchor resolves to the triggering row. |
| HR-4 | ⚠ `HR is generated per detection` | Given two triggering visits; Then two HR rows. (Reverses if Q2 answers "one per pregnancy".) |
| HR-5 | ⚠ `HR does not shift the regular ANC chain` | Given an HR row is inserted; Then ANC4's scheduled date is unchanged. |
| HR-6 | ☐ `no HR visit is generated during the neonatal phase (SR-NN-01)` | Given a critical condition during NN1/NN2; Then **no** HR row is created — the referral flow applies instead. |

---

# CR-022c — PP and NN schedules

## PP note — reconciling the SRS table ⚠ Q1

The SRS PP table gives three columns that appear to contradict each other. They reconcile cleanly if
**"Anchor" is the scheduled date and "Scheduled" is the window start**:

| Visit | Anchor (= scheduled) | Window | Consistent? |
|---|---|---|---|
| PP1 | Day 0 | 0 → 14 | Fixed range, per the note |
| PP2 | Day +15 | 15 → 28 | Fixed range, per the note |
| PP3 | Day +58 | 53 → 63 | ✅ 58 ± 5 matches both columns |
| PP4 | Day +88 | 83 → 93 | ✅ 88 ± 5 matches both columns |
| PP5 | Day +118 | 113 → 123 | ✅ 118 ± 5 matches both columns |

Under this reading every ±5 window lines up exactly. The only remaining error is the SRS's PP5
anchor of "Day +105", which its own formula contradicts — the text says *"PP4 scheduled (88 + 30)"*,
and 88 + 30 = **118**, not 105. We have implemented 118.

**Implemented on this basis, pending ARMMAN confirmation.** Isolated in `HardcodedRuleSource.PpSchedule`.

## 8. PP — `PpScheduleGeneratorTest` (anchor: delivery date `2026-06-01`)

| # | Name | Given / When / Then |
|---|---|---|
| PP-1 | ⚠ `five PP visits are generated on delivery form submission` | Then exactly 5 rows, `visitType = PP`, sequence 1–5. |
| PP-2 | ⚠ `scheduled dates are delivery + 0/15/58/88/118` | Then `2026-06-01, 06-16, 07-29, 08-28, 09-27`. |
| PP-3 | ⚠ `PP1 window is a fixed range Day 0 to Day 14` | Then `[06-01, 06-15]` — not ±5. |
| PP-4 | ⚠ `PP2 window is a fixed range Day 15 to Day 28` | Then `[06-16, 06-29]` — not ±5. |
| PP-5 | ⚠ `PP3 to PP5 use ±5 windows` | Then `[53,63] [83,93] [113,123]` offsets from delivery. |
| PP-6 | ☐ `anchorType is DELIVERY_DATE` | All 5 rows. |
| PP-7 | ☐ `PP is not generated at enrolment` | Given enrolment only, no delivery; Then zero PP rows. |
| PP-8 | ☐ `PP5 completion raises the closure-prompt signal` | Then the repository exposes a closure-eligible flag. (Signal only — the prompt UI is out of scope.) |

## 9. NN — `NnScheduleGeneratorTest` (delivery date `2026-06-01`)

Three scenarios keyed on **when the delivery form was filled**, relative to the delivery date.

| # | Name | Given / When / Then |
|---|---|---|
| NN-1 | ☐ `Scenario A — form filled Day 0, NN1 and NN2 both generated` | Form filled `06-01`; Then NN1 scheduled `06-01` window `[06-01, 06-15]`; NN2 window `[06-16, 06-29]`. |
| NN-2 | ☐ `Scenario A — form filled Day 14 (boundary)` | Form filled `06-15`; Then still Scenario A. |
| NN-3 | ☐ `Scenario A — NN2 opens Day 15` | Then `NN2.windowStart = deliveryDate + 15`. |
| NN-4 | ☐ `Scenario B — form filled Day 15, NN1 skipped` | Form filled `06-16`; Then **no NN1 row at all** — not generated, and **not** marked MISSED. |
| NN-5 | ☐ `Scenario B — NN2 is still called NN2` | Then the single row is `visitCode = "NN2"`, `sequenceNo = 2`. Not renumbered to NN1. |
| NN-6 | ☐ `Scenario B — NN2 window is the remaining days to Day 28` | Form filled `06-20`; Then window `[06-20, 06-29]`. |
| NN-7 | ☐ `Scenario B — boundary Day 27` | Form filled `06-28`; Then Scenario B, window `[06-28, 06-29]`. |
| NN-8 | ☐ `Scenario C — form filled Day 28` | Form filled `06-29`; Then NN1 skipped, NN2 window is that single day. |
| NN-9 | ☐ `form filled after Day 28 generates no NN visits` | Form filled `06-30`; Then zero NN rows, no crash. Not covered by the SRS — **flag to ARMMAN if it occurs in practice.** |
| NN-10 | ☐ `NN windows are fixed ranges, never ±N` | Guard against the ±5 default leaking into NN. |
| NN-11 | ☐ `no NN_HR type exists` | SR-NN-01 — the generator never emits an HR row for the neonatal phase. |

---

# CR-022d — INC and CCV schedules

## 10. INC — `IncScheduleGeneratorTest` (DOB `2026-06-01`)

Two formulas. Both give the count of visits **after** INC1.

| # | Name | Given / When / Then |
|---|---|---|
| INC-1 | ☐ `early registration — 11 visits total` | Registered `2026-06-10` (Day 9 ≤ 58); count = Round((365−58)/30) = 10 additional; Then 11 rows, INC1…INC11. |
| INC-2 | ☐ `early registration — INC1 anchors to DOB + 58` | Then `INC1.scheduledDate = 2026-07-29`, **not** the registration date. |
| INC-3 | ☐ `early registration — INC2 onwards chain every 30 days` | Then INC2 = INC1+30 … INC11 = DOB+358. |
| INC-4 | ☐ `boundary — Day 58 is early registration` | Registered `2026-07-29` (exactly Day 58); Then early formula. |
| INC-5 | ☐ `boundary — Day 59 is late registration` | Registered `2026-07-30`; Then late formula. |
| INC-6 | ☐ `late registration — INC1 is the registration date itself` | Registered `2026-09-01` (Day 92); Then `INC1.scheduledDate = 2026-09-01`. |
| INC-7 | ☐ `late registration — count formula` | Day 92; count = Round((365−92)/30) = Round(9.1) = 9 additional; Then 10 rows. |
| INC-8 | ☐ `late registration — INC2 onwards chain from INC1` | Then INC2 = INC1+30, etc. |
| INC-9 | ☐ `hard cutoff — a visit beyond DOB+370 is dropped` | Construct a case producing a row at DOB+371; Then it is absent, and **not** marked MISSED. |
| INC-10 | ☐ `boundary — DOB+370 is kept, DOB+371 is dropped` | Exact cutoff test. |
| INC-11 | ☐ `dropping does not renumber the remaining visits` | Then surviving rows keep their original `sequenceNo` — no gap-filling. |
| INC-12 | ☐ `very late registration near 12 months` | Registered Day 360; count = Round(5/30) = 0 additional; Then 1 row (INC1) only, no negative count. |
| INC-13 | ☐ `INC window is ±5` | All rows. |
| INC-14 | ☐ `INC-HR anchors to actual completion + 15, window ±2` | Same rule as ANC-HR; `anchorType = ACTUAL_VISIT`. |
| INC-15 | ☐ `anchorType is DOB for early, REGISTRATION for late` | Then the anchor reflects which formula ran. |

## 11. CCV — `CcvScheduleGeneratorTest`

| # | Name | Given / When / Then |
|---|---|---|
| CCV-1 | ☐ `CCV is NOT generated at registration` | Given a child registered at Day 10; Then zero CCV rows. Per the SRS recommendation — the projected 6-visit forecast is a dashboard concern, explicitly out of app scope. |
| CCV-2 | ☐ `CCV is generated at the INC-to-CCV transition` | Given the last INC visit completed (or DOB+365 reached); Then CCV rows appear. |
| CCV-3 | ☐ `never at HR — two-monthly cadence` | Given no HR condition anywhere in the full 0–12m scan; Then visits are 60 days apart. |
| CCV-4 | ☐ `currently HR (SAM / danger sign) — 30-day HR visit` | Given SAM at the most recent INC visit; Then a `CCV_HR` row 30 days out. |
| CCV-5 | ☐ `currently HR (other) — 30-day HR visit` | Given another HR condition at the most recent INC visit; Then the same. |
| CCV-6 | ☐ `risk state uses a FULL 0-12m scan, not only the last 3 visits` | Given an HR condition at INC2 and none in the last 3; Then the state is **not** "never at HR". The SRS says the last 3 are "a subset of this scan" — easy to misread as "check the last 3". |
| CCV-7 | ☐ `the CCV journey opens with a CCV_HR visit` | Then the first row is `CCV_HR`. |
| CCV-8 | ☐ `risk state is evaluated once, at transition` | Given a later change of state; Then the existing CCV schedule is unchanged (only an approved LMP/EDD change regenerates). |

---

# CR-022e — Trigger wiring, supersession, sync-readiness

## 12. Generation triggers — `ScheduleTriggerTest`

| # | Name | Given / When / Then |
|---|---|---|
| TR-1 | ☐ `mother enrolment submit generates the ANC schedule` | Then rows exist immediately after `submitEnrollment` returns, **before** any sync. |
| TR-2 | ☐ `child registration submit generates NN and INC` | Then both sets exist. |
| TR-3 | ☐ `delivery form submit generates PP and NN, and lapses open ANC` | Then all three effects occur in one transaction. |
| TR-4 | ☐ `generation runs fully offline` | Given airplane mode / `isOnline() = false`; Then generation succeeds and rows persist. **The core SRS requirement.** |
| TR-5 | ☐ `generation is idempotent` | Given submit is invoked twice for the same beneficiary; Then the row count is unchanged — not doubled. |
| TR-6 | ☐ `generation failure does not lose the enrolment` | Given the generator throws; Then the enrolment draft is still saved and queued, and the error surfaces without data loss. |
| TR-7 | ☐ `generation precedes sync-queue pickup` | Then the schedule rows exist before the enrolment record is marked sync-eligible. |
| TR-8 | ☐ `consent-refused enrolment generates nothing` | Given consent = No; Then no schedule rows. |

## 13. Supersession — `ScheduleSupersessionTest`

| # | Name | Given / When / Then |
|---|---|---|
| SU-1 | ☐ `approved LMP change supersedes GENERATED and OPEN rows` | Then those rows → `SUPERSEDED`. |
| SU-2 | ☐ `COMPLETED rows are never superseded` | A visit that happened, happened. |
| SU-3 | ☐ `no row is ever deleted` | Row count before ≤ row count after. |
| SU-4 | ☐ `the new schedule carries an incremented rule version` | Then new rows differ from old in `generatedByRuleVersion`. |
| SU-5 | ☐ `only an approved change triggers regeneration` | Given an unapproved LMP edit; Then nothing regenerates. |
| SU-6 | ☐ `superseded rows are excluded from active queries` | Then `getActiveForBeneficiary` omits them. |

## 14. Sync-readiness — `ScheduleSyncExecutorTest` (fake API; live integration blocked on CR-023)

| # | Name | Given / When / Then |
|---|---|---|
| SY-1 | ☐ `unsynced rows are batched per beneficiary` | Then one request per beneficiary, not per row. |
| SY-2 | ☐ `serverScheduleId is written back on success` | Then each local row records its returned server ID. |
| SY-3 | ☐ `upload is deferred until the beneficiary has synced` | Given `serverBeneficiaryId = null`; Then the schedule upload is **deferred, not failed** — otherwise orphan schedules. |
| SY-4 | ☐ `a replayed upload does not duplicate locally` | Given the API returns `alreadyExisted`; Then local rows are marked synced, not re-inserted. |
| SY-5 | ☐ `HTTP failure leaves rows unsynced and retryable` | Then `serverScheduleId` stays null and no data is lost. |
| SY-6 | ☐ `409 SCHEDULE_CONFLICT is surfaced, not silently retried` | Then a non-retryable state with a readable message (reuse `SubmitErrorCopy`). |
| SY-7 | ☐ `sync is manual-trigger only` | Then no periodic tick, no reconnect trigger — SRS §3A.1. Registered in `ManualSyncTrigger.syncAllQueues()`. |

## 15. Cross-cutting

| # | Name | Given / When / Then |
|---|---|---|
| XC-1 | ☐ `all schedule maths uses LocalDate, never Instant` | Static check plus a DST-boundary case. A timezone shift moves a real visit to the wrong day. |
| XC-2 | ☐ `leap year is handled` | DOB `2028-02-29`; Then no crash, correct day counts. |
| XC-3 | ☐ `a full ANC + PP + NN + INC series generates in under 500 ms` | Measured on the CI JVM as a proxy; re-verified on a low-end device in manual QA. |
| XC-4 | ☐ `generating for 100 beneficiaries does not exhaust memory` | Batch sanity check. |
| XC-5 | ☐ `every generated row carries a non-null generatedByRuleVersion` | The M3 migration depends on this. No exceptions. |
| XC-6 | ☐ `every generated row carries a unique localScheduleUuid` | Collision check across a large generated set. |

---

## Manual QA (batched visual/device checks — not JVM tests)

| # | Check |
|---|---|
| MQ-1 | Enrol a woman with the device in airplane mode → schedule rows exist in the DB inspector immediately. |
| MQ-2 | Force-stop and reopen the app → rows still present. |
| MQ-3 | Full generation on a low-end device completes with no visible UI stall. |
| MQ-4 | DB upgrade from a v3 build with real drafts present → no data loss, no crash on first launch. |
| MQ-5 | Once CR-023 is deployed: enrol offline, reconnect, tap Data Upload → rows appear server-side with matching `local_schedule_uuid`s. |

---

## Summary

| Group | Cases | Blocked |
|---|---|---|
| CR-022a — schema + seam | 15 | 1 (RS-5, on backend UUID) |
| CR-022b — ANC | 30 | 9 (Q2, Q3) |
| CR-022c — PP + NN | 19 | 8 (Q1) |
| CR-022d — INC + CCV | 23 | 0 |
| CR-022e — triggers, supersession, sync | 21 | 7 (SY-*, live integration only) |
| Cross-cutting | 6 | 0 |
| **Total** | **114** | **25 partially blocked, none preventing a start** |
