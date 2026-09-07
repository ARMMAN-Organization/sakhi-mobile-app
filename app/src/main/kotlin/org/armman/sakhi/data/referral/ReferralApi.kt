package org.armman.sakhi.data.referral

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** One entry of `GET /sakhi/{sakhiId}/referrals/pending-followup`'s `data.items` array. */
data class ReferralFollowUpDto(
  val referralId: String?,
  val beneficiaryId: String?,
  val beneficiaryName: String?,
  val referralDate: String?,
  val followUpDueDate: String?,
  val daysRemaining: Int?,
  val status: String?,
)

data class ReferralFollowUpListDataDto(
  val pendingFollowUpCount: Int?,
  val items: List<ReferralFollowUpDto>?,
)

data class ReferralFollowUpListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ReferralFollowUpListDataDto?,
)

/** `POST /referrals` request body — field names/casing match the live-confirmed contract exactly
 * (2026-08-27; corrected from this app's original, wrong assumptions — see [ReferralModels.kt]'s
 * doc comments for what changed). [status] is required, no server default, and must always be
 * `"PENDING_FOLLOWUP"` (confirmed: any other value would create a referral no existing endpoint
 * can act on). [triggerConditionListJson] is a real JSON array, not a JSON-encoded string, despite
 * its name (the `Json` suffix names the Postgres/Prisma column type, confirmed live). `validTill`
 * is deliberately never sent — confirmed a caller-supplied value is rejected outright with `400`,
 * since the server always computes it as `referralDate + 7 days`. */
data class CreateReferralRequestDto(
  val beneficiaryId: String,
  val visitId: String?,
  val sourceSubmissionId: String?,
  val referralTypeLookupValueId: String,
  val referralDate: String,
  val status: String,
  val facilityType: String,
  val facilityName: String,
  val triggerConditionListJson: List<String>,
)

/** `POST /referrals`'s `data` object, and also `PATCH /referrals/{id}/convert`'s `data` object —
 * same shape, confirmed live for both (the convert response is a strict subset of these fields;
 * extra fields simply come back null when a particular response omits them, handled the same way
 * every other DTO in this app treats an absent field). */
data class ReferralDataDto(
  val id: String?,
  val beneficiaryId: String?,
  val visitId: String?,
  val sourceSubmissionId: String?,
  val referralTypeLookupValueId: String?,
  val referralDate: String?,
  val triggerConditionListJson: List<String>?,
  val facilityType: String?,
  val facilityName: String?,
  val status: String?,
  val validTill: String?,
  val createdAt: String?,
  /** Task 8 — present once a Supervisor has decided a follow-up (REFILL or LAPSE); null before
   * that. Only ever populated on `GET /referrals` responses in practice (create/convert both run
   * before any Supervisor decision exists) — see [Referral.decidedByUserId]'s doc. */
  val decidedByUserId: String? = null,
  val decidedAt: String? = null,
  val decisionNotes: String? = null,
)

/** `GET /referrals?beneficiaryId=`'s `data` object — Task 8, backend-confirmed unblocked
 * 2026-08-31 (the endpoint previously had no beneficiary filter at all). */
data class ReferralListDataDto(
  val items: List<ReferralDataDto>?,
)

data class ReferralListResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ReferralListDataDto?,
)

data class CreateReferralResponseDto(
  val success: Boolean,
  val message: String?,
  val data: ReferralDataDto?,
)

/** `POST /referrals/{referralId}/follow-up` request body — field names match the live-confirmed
 * contract exactly (2026-08-27). [notVisitedReason] is free text (confirmed by an exact-echo
 * test against the live endpoint), not a lookup code. */
data class SubmitReferralFollowUpRequestDto(
  val visitedFacilityFlag: Boolean,
  val followupDate: String,
  val notVisitedReason: String?,
  val diagnosis: String?,
  val treatmentGiven: String?,
  val outcome: String?,
  /** Backend-confirmed 2026-08-31: accepted only at follow-up creation time (max 10, default
   * `[]`) — there is no route to attach media afterwards. This app always sends `[]` here since
   * its evidence queue uploads AFTER the follow-up succeeds (the real `followupId` is required to
   * finalize a media asset, and doesn't exist until this call returns) — see
   * [RemoteReferralRepository.uploadEvidence]'s doc. */
  val mediaAssetIds: List<String> = emptyList(),
)

data class ReferralFollowUpDataDto(
  val id: String?,
  val referralId: String?,
  val visitedFacilityFlag: Boolean?,
  val notVisitedReason: String?,
  val diagnosis: String?,
  val treatmentGiven: String?,
  val outcome: String?,
  val followupStatus: String?,
)

data class SubmitReferralFollowUpDataDto(
  val followup: ReferralFollowUpDataDto?,
  val referral: ReferralDataDto?,
)

data class SubmitReferralFollowUpResponseDto(
  val success: Boolean,
  val message: String?,
  val data: SubmitReferralFollowUpDataDto?,
)

/** `POST /media/upload-url` request body — backend-confirmed live 2026-08-31. [assetType] is
 * one of [ReferralEvidenceType]'s names (a subset of the service's 12-value `assetType` enum).
 * [mimeType] must be one of `image/jpeg`, `image/png`, `image/webp`, `application/pdf` — this app
 * only ever sends `image/jpeg` (live-camera-only capture). [sizeBytes] is capped server-side at
 * 25 MB (26214400 bytes). */
data class RequestMediaUploadUrlDto(
  val assetType: String,
  val mimeType: String,
  val sizeBytes: Long,
)

data class MediaUploadUrlDataDto(
  val uploadUrl: String?,
  val s3Key: String?,
  val expiresInSeconds: Int?,
  val maxSizeBytes: Long?,
)

