# CR-034 — Baseline risk at mother enrollment (on-device)

Rules sourced verbatim from `Registration_PW_D`, columns I ("Risk condition calculations") and
J ("Risk action"). Implementation: `data/enrollment/EnrollmentRiskAssessment.kt`; question and value
codes in `data/forms/MotherRegistrationQuestionCodes.kt`; wired at
`data/beneficiary/LocalEnrolmentBeneficiarySource.kt`.

## Decisions

1. The Excel's **"Severe"** tier (age) maps to `RiskLevel.HIGH` + `isPermanent = true`. No `SEVERE`
   value is added to `RiskLevel` — a fifth level would ripple through badges, filters and every risk
   sort for one rule.
2. **Q54 caesarean + Q51 interval < 3 years:** when Q51 is unanswered (Gravida = 1 hides it) the rule
   does **not** fire.
3. **Evaluated on read**, not stored: no Room column, no schema v5. Answers are already loaded where
   the beneficiary is built, so an edited answer can never leave a stale tag behind.
4. Age is derived from `date_of_birth` against the **stored registration date**, never the clock, and
   never from the `age_of_the_beneficiary` / `age_years` answer (renamed across schema versions).
5. Health-message-only rules return `RiskLevel.LOW` findings on purpose — they must reach the Sakhi
   as counselling without inflating the beneficiary's severity.

## Rules

| Level | Condition | Action |
| --- | --- | --- |
| HIGH (permanent) | Age < 19 or >= 35 | Referral |
| HIGH | Living children < Para | Referral + message |
| HIGH | Abortions >= 2 | Referral + message |
| HIGH | Still births >= 1 | Referral + message |
| HIGH | Last delivery `pre_term` | Health message |
| HIGH | `caesarian` AND interval `less_than_3_years_ago` | Referral + message |
| HIGH | Last delivery outcome `still_birth` | Referral + message |
| HIGH | Sickle cell `sickle_cell_disease_scd` | Referral + message |
| MODERATE | Gravida >= 4 | Referral + message |
| MODERATE | Any real condition in Q43 | Referral |
| MODERATE | Any real condition in Q58 | Health message |
| (no level) | Home delivery, birth weight < 2.5 kg, Q52 complications, substance use, sickle cell trait | Health message |

## Cases

`EnrollmentRiskAssessmentTest` — A1-A8 age (boundaries 18/19/34/35/36, missing DOB, registration-date
reference, malformed date); B9-B19 obstetric counts (L<P incl. missing Para, abortions 1/2/blank/
non-numeric, still births 0/1, gravida 3/4); C20-C27 last delivery (term, caesarean x interval incl.
unanswered, outcome); D28-D30 sickle cell (SCD/SCT/negative codes); E31-E37 Q43/Q58 conditions incl.
the two different none-codes and Q58-only `thalassemia`; F38-F41 health-message-only rules must not
raise the level; G42-G46 aggregation, High->Low ordering with stable ties, action mapping;
I53 the full high-risk record raises 8 HIGH findings + 1 MODERATE, none double-counted.

`LocalEnrolmentBaselineRiskTest` — H47-H52 the badge on My Beneficiaries: all-low stays LOW, abortions
= 2 reads HIGH (the reported defect), gravida 4 reads MODERATE, a draft with no stored payload is
skipped rather than crashing, each beneficiary is graded from her own answers, child drafts stay
filtered out; plus the registration-date-vs-today age guard.

## Out of scope

Referral record creation, health-message dispatch, ANC schedule changes on high-risk detection, and
the child/infant flow. Server-side evaluation moves to GoRules next sprint — that CR replaces the
`baselineRiskLevel` call site and seeds `rules-service`, which today has no migrations, no seed and
no evaluate endpoint (`beneficiary-service` returns `riskConditionSummaries: []` on create).
