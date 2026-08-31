package org.armman.sakhi.data.healtheducation

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Real, confirmed contract (backend response to `docs/backend-requests/CR-M3-06-health-education-
 * content-api.md`, 2026-08-28) — supersedes this interface's original draft, which proposed a
 * versioned content-bundle endpoint that never existed. Two real endpoints:
 *
 *  - `GET /beneficiaries/{beneficiaryId}/risk` — added by backend specifically to unblock this CR.
 *    Returns every graded condition for the beneficiary with `isEducationTrigger` and (when true)
 *    an embedded `educationContent` topic — no separate content fetch needed for the common case.
 *  - `GET /learn-more/topics/{topicCode}` — the underlying `cms-content-service` "Learn More"
 *    lookup (`GET /learn-more/sections` and `.../sections/{sectionCode}/topics` also exist but
 *    aren't needed here: this app only ever needs one topic at a time, by code). Used as the
 *    real-time, pre-submission fallback (see [RemoteHealthEducationRepository.getPlaceholderTopic])
 *    since there is no beneficiary risk record to look up mid-form, before a visit is submitted.
 *
 * Both sit behind the same gateway/Bearer-token auth as [org.armman.sakhi.data.rules.RuleSetApi]/
 * [org.armman.sakhi.data.referral.ReferralApi] (confirmed).
 */
interface HealthEducationApi {

  @GET("beneficiaries/{beneficiaryId}/risk")
  suspend fun getBeneficiaryRisk(
    @Path("beneficiaryId") beneficiaryId: String,
  ): Response<BeneficiaryRiskEnvelopeDto>

  @GET("learn-more/topics/{topicCode}")
  suspend fun getLearnMoreTopic(
    @Path("topicCode") topicCode: String,
  ): Response<LearnMoreTopicEnvelopeDto>
}

/** One entry of `GET /beneficiaries/{beneficiaryId}/risk`'s response array. [conditionCode] is
 * the same code space as [org.armman.sakhi.data.rules.RiskConditionIds.ANC]/`.INFANT`'s map KEYS
 * (e.g. `"JAUNDICE"`) — NOT [org.armman.sakhi.data.rules.RiskConditionFinding.riskConditionId]
 * (the UUID); confirmed by backend's sample response. [educationContent] is null whenever
 * [isEducationTrigger] is false. */
data class BeneficiaryRiskConditionDto(
  val conditionCode: String,
  val isEducationTrigger: Boolean,
  val educationContent: LearnMoreTopicDto? = null,
)

data class BeneficiaryRiskEnvelopeDto(
  val success: Boolean,
  val message: String? = null,
  val data: List<BeneficiaryRiskConditionDto> = emptyList(),
)

data class LearnMoreTopicDto(
  val id: String? = null,
  val topicCode: String,
  val topicName: String,
  val mediaType: String,
  val contentUrl: String? = null,
  val sortOrder: Int = 0,
)

data class LearnMoreTopicEnvelopeDto(
  val success: Boolean,
  val message: String? = null,
  val data: LearnMoreTopicDto? = null,
)
