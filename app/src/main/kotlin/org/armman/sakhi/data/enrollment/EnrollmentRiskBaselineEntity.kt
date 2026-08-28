package org.armman.sakhi.data.enrollment

/**
 * A frozen, one-time snapshot of [EnrollmentRiskAssessment]'s result at the moment a mother's
 * registration was submitted (punch-list item 6, 2026-08-28: "persist baseline risk-condition
 * summary at enrollment — currently computed on-read only, no Room column/schema").
 *
 * ### This does NOT change how the Beneficiaries list badge works
 * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource] still calls
 * [EnrollmentRiskAssessment.baselineRiskLevel] live, on every read, exactly as before — that is a
 * deliberate existing design choice (see that call site's own comment: "recomputed on read rather
 * than stored so an edited answer can never leave a stale tag behind") and this table does not
 * replace it. Product decision (bharath, 2026-08-28): this row is an additional historical
 * record — "what was her risk assessed as AT ENROLLMENT" — for later reporting/summary use
 * (alongside the separate `risk_assessments`/`risk_flags` persistence, punch-list items 1/2),
 * kept deliberately independent of whatever the live badge shows today or after a later edit.
 *
 * ### Written once, never overwritten
 * [EnrollmentRiskBaselineDao.insertIfAbsent] uses `OnConflictStrategy.IGNORE` — a baseline is a
 * point-in-time fact about registration, not a cache kept fresh, so a later edit to the
 * registration answers (or a retried/resumed submission) must never silently rewrite it. If the
 * product later needs "re-baseline after a correction" that is a deliberate new action, not a
 * side effect of this table's normal write path.
 *
 * Keyed by [localBeneficiaryId] rather than a registration-submission id — there is exactly one
 * baseline per beneficiary, ever, same one-row-per-subject shape as every other per-beneficiary
 * cache in this database.
 */
@androidx.room.Entity(tableName = "enrollment_risk_baselines")
data class EnrollmentRiskBaselineEntity(
  @androidx.room.PrimaryKey val localBeneficiaryId: String,
  /** [org.armman.sakhi.data.beneficiary.RiskLevel] stored by enum name — the worst-of-findings
   * overall level, same value [EnrollmentRiskAssessment.overall] would return for these findings. */
  val overallRiskLevel: String,
  /** [EnrollmentRiskFinding] list serialized to JSON via Gson at write time (see
   * [EnrollmentRiskBaselineTrigger]) — condition/riskLevel/action enums serialize by name. No
   * `@TypeConverter` needed; this table has exactly one caller writing it and one shape reading
   * it back. Empty list ("[]") when no condition was flagged, not null — a beneficiary with a
   * clean baseline still has a real (empty) baseline record, distinct from "never enrolled
   * through this code path" (row absent entirely). */
  val findingsJson: String,
  /** Local write time — this is the "at enrollment" timestamp; there is no separate server
   * `evaluatedAt` for this baseline (it is never sent to or returned by the backend). */
  val computedAtEpochMillis: Long,
)
