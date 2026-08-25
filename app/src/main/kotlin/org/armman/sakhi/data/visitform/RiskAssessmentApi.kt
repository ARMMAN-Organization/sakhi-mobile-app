package org.armman.sakhi.data.visitform

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Matches `risk-referral-service`'s `create-riskAssessment.dto.ts` exactly, per backend's
 * confirmed answer (2026-08-24, closing item 4 of `backend-prompt-risk-grading-ondevice.md`):
 * `POST /risk-assessments` is the SAME call `visit-form-service` already makes server-side today
 * (`triggerRiskAssessment` in `form.service.ts`) after a visit-linked submission — this app just
 * also calls it directly, on the same idempotency contract (a retried call with the same
 * [submissionId] returns the original assessment rather than re-evaluating).
 *
 * [answers] is the same raw `formData` map already sent to `POST /forms/:formCode/submissions`
 * (see [CreateSubmissionRequestDto.formData]) — the server re-evaluates authoritatively from the
 * raw values, not from anything this app computed on-device. No `conditionIds`/`isFirstInstance`
 * are sent — the server resolves those itself from its own DB, same as its existing internal
 * trigger.
 *
 * [ruleSetId] is the rule-SET id (e.g. [org.armman.sakhi.data.rules.RuleSetIds.RISK_ANC]), not a
 * published-version id — confirmed against backend's exact field name. [riskPhase] is one of
 * `"REGISTRATION"|"ANC"|"DELIVERY"|"PP"|"NN"|"INC"|"CCV"` per backend's enum — see
 * [VisitFormSubmissionCoordinator]'s `riskPhaseFor` for the formCode mapping, including why
 * `INFANT_VISIT` sends `"INC"` (confirmed 2026-08-24: it's `INC_VISIT` under its pre-rename name,
 * not a distinct phase).
 */
data class CreateRiskAssessmentRequestDto(
  val beneficiaryId: String,
  val visitId: String?,
  val submissionId: String,
  val ruleSetId: String,
  val riskPhase: String,
  val answers: Map<String, Any?>,
)

/** One entry of the response's `riskFlags` array — one per graded condition, not filtered to
 * abnormal-only (mirrors [org.armman.sakhi.data.rules.RiskConditionFinding], the on-device
 * equivalent, but this is the server's own authoritative post-submission grading). */
data class RiskAssessmentFlagDto(
  val id: String,
  val riskConditionId: String,
  val riskGradeLookupValueId: String,
  val observedValueJson: Map<String, Any?>? = null,
  val isReferralTrigger: Boolean = false,
  val isEducationTrigger: Boolean = false,
  val isHrVisitTrigger: Boolean = false,
)

data class RiskAssessmentResponseData(
  val id: String,
  val beneficiaryId: String,
  val visitId: String?,
  val submissionId: String,
  val ruleVersionId: String,
  val evaluatedAt: String,
  /** `"NORMAL"|"LOW"|"MEDIUM"|"HIGH"|"CRITICAL"` — note this is a different value set from the
   * on-device [org.armman.sakhi.data.rules.RiskCategory] (`NORMAL|LOW|HIGH|CRITICAL|UNKNOWN`, no
   * `MEDIUM`). Kept as a raw String rather than reusing that enum so a mismatched value never
   * silently maps to the wrong tier or throws — this server response is not currently displayed
   * anywhere, only stored (see [VisitFormSubmissionCoordinator]'s call site doc). */
  val overallRiskCategory: String,
  val overallHighRiskFlag: Boolean,
  val hrDetectedFlag: Boolean,
  val riskFlags: List<RiskAssessmentFlagDto> = emptyList(),
)

data class CreateRiskAssessmentResponseDto(
  val success: Boolean,
  val message: String?,
  val data: RiskAssessmentResponseData?,
)

/** Retrofit contract for `risk-referral-service`'s risk-assessment endpoint, routed through the
 * same API gateway/base URL as every other service, open to SAKHI
 * (`requireRoles('SAKHI', 'SUPERVISOR', 'MANAGER', 'ADMIN')`, confirmed by backend 2026-08-24).
 * Bearer token attached by [org.armman.sakhi.data.auth.AuthInterceptor], same as every other
 * authenticated API in the app. */
interface RiskAssessmentApi {
  @POST("risk-assessments")
  suspend fun createRiskAssessment(
    @Body request: CreateRiskAssessmentRequestDto,
  ): Response<CreateRiskAssessmentResponseDto>
}
