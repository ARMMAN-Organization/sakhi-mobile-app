package org.armman.sakhi.data.reopen

import org.armman.sakhi.data.enrollment.ApiErrorParser
import javax.inject.Inject
import javax.inject.Singleton

/** Everything that can stop [RemoteReopenRepository.submitReopenRequest] from completing — mirrors
 * [org.armman.sakhi.data.closure.ClosureSubmissionException]'s shape. */
sealed class ReopenSubmissionException(message: String) : Exception(message) {
  data class Failed(
    val httpCode: Int,
    val apiMessage: String?,
    val violations: List<String> = emptyList(),
  ) : ReopenSubmissionException("POST /reopen-requests failed: HTTP $httpCode — $apiMessage")
}

/** Real [ReopenRepository] backed by `POST`/`GET /api/v1/reopen-requests`. No offline
 * cache/fallback/retry-queue of its own — same rationale as
 * [org.armman.sakhi.data.closure.RemoteClosureRepository]: a reopen request is a write the Sakhi
 * makes rarely, from a screen that is online-only for this action (see
 * [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileScreen]'s Footer doc); it is not routed
 * through the ad-hoc-form sync queue like closure is. */
@Singleton
class RemoteReopenRepository @Inject constructor(
  private val reopenApi: ReopenApi,
) : ReopenRepository {

  override suspend fun submitReopenRequest(
    beneficiaryId: String,
    reason: ReopenRequestReason,
    localReopenRequestUuid: String,
  ) {
    val response = reopenApi.createReopenRequest(
      ReopenRequestDto(
        beneficiaryId = beneficiaryId,
        requestReason = reason.wireValue,
        localReopenRequestUuid = localReopenRequestUuid,
      ),
    )
    if (!response.isSuccessful) {
      val rawBody = response.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw ReopenSubmissionException.Failed(
        httpCode = response.code(),
        apiMessage = apiError.message?.takeIf { it != rawBody },
        violations = apiError.violations,
      )
    }
  }

  override suspend fun hasPendingReopenRequest(beneficiaryId: String): Boolean = try {
    reopenApi.getReopenRequests(beneficiaryId)
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

  override suspend fun hasApprovedReopenRequest(beneficiaryId: String): Boolean = try {
    reopenApi.getReopenRequests(beneficiaryId)
      .takeIf { it.isSuccessful }
      ?.body()
      ?.takeIf { it.success }
      ?.data
      ?.any { it.isApproved() }
      ?: false
  } catch (e: Exception) {
    // Offline, timeout, malformed body — best-effort, see the interface doc.
    false
  }

  override suspend fun hasRejectedReopenRequest(beneficiaryId: String): Boolean = try {
    reopenApi.getReopenRequests(beneficiaryId)
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
}
