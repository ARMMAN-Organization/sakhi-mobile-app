package org.armman.sakhi.data.referral

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.lookup.LookupRepository
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val KEY_REFERRAL_FOLLOWUP_CACHE = "referral_pending_followup_cache"
private const val STATUS_PENDING_FOLLOWUP = "PENDING_FOLLOWUP"
private const val HTTP_CREATED = 201
private const val LOOKUP_CATEGORY_REFERRAL_TYPE = "REFERRAL_TYPE"

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

private const val EVIDENCE_MIME_TYPE = "image/jpeg"

/**
 * Real [ReferralRepository] backed by `GET /sakhi/{sakhiId}/referrals/pending-followup`,
 * `POST /referrals`, `POST /referrals/{id}/follow-up`, and `PATCH /referrals/{id}/convert`. Same
 * fetch-then-cache-then-fallback resilience shape as the dashboard and pada repositories for the
 * follow-up list; the other three calls have no offline/cache story of their own — they're only
 * ever invoked once a visit submission (or an explicit Sakhi action on an existing referral) has
 * already reached the server, so they're inherently online-only.
 */
@Singleton
class RemoteReferralRepository @Inject constructor(
  private val referralApi: ReferralApi,
  private val sessionStore: SessionStore,
  private val store: SecureKeyValueStore,
  private val lookupRepository: LookupRepository,
  /** Unauthenticated client for the S3 PUT hop — see [org.armman.sakhi.di.NetworkModule
   * .provideRawOkHttp]'s doc for why this must not carry this app's Bearer token. */
  @Named("rawHttpClient") private val rawHttpClient: OkHttpClient,
  /** Backs [getCachedReferralVisitName] — a pure local-cache read, no network call, no online/
   * offline distinction to make (unlike every other method here). */
  private val referralLinkDao: ReferralLinkDao,
) : ReferralRepository {

  private val gson = Gson()
  private val mutex = Mutex()

  override suspend fun getCachedReferralVisitName(referralId: String): String? =
    referralLinkDao.getByReferralId(referralId)?.referralVisitName?.takeIf { it.isNotBlank() }

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

  /** See [ReferralRepository.createReferral]'s doc. `201` → [CreateReferralOutcome.Created],
   * `200` → [CreateReferralOutcome.AlreadyExists] (backend's confirmed idempotent-return for a
   * duplicate `visitId`, issue #197) — both are normal [Result.success], never [Result.failure].
   * Any other non-2xx surfaces as [Result.failure] with the backend's own message via
   * [ApiErrorParser], same pattern every other write call in this app uses. */
  override suspend fun createReferral(
    visitId: String?,
    beneficiaryId: String,
    sourceSubmissionId: String?,
    capture: ReferralCapture,
    triggeringConditionIds: List<String>,
  ): Result<CreateReferralOutcome> = runCatching {
    val referralTypeLookupValueId = lookupRepository.findValue(LOOKUP_CATEGORY_REFERRAL_TYPE, capture.referralType.name)?.id
      ?: throw IllegalStateException("No $LOOKUP_CATEGORY_REFERRAL_TYPE lookup value for ${capture.referralType.name}")

    val request = CreateReferralRequestDto(
      beneficiaryId = beneficiaryId,
      visitId = visitId,
      sourceSubmissionId = sourceSubmissionId,
      referralTypeLookupValueId = referralTypeLookupValueId,
      referralDate = capture.referralDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
      status = STATUS_PENDING_FOLLOWUP,
      facilityType = capture.facilityType,
      facilityName = capture.facilityName,
      triggerConditionListJson = triggeringConditionIds,
    )
    val response = referralApi.createReferral(request)

    if (!response.isSuccessful) {
      val apiError = ApiErrorParser.parse(response.errorBody()?.string())
      throw IllegalStateException(apiError.message ?: "POST /referrals failed: HTTP ${response.code()}")
    }
    val data = response.body()?.data
      ?: throw IllegalStateException("POST /referrals succeeded but returned no referral data")
    val referral = data.toDomain()
    if (response.code() == HTTP_CREATED) {
      CreateReferralOutcome.Created(referral)
    } else {
      CreateReferralOutcome.AlreadyExists(referral)
    }
  }

  /** See [ReferralRepository.submitFollowUp]'s doc for the confirmed COMPLETED/INCOMPLETE
   * behavior — this function just relays the backend's response, it doesn't compute status
   * itself. */
  override suspend fun submitFollowUp(
    referralId: String,
    visitedFacilityFlag: Boolean,
    followupDate: LocalDate,
    notVisitedReason: String?,
    diagnosis: String?,
    treatmentGiven: String?,
    outcome: String?,
  ): Result<ReferralFollowUpResult> = runCatching {
    val request = SubmitReferralFollowUpRequestDto(
      visitedFacilityFlag = visitedFacilityFlag,
      followupDate = followupDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
      notVisitedReason = notVisitedReason,
      diagnosis = diagnosis,
      treatmentGiven = treatmentGiven,
      outcome = outcome,
    )
    val response = referralApi.submitFollowUp(referralId, request)
    if (!response.isSuccessful) {
      val apiError = ApiErrorParser.parse(response.errorBody()?.string())
      throw IllegalStateException(apiError.message ?: "POST /referrals/$referralId/follow-up failed: HTTP ${response.code()}")
    }
    val data = response.body()?.data
      ?: throw IllegalStateException("Follow-up submission succeeded but returned no data")
    val followUpDto = data.followup
      ?: throw IllegalStateException("Follow-up submission succeeded but returned no followup record")
    val referralDto = data.referral
      ?: throw IllegalStateException("Follow-up submission succeeded but returned no referral record")

    ReferralFollowUpResult(
      followUp = ReferralFollowUpSubmission(
        id = followUpDto.id.orEmpty(),
        referralId = followUpDto.referralId ?: referralId,
        visitedFacilityFlag = followUpDto.visitedFacilityFlag ?: visitedFacilityFlag,
        notVisitedReason = followUpDto.notVisitedReason,
        diagnosis = followUpDto.diagnosis,
        treatmentGiven = followUpDto.treatmentGiven,
        outcome = followUpDto.outcome,
        followupStatus = followUpDto.followupStatus.toFollowUpOutcomeStatus(),
      ),
      referral = referralDto.toDomain(),
    )
  }

  /** See [ReferralRepository.convertToAccompanied]'s doc — a `409` (already Accompanied) is a
   * real, expected outcome (confirmed live), surfaced as [Result.failure] since there's no
   * confirmed idempotent-return behavior for this endpoint the way create-referral has. */
  override suspend fun convertToAccompanied(referralId: String): Result<Referral> = runCatching {
    val response = referralApi.convertToAccompanied(referralId)
    if (!response.isSuccessful) {
      val apiError = ApiErrorParser.parse(response.errorBody()?.string())
      throw IllegalStateException(apiError.message ?: "PATCH /referrals/$referralId/convert failed: HTTP ${response.code()}")
    }
    val data = response.body()?.data
      ?: throw IllegalStateException("Convert succeeded but returned no referral data")
    data.toDomain()
  }

  /** See [ReferralRepository.uploadEvidence]'s doc — the real, backend-confirmed 3-step
   * presigned-URL flow (2026-08-31). [file] is read from disk on the caller's dispatcher (this is
   * invoked from [ReferralEvidenceSyncExecutor], never directly from a UI thread). */
  override suspend fun uploadEvidence(
    referralId: String,
    followupId: String?,
    evidenceType: ReferralEvidenceType,
    file: File,
    submissionId: String?,
  ): Result<String> = runCatching {
    val sizeBytes = file.length()

    // Step 1 — request a presigned upload URL.
    val uploadUrlResponse = referralApi.requestMediaUploadUrl(
      RequestMediaUploadUrlDto(
        assetType = evidenceType.name,
        mimeType = EVIDENCE_MIME_TYPE,
        sizeBytes = sizeBytes,
      ),
    )
    if (!uploadUrlResponse.isSuccessful) {
      val apiError = ApiErrorParser.parse(uploadUrlResponse.errorBody()?.string())
      throw IllegalStateException(apiError.message ?: "POST /media/upload-url failed: HTTP ${uploadUrlResponse.code()}")
    }
    val uploadUrlData = uploadUrlResponse.body()?.data
      ?: throw IllegalStateException("POST /media/upload-url succeeded but returned no data")
    val uploadUrl = uploadUrlData.uploadUrl
      ?: throw IllegalStateException("POST /media/upload-url succeeded but returned no uploadUrl")
    val s3Key = uploadUrlData.s3Key
      ?: throw IllegalStateException("POST /media/upload-url succeeded but returned no s3Key")

    // Step 2 — raw PUT of the file bytes straight to S3. Uses rawHttpClient (no Bearer token,
    // no Retrofit) since this URL is a third-party AWS host, not the API gateway.
    withContext(Dispatchers.IO) {
      val body = file.asRequestBody(EVIDENCE_MIME_TYPE.toMediaTypeOrNull())
      val putRequest = Request.Builder().url(uploadUrl).put(body).build()
      rawHttpClient.newCall(putRequest).execute().use { s3Response ->
        if (!s3Response.isSuccessful) {
          throw IllegalStateException("S3 upload failed: HTTP ${s3Response.code}")
        }
      }
    }

    // Step 3 — finalize: register the now-uploaded object as a real media asset, linked to this
    // referral/follow-up.
    val finalizeResponse = referralApi.finalizeMedia(
      FinalizeMediaRequestDto(
        assetType = evidenceType.name,
        s3Key = s3Key,
        expectedSizeBytes = sizeBytes,
        referralId = referralId,
        followupId = followupId,
        submissionId = submissionId,
      ),
    )
    if (!finalizeResponse.isSuccessful) {
      val apiError = ApiErrorParser.parse(finalizeResponse.errorBody()?.string())
      throw IllegalStateException(apiError.message ?: "POST /media failed: HTTP ${finalizeResponse.code()}")
    }
    val mediaData = finalizeResponse.body()?.data
      ?: throw IllegalStateException("POST /media succeeded but returned no data")
    mediaData.id ?: throw IllegalStateException("POST /media succeeded but returned no media id")
  }

  private fun ReferralDataDto.toDomain() = Referral(
    referralId = id.orEmpty(),
    visitId = visitId,
    sourceSubmissionId = sourceSubmissionId,
    beneficiaryId = beneficiaryId.orEmpty(),
    referralTypeLookupValueId = referralTypeLookupValueId.orEmpty(),
    status = status.toReferralStatus(),
    facilityName = facilityName.orEmpty(),
    facilityType = facilityType.toFacilityTypeOrDefault(FacilityType.OTHER),
    triggeringConditionIds = triggerConditionListJson.orEmpty(),
    createdAt = createdAt,
    validTill = validTill,
  )

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

private fun String?.toReferralStatus(): ReferralStatus =
  ReferralStatus.entries.firstOrNull { it != ReferralStatus.UNKNOWN && it.name.equals(this, ignoreCase = true) }
    ?: ReferralStatus.UNKNOWN

private fun String?.toFollowUpOutcomeStatus(): ReferralFollowUpOutcomeStatus =
  ReferralFollowUpOutcomeStatus.entries.firstOrNull {
    it != ReferralFollowUpOutcomeStatus.UNKNOWN && it.name.equals(this, ignoreCase = true)
  } ?: ReferralFollowUpOutcomeStatus.UNKNOWN

private fun String?.toFacilityTypeOrDefault(default: FacilityType): FacilityType =
  FacilityType.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: default

/** `"2026-08-10"` (date-only, per the confirmed sample) → [LocalDate], null for anything
 * unparseable rather than throwing. */
private fun String?.toLocalDateOrNull(): LocalDate? {
  val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
  return runCatching { LocalDate.parse(value) }.getOrNull()
}