data class MediaUploadUrlResponseDto(
  val success: Boolean,
  val message: String?,
  val data: MediaUploadUrlDataDto?,
)

/** `POST /media` (finalize) request body — backend-confirmed live 2026-08-31. [s3Key] must be
 * exactly what `POST /media/upload-url` returned. Do NOT send `mimeType`, `storageUri`,
 * `checksum`, or `uploadedAt` — those are server-derived (confirmed). [followupId] is required in
 * practice for this app's flow (finalize only ever runs after the parent follow-up's real id is
 * known — see [ReferralEvidenceMediaEntity.followupId]'s doc) even though the backend contract
 * marks it optional. */
data class FinalizeMediaRequestDto(
  val assetType: String,
  val s3Key: String,
  val expectedSizeBytes: Long,
  val referralId: String? = null,
  val followupId: String? = null,
  val beneficiaryId: String? = null,
  /** Links a media asset to an ad-hoc-form generic submission (`data.id` from
   * `POST /forms/{formCode}/submissions`) — backend-confirmed 2026-08-31 as one of the finalize
   * call's accepted optional link fields, alongside [referralId]/[followupId]/[beneficiaryId].
   * Used by Referral Follow-up's `case_paper_photo`/`further_investigation_photo` evidence,
   * captured via the ad-hoc form pipeline (see
   * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]) rather than the retired
   * bespoke screen's [followupId]-keyed flow. */
  val submissionId: String? = null,
)

/** `POST /media`'s response `data` object — backend-confirmed live 2026-08-31 as the real,
 * narrower shape actually returned (an earlier doc example on the backend's side showed extra
 * fields that aren't actually sent; this is authoritative). [sizeBytes] comes back as a STRING,
 * not a number — confirmed, not a typo. [id] is the `mediaAssetId` this app keeps locally as
 * [ReferralEvidenceMediaEntity.remoteMediaId]. */
data class MediaAssetDataDto(
  val id: String?,
  val assetType: String?,
  val storageUri: String?,
  val mimeType: String?,
  val sizeBytes: String?,
  val uploadedByUserId: String?,
  val uploadedAt: String?,
  val encryptedFlag: Boolean?,
  val createdAt: String?,
)

data class FinalizeMediaResponseDto(
  val success: Boolean,
  val message: String?,
  val data: MediaAssetDataDto?,
)

/** Retrofit contract for referrals, behind the same API gateway/base URL and Bearer token as
 * every other service ([org.armman.sakhi.data.auth.AuthInterceptor]). */
interface ReferralApi {
  @GET("sakhi/{sakhiId}/referrals/pending-followup")
  suspend fun getPendingFollowUps(@Path("sakhiId") sakhiId: String): Response<ReferralFollowUpListResponseDto>

  /** Returns `201` for a genuinely new referral, `200` with the existing referral for a duplicate
   * `visitId` (idempotent as of backend's #197 fix, confirmed live 2026-08-27) — callers
   * distinguish the two by HTTP status code; the body shape is identical either way. See
   * [RemoteReferralRepository.createReferral]. */
  @POST("referrals")
  suspend fun createReferral(@Body request: CreateReferralRequestDto): Response<CreateReferralResponseDto>

  @POST("referrals/{referralId}/follow-up")
  suspend fun submitFollowUp(
    @Path("referralId") referralId: String,
    @Body request: SubmitReferralFollowUpRequestDto,
  ): Response<SubmitReferralFollowUpResponseDto>

  /** No request body — confirmed live 2026-08-27. Returns `409` if the referral is already
   * Accompanied. */
  @PATCH("referrals/{referralId}/convert")
  suspend fun convertToAccompanied(@Path("referralId") referralId: String): Response<CreateReferralResponseDto>

  /**
   * Task 8 (LMP/Reopen/Referral/Audit task list) — backend-confirmed unblocked 2026-08-31: this
   * list endpoint now accepts an optional `beneficiaryId` filter (previously none existed at all).
   * Used to detect a Supervisor's follow-up decision (LAPSE via [ReferralDataDto.status], REFILL
   * via [ReferralDataDto.decidedByUserId]/[decidedAt]/[decisionNotes]) that this app was never
   * told about directly — see [RemoteReferralRepository.refreshReferralStatuses]'s doc.
   *
   * NOT yet confirmed: whether a SAKHI-role token is authorized to call this at all (the backend
   * update only confirmed the filter param, not the role check) — see the backend-ask doc's open
   * question. Treated as best-effort by every caller, same as the rest of this app's polls.
   */
  @GET("referrals")
  suspend fun getReferrals(@Query("beneficiaryId") beneficiaryId: String): Response<ReferralListResponseDto>

  /**
   * CR-Referral-02 — Step 1 of the real, backend-confirmed presigned-URL upload flow
   * (2026-08-31). Returns a short-lived (900s) S3 `uploadUrl` + `s3Key`; the raw file bytes are
   * PUT directly to `uploadUrl` (not through this Retrofit interface — see
   * [RemoteReferralRepository.uploadEvidence], which uses an unauthenticated OkHttp client for
   * that hop so this app's Bearer token is never sent to S3), then [finalizeMedia] registers it.
   */
  @POST("media/upload-url")
  suspend fun requestMediaUploadUrl(@Body request: RequestMediaUploadUrlDto): Response<MediaUploadUrlResponseDto>

  /** CR-Referral-02 — Step 3 of the real upload flow: registers the file already PUT to S3 as a
   * real media asset, optionally linked to a referral/follow-up. See [requestMediaUploadUrl]'s
   * doc for the full 3-step sequence. */
  @POST("media")
  suspend fun finalizeMedia(@Body request: FinalizeMediaRequestDto): Response<FinalizeMediaResponseDto>
}
