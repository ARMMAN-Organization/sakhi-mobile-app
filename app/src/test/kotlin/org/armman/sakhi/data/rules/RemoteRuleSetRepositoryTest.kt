package org.armman.sakhi.data.rules

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * Covers the fetch/cache/fallback logic this class never had direct tests for before the
 * 2026-08-24 rewrite — the previous implementation's SCHEDULE path was calling an ADMIN-only
 * endpoint (permanent 403 for SAKHI) and its RISK path was calling an endpoint that silently
 * never returned `rulesJson`, and neither failure mode had a test that would have caught it.
 */
class RemoteRuleSetRepositoryTest {

  private val ruleSetId = "set-1"
  private val versionA = "version-a"
  private val versionB = "version-b"
  private val json = JsonParser.parseString("""{"nodes":[]}""").asJsonObject

  private fun repo(api: RuleSetApi) = RemoteRuleSetRepository(api, FakeSecureKeyValueStore())

  @Test
  fun `first fetch resolves version then fetches content`() = runTest {
    val api = FakeRuleSetApi(publishedVersionId = versionA, content = mapOf(versionA to contentDto(versionA)))
    val result = repo(api).getPublishedRuleSet(ruleSetId)

    assertEquals(1, api.publishedVersionCalls)
    assertEquals(1, api.contentCalls)
    assertEquals(versionA, result?.ruleVersionId)
  }

  @Test
  fun `unchanged published version skips the content call`() = runTest {
    val api = FakeRuleSetApi(publishedVersionId = versionA, content = mapOf(versionA to contentDto(versionA)))
    val repository = repo(api)

    repository.getPublishedRuleSet(ruleSetId)
    val second = repository.getPublishedRuleSet(ruleSetId)

    assertEquals(2, api.publishedVersionCalls)
    assertEquals(1, api.contentCalls) // not called again — version matched the cache
    assertEquals(versionA, second?.ruleVersionId)
  }

  @Test
  fun `a changed published version triggers a fresh content fetch`() = runTest {
    val api = FakeRuleSetApi(
      publishedVersionId = versionA,
      content = mapOf(versionA to contentDto(versionA), versionB to contentDto(versionB)),
    )
    val repository = repo(api)
    repository.getPublishedRuleSet(ruleSetId)

    api.publishedVersionId = versionB
    val second = repository.getPublishedRuleSet(ruleSetId)

    assertEquals(2, api.contentCalls)
    assertEquals(versionB, second?.ruleVersionId)
  }

  @Test
  fun `live failure falls back to last cached content`() = runTest {
    val api = FakeRuleSetApi(publishedVersionId = versionA, content = mapOf(versionA to contentDto(versionA)))
    val repository = repo(api)
    val first = repository.getPublishedRuleSet(ruleSetId)

    api.publishedVersionHttpCode = 500
    val second = repository.getPublishedRuleSet(ruleSetId)

    assertEquals(first, second)
  }

  @Test
  fun `never fetched successfully returns null, not a crash`() = runTest {
    val api = FakeRuleSetApi(publishedVersionId = versionA, content = emptyMap(), publishedVersionHttpCode = 404)
    val result = repo(api).getPublishedRuleSet(ruleSetId)
    assertNull(result)
  }

  @Test
  fun `prefetchRuleSets fills the cache for every returned id`() = runTest {
    val api = FakeRuleSetApi(publishedVersionId = versionA, content = emptyMap())
    api.batchResponse = listOf(
      BatchRuleContentItemDto("set-1", versionA, "v1", json, "PUBLISHED"),
      BatchRuleContentItemDto("set-2", versionB, "v1", json, "PUBLISHED"),
    )
    val repository = repo(api)

    repository.prefetchRuleSets(listOf("set-1", "set-2"))

    assertEquals(versionA, repository.getPublishedRuleSet("set-1")?.ruleVersionId)
    // set-2's cache is warm without any published-version/content call for it individually —
    // confirmed by publishedVersionCalls/contentCalls not incrementing for a plain cache read.
    val callsBefore = api.publishedVersionCalls
    repository.getPublishedRuleSet("set-2")
    // getPublishedRuleSet always re-resolves live; what matters is the cache existed to fall back
    // to. Force that by making the live path fail.
    api.publishedVersionHttpCode = 500
    val fallback = repository.getPublishedRuleSet("set-2")
    assertEquals(versionB, fallback?.ruleVersionId)
    assertEquals(callsBefore + 2, api.publishedVersionCalls)
  }

  @Test
  fun `an id missing from the batch response leaves its existing cache untouched`() = runTest {
    val api = FakeRuleSetApi(publishedVersionId = versionA, content = mapOf(versionA to contentDto(versionA)))
    val repository = repo(api)
    repository.getPublishedRuleSet("set-1") // warm the cache normally first

    // Batch omits "set-1" entirely — per the confirmed contract, this is not an error.
    api.batchResponse = emptyList()
    repository.prefetchRuleSets(listOf("set-1"))

    api.publishedVersionHttpCode = 500 // force fallback-to-cache to prove it's still there
    val result = repository.getPublishedRuleSet("set-1")
    assertEquals(versionA, result?.ruleVersionId)
  }

  private fun contentDto(versionId: String) =
    RuleVersionContentDto(versionId, ruleSetId, "v1", json, "PUBLISHED")

  /** Hand-written fake — implements [RuleSetApi] directly rather than mocking Retrofit. */
  private class FakeRuleSetApi(
    var publishedVersionId: String,
    val content: Map<String, RuleVersionContentDto>,
    var publishedVersionHttpCode: Int = 200,
  ) : RuleSetApi {
    var publishedVersionCalls = 0
    var contentCalls = 0
    var batchResponse: List<BatchRuleContentItemDto> = emptyList()

    override suspend fun getPublishedVersionId(setId: String): Response<PublishedVersionEnvelopeDto> {
      publishedVersionCalls++
      if (publishedVersionHttpCode != 200) {
        return Response.error(publishedVersionHttpCode, "".toResponseBody(null))
      }
      return Response.success(PublishedVersionEnvelopeDto(success = true, data = PublishedVersionDto(publishedVersionId)))
    }

    override suspend fun getRuleVersionContent(versionId: String): Response<RuleContentEnvelopeDto> {
      contentCalls++
      val dto = content[versionId] ?: return Response.success(RuleContentEnvelopeDto(success = true, data = null))
      return Response.success(RuleContentEnvelopeDto(success = true, data = dto))
    }

    override suspend fun getPublishedContentBatch(setIds: String): Response<BatchRuleContentEnvelopeDto> {
      return Response.success(BatchRuleContentEnvelopeDto(success = true, data = batchResponse))
    }
  }
}
