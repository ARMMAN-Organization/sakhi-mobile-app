package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.referral.CreateReferralOutcome
import org.armman.sakhi.data.referral.Referral
import org.armman.sakhi.data.referral.ReferralCapture
import org.armman.sakhi.data.referral.ReferralFollowUp
import org.armman.sakhi.data.referral.ReferralFollowUpResult
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.referral.ReferralEvidenceType
import java.time.LocalDate

/** Records every [createReferral] call it receives — [VisitFormSubmissionCoordinatorTest] asserts
 * against [requests] to verify [VisitFormSubmissionCoordinator.maybeCreateReferral]'s one-call,
 * server-authoritative-trigger behavior. [getPendingFollowUps]/[submitFollowUp]/
 * [convertToAccompanied] are unused by any test that needs this fake and just throw if ever
 * called. */
class FakeReferralRepository : ReferralRepository {

  data class CreateReferralCall(
    val visitId: String?,
    val beneficiaryId: String,
    val sourceSubmissionId: String?,
    val capture: ReferralCapture,
    val triggeringConditionIds: List<String>,
  )

  val requests = mutableListOf<CreateReferralCall>()

  var resultToReturn: Result<CreateReferralOutcome> =
    Result.failure(IllegalStateException("FakeReferralRepository.resultToReturn not configured"))

  var errorToThrow: Throwable? = null

  override suspend fun getPendingFollowUps(): List<ReferralFollowUp> =
    throw UnsupportedOperationException("not used by these tests")

  override suspend fun createReferral(
    visitId: String?,
    beneficiaryId: String,
    sourceSubmissionId: String?,
    capture: ReferralCapture,
    triggeringConditionIds: List<String>,
  ): Result<CreateReferralOutcome> {
    requests += CreateReferralCall(visitId, beneficiaryId, sourceSubmissionId, capture, triggeringConditionIds)
    errorToThrow?.let { throw it }
    return resultToReturn
  }

  data class SubmitFollowUpCall(
    val referralId: String,
    val visitedFacilityFlag: Boolean,
    val followupDate: LocalDate,
    val notVisitedReason: String?,
    val diagnosis: String?,
    val treatmentGiven: String?,
    val outcome: String?,
  )

  val submitFollowUpCalls = mutableListOf<SubmitFollowUpCall>()

  /** Configurable — [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinatorTest] reuses
   * this fake for its REFERRAL_FOLLOWUP_VISIT coverage, unlike every other test that leaves this
   * throwing (unused). Defaults to a throwing failure so a test that forgets to configure it
   * fails loudly rather than silently succeeding. */
  var submitFollowUpResult: Result<ReferralFollowUpResult> =
    Result.failure(IllegalStateException("FakeReferralRepository.submitFollowUpResult not configured"))

  override suspend fun submitFollowUp(
    referralId: String,
    visitedFacilityFlag: Boolean,
    followupDate: LocalDate,
    notVisitedReason: String?,
    diagnosis: String?,
    treatmentGiven: String?,
    outcome: String?,
  ): Result<ReferralFollowUpResult> {
    submitFollowUpCalls += SubmitFollowUpCall(
      referralId, visitedFacilityFlag, followupDate, notVisitedReason, diagnosis, treatmentGiven, outcome,
    )
    return submitFollowUpResult
  }

  override suspend fun convertToAccompanied(referralId: String): Result<Referral> =
    throw UnsupportedOperationException("not used by these tests")

  override suspend fun uploadEvidence(
    referralId: String,
    followupId: String?,
    evidenceType: ReferralEvidenceType,
    file: java.io.File,
    submissionId: String?,
  ): Result<String> = throw UnsupportedOperationException("not used by these tests")
}
