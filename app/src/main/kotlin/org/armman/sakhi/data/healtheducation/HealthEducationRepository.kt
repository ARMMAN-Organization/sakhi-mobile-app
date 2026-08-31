package org.armman.sakhi.data.healtheducation

/**
 * Master-data boundary for "Learn More" health-education content (CR-M3-06). REWRITTEN 2026-08-28
 * against backend's confirmed real contract — see [HealthEducationApi]'s class doc for what
 * changed from this interface's original (fictional) versioned-bundle shape.
 */
interface HealthEducationRepository {

  /**
   * Resolved [HealthEducationTopic] per triggered condition, via `GET
   * /beneficiaries/{beneficiaryId}/risk` — [conditionCodes] are
   * [org.armman.sakhi.data.rules.RiskConditionIds.ANC]/`.INFANT` map KEYS (e.g. `"JAUNDICE"`),
   * not the UUID `riskConditionId` values (see [HealthEducationApi]'s DTO doc). A code with no
   * `isEducationTrigger`/`educationContent` in the response, or the whole call failing, resolves
   * to [HealthEducationDefaults.COMING_SOON_TOPIC] rather than being omitted — CR-M3-06
   * requirement #6/#10: a triggered condition must never silently show nothing, even offline.
   */
  suspend fun getEducationContentForBeneficiary(
    beneficiaryId: String,
    conditionCodes: Set<String>,
  ): Map<String, HealthEducationTopic>

  /**
   * The current "Learn More" placeholder topic (`GET /learn-more/topics/{topicCode}`), used for
   * the real-time, pre-submission "Learn More" hint (CR-M3-06 requirement #4) — there is no
   * beneficiary risk record to look up mid-form, before the visit is actually submitted, so this
   * calls the topic lookup directly rather than [getEducationContentForBeneficiary]. Always
   * resolves to something (falls back to [HealthEducationDefaults.COMING_SOON_TOPIC] on any
   * failure), never throws.
   */
  suspend fun getPlaceholderTopic(): HealthEducationTopic
}
