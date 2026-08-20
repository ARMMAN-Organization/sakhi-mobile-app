package org.armman.sakhi.data.referral

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_REFERRAL_FOLLOWUP_CACHE = "referral_pending_followup_cache"
private const val STATUS_PENDING_FOLLOWUP = "PENDING_FOLLOWUP"

/** Wire shape persisted to disk — [ReferralFollowUp.referralDate]/[ReferralFollowUp.followUpDueDate]
 * are `java.time.LocalDate`, so (same reasoning as [org.armman.sakhi.data.dashboard.RemoteDashboardRepository])
 * the cached shape keeps dates as plain strings and re-parses on read rather than caching the
 * domain type directly. */
private data class CachedReferralFollowUp(
  val referralId: String,
  val beneficiaryId: String,
  val beneficiaryName: String,
  val referralDate: String?,
  val followUpDueDate: String?,
  val daysRemaining: Int,
  val status: String,
)

/**
 * Real [ReferralRepository] backed by `GET /sakhi/{sakhiId}/referrals/pending-followup`. Same
 * fetch-then-cache-then-fallback resilience shape as the dashboard and pada repositories.
 */
@Singleton
class RemoteReferralRepository @Inject constructor(
  private val referralApi: ReferralApi,
  private val sessionStore: SessionStore,
  private val store: SecureKeyValueStore,
) : ReferralRepository {

  private val gson = Gson()
  private val mutex = Mutex()

  override suspend fun getPendingFollowUps(): List<ReferralFollowUp> = mutex.withLock {
    val fetched = fetchFollowUps()
    if (fetched != null) {
      // Persisted even when empty: a Sakhi with nothing pending today must not keep seeing a
      // stale list.
      store.putString(KEY_REFERRAL_FOLLOWUP_CACHE, gson.toJson(fetched.map { it.toCached() }))
      return fetched
    }
    readPersisted()?.map { it.toDomain() }
      ?: throw IllegalStateException("No referral follow-ups available online or cached")
  }

  private suspend fun fetchFollowUps(): List<ReferralFollowUp>? {
    val sakhiId = sessionStore.readSession()?.subjectId ?: return null
    return try {
      referralApi.getPendingFollowUps(sakhiId)
        .takeIf { it.isSuccessful }
        ?.body()
        ?.takeIf { it.success }
        ?.data
        ?.items
        ?.mapNotNull { it.toDomain() }
    } catch (e: Exception) {
      // Offline, timeout, malformed body — all fall back to whatever's cached.
      null
    }
  }

  private fun readPersisted(): List<CachedReferralFollowUp>? {
    val json = store.getString(KEY_REFERRAL_FOLLOWUP_CACHE) ?: return null
    return try {
      gson.fromJson(json, object : TypeToken<List<CachedReferralFollowUp>>() {}.type)
    } catch (e: JsonSyntaxException) {
      null
    }
  }

  /** Null only for an entry missing its referralId or beneficiaryId — nothing to key it by or
   * navigate to. Everything else degrades to a safe default. */
  private fun ReferralFollowUpDto.toDomain(): ReferralFollowUp? {
    val refId = referralId?.takeIf { it.isNotBlank() } ?: return null
    val benId = beneficiaryId?.takeIf { it.isNotBlank() } ?: return null
    return ReferralFollowUp(
      referralId = refId,
      beneficiaryId = benId,
      beneficiaryName = beneficiaryName?.trim()?.takeIf { it.isNotBlank() } ?: "Unnamed beneficiary",
      referralDate = referralDate.toLocalDateOrNull(),
      followUpDueDate = followUpDueDate.toLocalDateOrNull(),
      daysRemaining = daysRemaining ?: 0,
      status = status.toReferralFollowUpStatus(),
    )
  }

  private fun ReferralFollowUp.toCached() = CachedReferralFollowUp(
    referralId = referralId,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    referralDate = referralDate?.toString(),
    followUpDueDate = followUpDueDate?.toString(),
    daysRemaining = daysRemaining,
    status = status.name,
  )

  private fun CachedReferralFollowUp.toDomain() = ReferralFollowUp(
    referralId = referralId,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    referralDate = referralDate?.toLocalDateOrNull(),
    followUpDueDate = followUpDueDate?.toLocalDateOrNull(),
    daysRemaining = daysRemaining,
    status = status.toReferralFollowUpStatus(),
  )
}

private fun String?.toReferralFollowUpStatus(): ReferralFollowUpStatus =
  if (this.equals(STATUS_PENDING_FOLLOWUP, ignoreCase = true)) {
    ReferralFollowUpStatus.PENDING_FOLLOWUP
  } else {
    ReferralFollowUpStatus.UNKNOWN
  }

/** `"2026-08-10"` (date-only, per the confirmed sample) → [LocalDate], null for anything
 * unparseable rather than throwing. */
private fun String?.toLocalDateOrNull(): LocalDate? {
  val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
  return runCatching { LocalDate.parse(value) }.getOrNull()
}
