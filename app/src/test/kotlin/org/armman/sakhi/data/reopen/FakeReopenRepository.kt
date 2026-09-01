package org.armman.sakhi.data.reopen

/** In-memory fake — records every [submitReopenRequest] call for assertions and lets a test
 * configure either an [exceptionToThrow] to simulate a failed `POST /reopen-requests` call,
 * [pendingBeneficiaryIds] to control [hasPendingReopenRequest]'s answer, or
 * [approvedBeneficiaryIds] to control [hasApprovedReopenRequest]'s answer (CR-Closure-04). */
class FakeReopenRepository(
  var exceptionToThrow: ReopenSubmissionException? = null,
  var pendingBeneficiaryIds: Set<String> = emptySet(),
  var approvedBeneficiaryIds: Set<String> = emptySet(),
) : ReopenRepository {

  data class RecordedRequest(
    val beneficiaryId: String,
    val reason: ReopenRequestReason,
    val localReopenRequestUuid: String,
  )

  /** Every [submitReopenRequest] call, in call order. */
  val recordedRequests = mutableListOf<RecordedRequest>()

  override suspend fun submitReopenRequest(
    beneficiaryId: String,
    reason: ReopenRequestReason,
    localReopenRequestUuid: String,
  ) {
    recordedRequests += RecordedRequest(beneficiaryId, reason, localReopenRequestUuid)
    exceptionToThrow?.let { throw it }
  }

  override suspend fun hasPendingReopenRequest(beneficiaryId: String): Boolean =
    beneficiaryId in pendingBeneficiaryIds

  override suspend fun hasApprovedReopenRequest(beneficiaryId: String): Boolean =
    beneficiaryId in approvedBeneficiaryIds
}
