package org.armman.sakhi.data.riskassessment

/**
 * Local cache of one `POST /risk-assessments` response (punch-list item 1/8, 2026-08-27) — the
 * mobile-side twin of the server's `risk_assessments` table
 * (`arogyasakhi-service/apps/risk-referral-service/prisma/schema.prisma`), one row per visit
 * submission that got a real-time risk grading.
 *
 * Keyed by [localScheduleUuid], NOT the server [id] — same convention as
 * [org.armman.sakhi.data.referral.ReferralLinkEntity] and for the same reason: the Beneficiary
 * Profile / Visit Tracker / My Beneficiaries screens all key their visit rows by
 * [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit.id] (== `localScheduleUuid`), so a
 * lookup never needs to resolve one id from the other. `submissionId` is unique too (the actual
 * server idempotency key) but is not what any UI screen has on hand at read time.
 *
 * Written once by [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator
 * .cacheRiskAssessment], right after `POST /risk-assessments` succeeds (including on a retried
 * attempt via [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator
 * .createRiskAssessmentWithRetry] — idempotent on [submissionId], so a retry's response is the
 * same assessment, not a new one). Never uploaded — this is a read-through cache of a response
 * the backend already accepted, not a sync queue, so unlike every `*_drafts` table in this
 * database it has no `syncStatus`/`retryCount`.
 *
 * `overallRiskCategory` is kept as a raw String rather than an enum for the same reason
 * [org.armman.sakhi.data.visitform.RiskAssessmentResponseData] does — see that DTO's own doc.
 */
@androidx.room.Entity(tableName = "risk_assessments")
data class RiskAssessmentEntity(
  @androidx.room.PrimaryKey val localScheduleUuid: String,
  /** Server `risk_assessments.id` — the row's own identity, distinct from [submissionId]. */
  val serverAssessmentId: String,
  val beneficiaryId: String,
  val visitId: String?,
  /** The idempotency key this assessment was created/retrieved against. */
  val submissionId: String,
  val ruleVersionId: String,
  /** ISO-8601 timestamp from the server, stored as-is (no local re-formatting). */
  val evaluatedAt: String,
  /** `"NORMAL"|"LOW"|"MEDIUM"|"HIGH"|"CRITICAL"` — see [org.armman.sakhi.data.visitform
   * .RiskAssessmentResponseData.overallRiskCategory]'s own doc for why this is a raw String. */
  val overallRiskCategory: String,
  val overallHighRiskFlag: Boolean,
  val hrDetectedFlag: Boolean,
  /** Local write time, not the server's [evaluatedAt] — lets a future "synced Xh ago" style
   * display distinguish "when the server graded this" from "when this device last heard about
   * it", same reasoning as every other `createdAtEpochMillis` column in this database. */
  val createdAtEpochMillis: Long,
)
