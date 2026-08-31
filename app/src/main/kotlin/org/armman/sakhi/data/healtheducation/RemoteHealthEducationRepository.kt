package org.armman.sakhi.data.healtheducation

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"
private const val KEY_PLACEHOLDER_TOPIC_CODE = "health_education_placeholder_topic_code"
private const val KEY_PLACEHOLDER_TOPIC_NAME = "health_education_placeholder_topic_name"
private const val KEY_PLACEHOLDER_MEDIA_TYPE = "health_education_placeholder_media_type"
private const val KEY_PLACEHOLDER_CONTENT_URL = "health_education_placeholder_content_url"

/**
 * Real [HealthEducationRepository] against backend's confirmed contract — see
 * [HealthEducationApi]'s class doc. Deliberately NOT shaped like
 * [org.armman.sakhi.data.rules.RemoteRuleSetRepository]'s publish/version/content-with-fallback
 * pattern this package's first draft mirrored: there is no versioned content bundle to sync, so
 * there is nothing to "prefetch" ahead of time — [getEducationContentForBeneficiary] is a live,
 * per-beneficiary lookup, and [getPlaceholderTopic] persists only the single seeded topic it
 * fetches, for offline resilience.
 */
@Singleton
class RemoteHealthEducationRepository @Inject constructor(
  private val api: HealthEducationApi,
  private val store: SecureKeyValueStore,
) : HealthEducationRepository {

  private val mutex = Mutex()
  private var cachedPlaceholderTopic: HealthEducationTopic? = null

  override suspend fun getEducationContentForBeneficiary(
    beneficiaryId: String,
    conditionCodes: Set<String>,
  ): Map<String, HealthEducationTopic> {
    if (conditionCodes.isEmpty()) return emptyMap()
    val fetched = try {
      val response = api.getBeneficiaryRisk(beneficiaryId)
      if (!response.isSuccessful) {
        Log.w(TAG, "RemoteHealthEducationRepository.getEducationContentForBeneficiary: HTTP ${response.code()}")
        null
      } else {
        response.body()?.takeIf { it.success }?.data
      }
    } catch (e: Exception) {
      Log.w(
        TAG,
        "RemoteHealthEducationRepository.getEducationContentForBeneficiary($beneficiaryId): " +
          "threw ${e::class.simpleName} — ${e.message}",
      )
      null
    }.orEmpty()

    val byCode = fetched
      .filter { it.conditionCode in conditionCodes && it.isEducationTrigger && it.educationContent != null }
      .associate { it.conditionCode to it.educationContent!!.toDomain() }

    // Every requested code not resolved by the live call (call failed entirely, condition absent
    // from the response, or the response just doesn't have education content for it yet) still
    // gets the placeholder — requirement #6/#10, same "never a silent gap" contract as the
    // deleted content-bundle repository enforced. Fetched at most once per call (not per missing
    // code) and only when actually needed — `by lazy {}` can't wrap a suspend call, so this is a
    // plain nullable computed eagerly but conditionally instead.
    val unresolvedCodes = conditionCodes - byCode.keys
    val placeholder = if (unresolvedCodes.isNotEmpty()) {
      runCatching { getPlaceholderTopic() }.getOrDefault(HealthEducationDefaults.COMING_SOON_TOPIC)
    } else {
      null
    }
    return conditionCodes.associateWith { code -> byCode[code] ?: placeholder!! }
  }

  override suspend fun getPlaceholderTopic(): HealthEducationTopic = mutex.withLock {
    cachedPlaceholderTopic?.let { return@withLock it }
    val persisted = readPersistedPlaceholder()
    val fetched = try {
      val response = api.getLearnMoreTopic(HealthEducationDefaults.COMING_SOON_TOPIC_CODE)
      if (!response.isSuccessful) {
        Log.w(TAG, "RemoteHealthEducationRepository.getPlaceholderTopic: HTTP ${response.code()}")
        null
      } else {
        response.body()?.takeIf { it.success }?.data?.toDomain()
      }
    } catch (e: Exception) {
      Log.w(TAG, "RemoteHealthEducationRepository.getPlaceholderTopic: threw ${e::class.simpleName} — ${e.message}")
      null
    }
    val resolved = fetched ?: persisted ?: HealthEducationDefaults.COMING_SOON_TOPIC
    if (fetched != null) persistPlaceholder(fetched)
    cachedPlaceholderTopic = resolved
    resolved
  }

  private fun LearnMoreTopicDto.toDomain(): HealthEducationTopic = HealthEducationTopic(
    topicCode = topicCode,
    topicName = topicName,
    mediaType = runCatching { HealthEducationMediaType.valueOf(mediaType) }.getOrDefault(HealthEducationMediaType.UNKNOWN),
    contentUrl = contentUrl,
  )

  private fun persistPlaceholder(topic: HealthEducationTopic) {
    store.putString(KEY_PLACEHOLDER_TOPIC_CODE, topic.topicCode)
    store.putString(KEY_PLACEHOLDER_TOPIC_NAME, topic.topicName)
    store.putString(KEY_PLACEHOLDER_MEDIA_TYPE, topic.mediaType.name)
    topic.contentUrl?.let { store.putString(KEY_PLACEHOLDER_CONTENT_URL, it) }
  }

  private fun readPersistedPlaceholder(): HealthEducationTopic? {
    val code = store.getString(KEY_PLACEHOLDER_TOPIC_CODE) ?: return null
    val name = store.getString(KEY_PLACEHOLDER_TOPIC_NAME) ?: return null
    val mediaType = store.getString(KEY_PLACEHOLDER_MEDIA_TYPE)
      ?.let { runCatching { HealthEducationMediaType.valueOf(it) }.getOrNull() }
      ?: HealthEducationMediaType.UNKNOWN
    return HealthEducationTopic(
      topicCode = code,
      topicName = name,
      mediaType = mediaType,
      contentUrl = store.getString(KEY_PLACEHOLDER_CONTENT_URL),
    )
  }
}
