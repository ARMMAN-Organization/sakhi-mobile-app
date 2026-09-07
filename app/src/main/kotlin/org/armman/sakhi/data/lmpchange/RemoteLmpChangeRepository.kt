package org.armman.sakhi.data.lmpchange

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.armman.sakhi.data.enrollment.ApiErrorParser
import org.armman.sakhi.data.referral.FinalizeMediaRequestDto
import org.armman.sakhi.data.referral.ReferralApi
import org.armman.sakhi.data.referral.RequestMediaUploadUrlDto
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** `POST /media/upload-url` and `POST /media`'s `assetType` value for an LMP correction's
 * sonography report photo -- backend-confirmed 2026-09-02 as already a valid `media-service`
 * `MediaAssetType` enum entry, no backend change needed. Not one of
 * [org.armman.sakhi.data.referral.ReferralEvidenceType]'s values (that enum is scoped to referral
 * evidence specifically), so this is a plain string constant rather than a shared enum member. */
private const val ASSET_TYPE_LMP_SONOGRAPHY_REPORT = "LMP_SONOGRAPHY_REPORT"
private const val UPLOAD_MIME_TYPE = "image/jpeg"


/** Everything that can stop [RemoteLmpChangeRepository.submitLmpChangeRequest] from completing —
 * mirrors [org.armman.sakhi.data.reopen.ReopenSubmissionException]'s shape. */
sealed class LmpChangeSubmissionException(message: String) : Exception(message) {
  data class Failed(
    val httpCode: Int,
    val apiMessage: String?,
    val violations: List<String> = emptyList(),
  ) : LmpChangeSubmissionException("POST /lmp-change-requests failed: HTTP $httpCode — $apiMessage")
}

/** Real [LmpChangeRepository] backed by `POST`/`GET /api/v1/lmp-change-requests` — mirrors
 * [org.armman.sakhi.data.reopen.RemoteReopenRepository] exactly, including its "no offline
 * cache/retry-queue of its own" rationale: an LMP correction is a rare, online-only write from a
 * screen the Sakhi is actively using, not routed through the ad-hoc-form sync queue. */
@Singleton
class RemoteLmpChangeRepository @Inject constructor(
  private val lmpChangeApi: LmpChangeApi,
  /** Task 2 -- the upload-url/finalize media endpoints are generic media-service routes, just
   * co-located on [ReferralApi] today (see [uploadSonographyImage]'s own doc for why this
   * dependency is reused rather than duplicated). */
  private val referralApi: ReferralApi,
  /** Unauthenticated client for the S3 PUT hop -- mirrors
   * [org.armman.sakhi.data.referral.RemoteReferralRepository]'s own rawHttpClient use and its
   * doc's reasoning (this app's Bearer token must never reach a third-party S3 host). */
  @Named("rawHttpClient") private val rawHttpClient: OkHttpClient,
) : LmpChangeRepository {

  override suspend fun submitLmpChangeRequest(
    beneficiaryId: String,
    newLmpDate: LocalDate,
    sonographyImageAssetId: String?,
    localRequestUuid: String,
  ) {
    val response = lmpChangeApi.createLmpChangeRequest(
      LmpChangeRequestDto(
        beneficiaryId = beneficiaryId,
        newLmpDate = newLmpDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
        sonographyImageAssetId = sonographyImageAssetId,
        localRequestUuid = localRequestUuid,
      ),
    )
    if (!response.isSuccessful) {
      val rawBody = response.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw LmpChangeSubmissionException.Failed(
        httpCode = response.code(),
        apiMessage = apiError.message?.takeIf { it != rawBody },
        violations = apiError.violations,
      )
    }
  }

  override suspend fun hasPendingLmpChangeRequest(beneficiaryId: String): Boolean = try {
    lmpChangeApi.getLmpChangeRequests(beneficiaryId)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      ?.any { it.isPending() }
      ?: false
  } catch (e: Exception) {
    // Offline, timeout, malformed body — best-effort, see the interface doc.
    false
  }

  override suspend fun approvedLmpChangeRequest(beneficiaryId: String): LmpChangeRequestRowDto? = try {
    lmpChangeApi.getLmpChangeRequests(beneficiaryId)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      ?.firstOrNull { it.isApproved() }
  } catch (e: Exception) {
    // Offline, timeout, malformed body — best-effort, see the interface doc.
    null
  }

  override suspend fun hasRejectedLmpChangeRequest(beneficiaryId: String): Boolean = try {
    lmpChangeApi.getLmpChangeRequests(beneficiaryId)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      ?.any { it.isRejected() }
      ?: false
  } catch (e: Exception) {
    // Offline, timeout, malformed body — best-effort, see the interface doc.
    false
  }

  /** See [LmpChangeRepository.uploadSonographyImage]'s doc. Same 3-step sequence as
   * [org.armman.sakhi.data.referral.RemoteReferralRepository.uploadEvidence] -- request a
   * presigned URL, PUT the raw bytes straight to S3 (no Bearer token, no Retrofit -- see
   * [rawHttpClient]'s doc), then finalize as a real media asset. [file] is read from disk on the
   * caller's dispatcher. */
  override suspend fun uploadSonographyImage(file: File): Result<String> = runCatching {
    val sizeBytes = file.length()

    val uploadUrlResponse = referralApi.requestMediaUploadUrl(
      RequestMediaUploadUrlDto(
        assetType = ASSET_TYPE_LMP_SONOGRAPHY_REPORT,
        mimeType = UPLOAD_MIME_TYPE,
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

    withContext(Dispatchers.IO) {
      val body = file.asRequestBody(UPLOAD_MIME_TYPE.toMediaTypeOrNull())
      val putRequest = Request.Builder().url(uploadUrl).put(body).build()
      rawHttpClient.newCall(putRequest).execute().use { s3Response ->
        if (!s3Response.isSuccessful) {
          throw IllegalStateException("S3 upload failed: HTTP ${s3Response.code}")
        }
      }
    }

    val finalizeResponse = referralApi.finalizeMedia(
      FinalizeMediaRequestDto(
        assetType = ASSET_TYPE_LMP_SONOGRAPHY_REPORT,
        s3Key = s3Key,
        expectedSizeBytes = sizeBytes,
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
}
