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

  /** Configurable — [org.armman.sakhi.ui.adhocform.AdHocFormViewModelTest] sets this to cover
   * `prefillReferralVisitNameFromParent`. Defaults to null (not a throwing failure): unlike
   * [createReferral]/[submitFollowUp], a local-cache miss is this method's own normal, documented
   * outcome (see [ReferralRepository.getCachedReferralVisitName]'s doc), not something a test
   * forgetting to configure it should be caught doing. */
  var cachedReferralVisitName: String? = null

  override suspend fun getCachedReferralVisitName(referralId: String): String? = cachedReferralVisitName

  /** Configurable -- [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModelTest] sets this to
   * cover the in-visit Referral capture step's "RV{n+1}" auto-numbering. Defaults to 0 (a fresh
   * beneficiary with no prior referrals) so every other test using this fake keeps behaving
   * exactly as before this method existed. */
  var referralCountToReturn: Int = 0

  val countReferralsForBeneficiaryCalls = mutableListOf<String>()

  override suspend fun countReferralsForBeneficiary(beneficiaryId: String): Int {
    countReferralsForBeneficiaryCalls += beneficiaryId
    return referralCountToReturn
  }

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

  /** Configurable — [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModelTest]
   * covers Task 8's LAPSE/REFILL refresh via this fake. Defaults to success/no-op so every other
   * test using this fake (which never configures it) keeps behaving exactly as before this method
   * existed. */
  var refreshReferralStatusesResult: Result<Unit> = Result.success(Unit)

  val refreshReferralStatusesCalls = mutableListOf<String>()

  override suspend fun refreshReferralStatuses(beneficiaryId: String): Result<Unit> {
    refreshReferralStatusesCalls += beneficiaryId
    return refreshReferralStatusesResult
  }
}
