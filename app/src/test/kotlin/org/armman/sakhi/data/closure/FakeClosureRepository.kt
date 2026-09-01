package org.armman.sakhi.data.closure

/** In-memory fake — records every [submitClosure] call for assertions and lets a test configure
 * either a fixed [submittedClosureId] to return, or an [exceptionToThrow] to simulate a failed
 * `POST /closures` call, mirroring [org.armman.sakhi.data.forms.FakeFormSubmissionApi]'s shape. */
class FakeClosureRepository(
  var submittedClosureId: String = "closure-1",
  var exceptionToThrow: ClosureSubmissionException? = null,
) : ClosureRepository {

  data class RecordedClosure(
    val localClosureUuid: String,
    val beneficiaryId: String,
    val closureType: String,
    val closureReasonLookupValueId: String,
    val eventDate: String?,
    val closureDate: String,
    val submittedByUserId: String,
  )

  /** Every [submitClosure] call, in call order. */
  val recordedClosures = mutableListOf<RecordedClosure>()

  override suspend fun submitClosure(
    localClosureUuid: String,
    beneficiaryId: String,
    closureType: String,
    closureReasonLookupValueId: String,
    eventDate: String?,
    closureDate: String,
    submittedByUserId: String,
  ): String {
    recordedClosures += RecordedClosure(
      localClosureUuid = localClosureUuid,
      beneficiaryId = beneficiaryId,
      closureType = closureType,
      closureReasonLookupValueId = closureReasonLookupValueId,
      eventDate = eventDate,
      closureDate = closureDate,
      submittedByUserId = submittedByUserId,
    )
    exceptionToThrow?.let { throw it }
    return submittedClosureId
  }
}
