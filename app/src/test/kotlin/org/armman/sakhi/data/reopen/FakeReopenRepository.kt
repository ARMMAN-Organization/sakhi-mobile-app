package org.armman.sakhi.data.reopen

/** In-memory fake — records every [submitReopenRequest] call for assertions and lets a test
 * configure either an [exceptionToThrow] to simulate a failed `POST /reopen-requests` call, or
 * [pendingBeneficiaryIds] to control [hasPendingReopenRequest]'s answer. */
class FakeReopenRepository(
  var exceptionToThrow: ReopenSubmissionException? = null,
  var pendingBeneficiaryIds: Set<String> = emptySet(),
) : ReopenRepository {

  data class RecordedRequest(val beneficiaryId: String, val reason: ReopenRequestReason)

  /** Every [submitReopenRequest] call, in call order. */
  val recordedRequests = mutableListOf<RecordedRequest>()

  override suspend fun submitReopenRequest(beneficiaryId: String, reason: ReopenRequestReason) {
    recordedRequests += RecordedRequest(beneficiaryId, reason)
    exceptionToThrow?.let { throw it }
  }

  override suspend fun hasPendingReopenRequest(beneficiaryId: String): Boolean =
    beneficiaryId in pendingBeneficiaryIds
}
