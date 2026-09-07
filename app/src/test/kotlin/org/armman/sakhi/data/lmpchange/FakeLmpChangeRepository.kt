package org.armman.sakhi.data.lmpchange

import java.io.File
import java.time.LocalDate

/** In-memory fake — records every [submitLmpChangeRequest] call for assertions and lets a test
 * configure [pendingBeneficiaryIds] to control [hasPendingLmpChangeRequest]'s answer,
 * [approvedRequestByBeneficiaryId] to control [approvedLmpChangeRequest]'s answer, or
 * [rejectedBeneficiaryIds] to control [hasRejectedLmpChangeRequest]'s answer — mirrors
 * [org.armman.sakhi.data.reopen.FakeReopenRepository]'s shape. */
class FakeLmpChangeRepository(
  var exceptionToThrow: LmpChangeSubmissionException? = null,
  var pendingBeneficiaryIds: Set<String> = emptySet(),
  var approvedRequestByBeneficiaryId: Map<String, LmpChangeRequestRowDto> = emptyMap(),
  var rejectedBeneficiaryIds: Set<String> = emptySet(),
) : LmpChangeRepository {

  data class RecordedRequest(
    val beneficiaryId: String,
    val newLmpDate: LocalDate,
    val sonographyImageAssetId: String?,
    val localRequestUuid: String,
  )

  /** Every [submitLmpChangeRequest] call, in call order. */
  val recordedRequests = mutableListOf<RecordedRequest>()

  override suspend fun submitLmpChangeRequest(
    beneficiaryId: String,
    newLmpDate: LocalDate,
    sonographyImageAssetId: String?,
    localRequestUuid: String,
  ) {
    recordedRequests += RecordedRequest(beneficiaryId, newLmpDate, sonographyImageAssetId, localRequestUuid)
    exceptionToThrow?.let { throw it }
  }

  override suspend fun hasPendingLmpChangeRequest(beneficiaryId: String): Boolean =
    beneficiaryId in pendingBeneficiaryIds

  override suspend fun approvedLmpChangeRequest(beneficiaryId: String): LmpChangeRequestRowDto? =
    approvedRequestByBeneficiaryId[beneficiaryId]

  override suspend fun hasRejectedLmpChangeRequest(beneficiaryId: String): Boolean =
    beneficiaryId in rejectedBeneficiaryIds

  /** Configurable -- Task 2 coverage sets this to a success/failure Result. Defaults to a
   * throwing failure so a test that forgets to configure it fails loudly. */
  var uploadSonographyImageResult: Result<String> =
    Result.failure(IllegalStateException("FakeLmpChangeRepository.uploadSonographyImageResult not configured"))

  val uploadedFiles = mutableListOf<File>()

  override suspend fun uploadSonographyImage(file: File): Result<String> {
    uploadedFiles += file
    return uploadSonographyImageResult
  }
}
