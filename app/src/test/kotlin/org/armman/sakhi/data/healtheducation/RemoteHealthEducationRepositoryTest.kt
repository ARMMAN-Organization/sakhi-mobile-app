package org.armman.sakhi.data.healtheducation

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * CR-M3-06 requirement #10 (offline rendering + placeholder fallback), plus a regression test for
 * the specific redundant-call question backend raised 2026-08-28: does
 * [RemoteHealthEducationRepository.getEducationContentForBeneficiary] call `GET
 * /learn-more/topics/{topicCode}` for a condition the risk-profile response already resolved?
 * (Answer, enforced below: no — [FakeHealthEducationApi.learnMoreTopicCalls] stays 0 whenever
 * every requested code comes back with `educationContent` populated.)
 */
class RemoteHealthEducationRepositoryTest {

  private fun repo(api: FakeHealthEducationApi) = RemoteHealthEducationRepository(api, FakeSecureKeyValueStore())

  private val realTopic = LearnMoreTopicDto(
    topicCode = "COMING_SOON",
    topicName = "Content coming soon",
    mediaType = "QNA_TEXT",
    contentUrl = null,
  )

  @Test
  fun `a condition already resolved in the risk response never triggers a learn-more topic call`() = runTest {
    val api = FakeHealthEducationApi(
      riskConditions = listOf(
        BeneficiaryRiskConditionDto("JAUNDICE", isEducationTrigger = true, educationContent = realTopic),
      ),
    )

    val result = repo(api).getEducationContentForBeneficiary("beneficiary-1", setOf("JAUNDICE"))

    assertEquals("COMING_SOON", result.getValue("JAUNDICE").topicCode)
    assertEquals(1, api.beneficiaryRiskCalls)
    assertEquals(0, api.learnMoreTopicCalls)
  }

  @Test
  fun `a condition absent from the risk response falls back to the placeholder topic`() = runTest {
    val api = FakeHealthEducationApi(
      riskConditions = listOf(
        BeneficiaryRiskConditionDto("JAUNDICE", isEducationTrigger = true, educationContent = realTopic),
      ),
      placeholderTopic = realTopic,
    )

    // ANEMIA was on-device-flagged but the risk-profile response doesn't have it yet — must not
    // silently drop it (requirement #6/#10: never a silent gap).
    val result = repo(api).getEducationContentForBeneficiary("beneficiary-1", setOf("JAUNDICE", "ANEMIA"))

    assertEquals("COMING_SOON", result.getValue("JAUNDICE").topicCode)
    assertEquals("COMING_SOON", result.getValue("ANEMIA").topicCode)
    assertEquals(1, api.learnMoreTopicCalls) // fetched once for the one unresolved code, not per-code
  }

  @Test
  fun `a condition with isEducationTrigger false is not resolved from the risk response`() = runTest {
    val api = FakeHealthEducationApi(
      riskConditions = listOf(
        BeneficiaryRiskConditionDto("HYPERTENSION", isEducationTrigger = false, educationContent = null),
      ),
      placeholderTopic = realTopic,
    )

    val result = repo(api).getEducationContentForBeneficiary("beneficiary-1", setOf("HYPERTENSION"))

    // Still resolves to the placeholder (requested because it was on-device-flagged), not to a
    // non-triggering entry the backend happened to also return for the same beneficiary.
    assertEquals("COMING_SOON", result.getValue("HYPERTENSION").topicCode)
  }

  @Test
  fun `offline (both calls fail) still resolves to the known real default, never a crash or gap`() = runTest {
    val api = FakeHealthEducationApi(
      riskConditions = emptyList(),
      beneficiaryRiskHttpCode = 500,
      learnMoreTopicHttpCode = 500,
    )

    val result = repo(api).getEducationContentForBeneficiary("beneficiary-1", setOf("JAUNDICE"))

    assertEquals(HealthEducationDefaults.COMING_SOON_TOPIC, result.getValue("JAUNDICE"))
  }

  @Test
  fun `empty conditionCodes short-circuits without calling the API at all`() = runTest {
    val api = FakeHealthEducationApi(riskConditions = emptyList())

    val result = repo(api).getEducationContentForBeneficiary("beneficiary-1", emptySet())

    assertTrue(result.isEmpty())
    assertEquals(0, api.beneficiaryRiskCalls)
  }

  @Test
  fun `getPlaceholderTopic caches after first success and does not re-fetch`() = runTest {
    val api = FakeHealthEducationApi(riskConditions = emptyList(), placeholderTopic = realTopic)
    val repository = repo(api)

    repository.getPlaceholderTopic()
    repository.getPlaceholderTopic()

    assertEquals(1, api.learnMoreTopicCalls)
  }

  @Test
  fun `getPlaceholderTopic falls back to a persisted value when the live call later fails`() = runTest {
    val api = FakeHealthEducationApi(riskConditions = emptyList(), placeholderTopic = realTopic)
    val store = FakeSecureKeyValueStore()
    val firstRepository = RemoteHealthEducationRepository(api, store)
    firstRepository.getPlaceholderTopic() // persists realTopic into `store`

    // A fresh repository instance (simulates a process restart) with no in-memory cache, and the
    // live call now failing — must read from `store`, not fall through to the hardcoded default.
    api.learnMoreTopicHttpCode = 500
    val secondRepository = RemoteHealthEducationRepository(api, store)
    val result = secondRepository.getPlaceholderTopic()

    assertEquals("COMING_SOON", result.topicCode)
  }

  @Test
  fun `getPlaceholderTopic falls back to the hardcoded default when nothing is cached and the live call fails`() = runTest {
    val api = FakeHealthEducationApi(riskConditions = emptyList(), learnMoreTopicHttpCode = 500)

    val result = repo(api).getPlaceholderTopic()

    assertEquals(HealthEducationDefaults.COMING_SOON_TOPIC, result)
  }

  /** Hand-written fake — implements [HealthEducationApi] directly, mirroring
   * [org.armman.sakhi.data.rules.RemoteRuleSetRepositoryTest]'s `FakeRuleSetApi` pattern. */
  private class FakeHealthEducationApi(
    val riskConditions: List<BeneficiaryRiskConditionDto>,
    val placeholderTopic: LearnMoreTopicDto? = null,
    var beneficiaryRiskHttpCode: Int = 200,
    var learnMoreTopicHttpCode: Int = 200,
  ) : HealthEducationApi {
    var beneficiaryRiskCalls = 0
    var learnMoreTopicCalls = 0

    override suspend fun getBeneficiaryRisk(beneficiaryId: String): Response<BeneficiaryRiskEnvelopeDto> {
      beneficiaryRiskCalls++
      if (beneficiaryRiskHttpCode != 200) {
        return Response.error(beneficiaryRiskHttpCode, "".toResponseBody(null))
      }
      return Response.success(BeneficiaryRiskEnvelopeDto(success = true, data = riskConditions))
    }

    override suspend fun getLearnMoreTopic(topicCode: String): Response<LearnMoreTopicEnvelopeDto> {
      learnMoreTopicCalls++
      if (learnMoreTopicHttpCode != 200) {
        return Response.error(learnMoreTopicHttpCode, "".toResponseBody(null))
      }
      return Response.success(LearnMoreTopicEnvelopeDto(success = true, data = placeholderTopic))
    }
  }
}
