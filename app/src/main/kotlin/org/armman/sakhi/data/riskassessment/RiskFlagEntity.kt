package org.armman.sakhi.data.riskassessment

/**
 * Local cache of one entry of a `POST /risk-assessments` response's `riskFlags` array (punch-list
 * item 2/8, 2026-08-27) — the mobile-side twin of the server's `risk_flags` table, one row per
 * graded condition for a given visit submission, including NORMAL grades (mirrors
 * [org.armman.sakhi.data.visitform.RiskAssessmentFlagDto]'s own doc: not filtered to
 * abnormal-only, so a later "was this condition even evaluated" question can be answered from the
 * cache alone).
 *
 * Keyed by an auto-generated local [id] rather than the server flag id — Room needs a stable
 * primary key for a one-to-many child table, and the server id alone can't serve that role here
 * (a resubmission's [org.armman.sakhi.data.riskassessment.RiskAssessmentDao.upsertAssessmentWithFlags]
 * clears and re-inserts every flag row for the schedule rather than diffing by server id, so reuse
 * would only add complexity with no benefit — see that DAO method's own doc).
 *
 * [localScheduleUuid] is the join key back to [RiskAssessmentEntity] — same key, same reasoning as
 * that entity's own doc (never the server [riskAssessmentId] alone, so every read here stays a
 * plain equality lookup against what the UI already has on hand).
 *
 * `riskGradeLookupValueId` is kept as a raw String, NOT a shared grade enum — confirmed
 * (2026-08-24 discovery, this session) that grade taxonomy has no single flat enum: `gradeScale`
 * varies per condition (`BINARY`, `NORMAL_MILD_MODERATE_SEVERE`, `NORMAL_LOW_MEDIUM_HIGH`), so a
 * lookup-value UUID is the only representation that is correct for every condition without a
 * per-scale enum this table would have to pick one of.
 */
@androidx.room.Entity(
  tableName = "risk_flags",
  indices = [androidx.room.Index(value = ["localScheduleUuid"])],
)
data class RiskFlagEntity(
  @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
  val localScheduleUuid: String,
  /** Server `risk_flags.id` — kept for reference/debugging; never looked up by directly today. */
  val serverFlagId: String,
  val riskConditionId: String,
  val riskGradeLookupValueId: String,
  /** [org.armman.sakhi.data.visitform.RiskAssessmentFlagDto.observedValueJson], serialized to a
   * JSON string at write time (same "store the server's own shape, don't reinterpret it"
   * reasoning as [overallRiskCategory] on [RiskAssessmentEntity]) — null when the server omitted
   * it. No `@TypeConverter` needed; the mapping happens once, at
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.cacheRiskAssessment]. */
  val observedValueJson: String?,
  val isReferralTrigger: Boolean,
  val isEducationTrigger: Boolean,
  val isHrVisitTrigger: Boolean,
)
