package org.armman.sakhi.ui.referral

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.referral.FacilityType
import org.armman.sakhi.data.referral.FakeReferralLinkDao
import org.armman.sakhi.data.referral.Referral
import org.armman.sakhi.data.referral.ReferralFollowUp
import org.armman.sakhi.data.referral.ReferralFollowUpOutcomeStatus
import org.armman.sakhi.data.referral.ReferralFollowUpResult
import org.armman.sakhi.data.referral.ReferralFollowUpSubmission
import org.armman.sakhi.data.referral.ReferralLinkEntity
import org.armman.sakhi.data.referral.ReferralRepository
import org.armman.sakhi.data.referral.ReferralStatus
import org.armman.sakhi.data.referral.ReferralType
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CR-Referral-01: covers [ReferralFollowUpViewModel.load]'s [ReferralFollowUpUiState
 * .canConvertToAccompanied] gating (status + window + current type — the three independent rules
 * documented on that field), plus [ReferralFollowUpViewModel.submit]/[convertToAccompanied]'s
 * success/failure state transitions. No test file existed for this ViewModel before this pass
 * (flagged as a gap in the CR-Referral-01 RTM).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferralFollowUpViewModelTest {

  /** Configurable fake — [FakeReferralRepository] in the `visitform` test package is scoped to
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinatorTest]'s `createReferral`-only
   * needs and throws for everything else, so this test gets its own covering [submitFollowUp]/
   * [convertToAccompanied] instead. */
  private class FakeReferralRepository : ReferralRepository {
    var submitFollowUpResult: Result<ReferralFollowUpResult> =
      Result.failure(IllegalStateException("not configured"))
    var convertToAccompaniedResult: Result<Referral> =
      Result.failure(IllegalStateException("not configured"))

    data class SubmitFollowUpCall(val referralId: String, val visitedFacilityFlag: Boolean)

    val submitFollowUpCalls = mutableListOf<SubmitFollowUpCall>()
    val convertToAccompaniedCalls = mutableListOf<String>()

    override suspend fun getPendingFollowUps(): List<ReferralFollowUp> =
      throw UnsupportedOperationException("not used by these tests")

    override suspend fun createReferral(
      visitId: String?,
      beneficiaryId: String,
      sourceSubmissionId: String?,
      capture: org.armman.sakhi.data.referral.ReferralCapture,
      triggeringConditionIds: List<String>,
    ): Result<org.armman.sakhi.data.referral.CreateReferralOutcome> =
      throw UnsupportedOperationException("not used by these tests")

    override suspend fun submitFollowUp(
      referralId: String,
      visitedFacilityFlag: Boolean,
      followupDate: LocalDate,
      notVisitedReason: String?,
      diagnosis: String?,
      treatmentGiven: String?,
      outcome: String?,
    ): Result<ReferralFollowUpResult> {
      submitFollowUpCalls += SubmitFollowUpCall(referralId, visitedFacilityFlag)
      return submitFollowUpResult
    }

    override suspend fun convertToAccompanied(referralId: String): Result<Referral> {
      convertToAccompaniedCalls += referralId
      return convertToAccompaniedResult
    }
  }

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var referralRepository: FakeReferralRepository
  private lateinit var referralLinkDao: FakeReferralLinkDao
  private lateinit var lookupRepository: FakeLookupRepository

  private val standardLookupId = "lookup-referral-standard"
  private val accompaniedLookupId = "lookup-referral-accompanied"

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    referralRepository = FakeReferralRepository()
    referralLinkDao = FakeReferralLinkDao()
    lookupRepository = FakeLookupRepository(
      mutableMapOf(
        "REFERRAL_TYPE" to listOf(
          LookupValue(id = standardLookupId, valueCode = "STANDARD", valueLabel = "Standard"),
          LookupValue(id = accompaniedLookupId, valueCode = "ACCOMPANIED", valueLabel = "Accompanied"),
        ),
      ),
    )
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun link(
    status: ReferralStatus = ReferralStatus.PENDING_FOLLOWUP,
    referralTypeLookupValueId: String = standardLookupId,
    validTill: String? = Instant.now().plus(3, ChronoUnit.DAYS).toString(),
  ) = ReferralLinkEntity(
    localScheduleUuid = "schedule-1",
    referralId = "referral-1",
    visitId = "server-visit-1",
    status = status.name,
    referralTypeLookupValueId = referralTypeLookupValueId,
    validTill = validTill,
    createdAtEpochMillis = 0L,
  )

  private fun buildViewModel() = ReferralFollowUpViewModel(
    referralRepository = referralRepository,
    referralLinkDao = referralLinkDao,
    lookupRepository = lookupRepository,
    savedStateHandle = SavedStateHandle(
      mapOf(
        ReferralFollowUpViewModel.NAV_ARG_LOCAL_SCHEDULE_UUID to "schedule-1",
        ReferralFollowUpViewModel.NAV_ARG_REFERRAL_ID to "referral-1",
      ),
    ),
  )

  @Test
  fun `no cached link at all surfaces as an error state`() {
    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.hasError)
    assertFalse(viewModel.uiState.value.isLoading)
  }

  @Test
  fun `a pending Standard referral within its window can convert`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link())

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.first()
    assertEquals(ReferralStatus.PENDING_FOLLOWUP, state.status)
    assertTrue(state.canConvertToAccompanied)
  }

  @Test
  fun `a referral already Accompanied cannot convert again`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link(referralTypeLookupValueId = accompaniedLookupId))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.first().canConvertToAccompanied)
  }

  @Test
  fun `a referral past its validTill window cannot convert`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link(validTill = Instant.now().minus(1, ChronoUnit.DAYS).toString()))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.first().canConvertToAccompanied)
  }

  @Test
  fun `a COMPLETED referral cannot convert, regardless of window or type`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link(status = ReferralStatus.COMPLETED))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.first()
    assertEquals(ReferralStatus.COMPLETED, state.status)
    assertFalse(state.canConvertToAccompanied)
  }

  @Test
  fun `a LAPSED referral cannot convert`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link(status = ReferralStatus.LAPSED))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.first().canConvertToAccompanied)
  }

  @Test
  fun `submit success updates the cached status and emits SubmittedSuccessfully`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link())
    referralRepository.submitFollowUpResult = Result.success(
      ReferralFollowUpResult(
        followUp = ReferralFollowUpSubmission(
          id = "followup-1",
          referralId = "referral-1",
          visitedFacilityFlag = true,
          notVisitedReason = null,
          diagnosis = "yyy",
          treatmentGiven = "ghgg",
          outcome = "vvgg",
          followupStatus = ReferralFollowUpOutcomeStatus.COMPLETED,
        ),
        referral = Referral(
          referralId = "referral-1",
          visitId = "server-visit-1",
          sourceSubmissionId = "submission-1",
          beneficiaryId = "beneficiary-1",
          referralTypeLookupValueId = standardLookupId,
          status = ReferralStatus.COMPLETED,
          facilityName = "Test PHC",
          facilityType = FacilityType.PHC,
          triggeringConditionIds = emptyList(),
          createdAt = null,
          validTill = null,
        ),
      ),
    )

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setVisitedFacility(true)
    viewModel.setFollowupDate(LocalDate.now())
    viewModel.submit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(ReferralFollowUpEvent.SubmittedSuccessfully, viewModel.events.first())
    assertFalse(viewModel.uiState.value.isSubmitting)
    assertEquals(
      ReferralStatus.COMPLETED.name,
      referralLinkDao.getByLocalScheduleUuid("schedule-1")?.status,
    )
  }

  @Test
  fun `submit failure surfaces the backend message and does not touch the cached status`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link())
    referralRepository.submitFollowUpResult =
      Result.failure(IllegalStateException("Cannot submit a follow-up for a referral with status COMPLETED."))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setVisitedFacility(true)
    viewModel.setFollowupDate(LocalDate.now())
    viewModel.submit()
    testDispatcher.scheduler.advanceUntilIdle()

    val event = viewModel.events.first()
    assertTrue(event is ReferralFollowUpEvent.SubmitFailed)
    assertEquals(
      "Cannot submit a follow-up for a referral with status COMPLETED.",
      (event as ReferralFollowUpEvent.SubmitFailed).message,
    )
    assertFalse(viewModel.uiState.value.isSubmitting)
    assertEquals(ReferralStatus.PENDING_FOLLOWUP.name, referralLinkDao.getByLocalScheduleUuid("schedule-1")?.status)
  }

  @Test
  fun `submit does nothing while visitedFacility or followupDate is unanswered`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link())
    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.submit() // neither field set yet
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(referralRepository.submitFollowUpCalls.isEmpty())
  }

  @Test
  fun `convertToAccompanied success updates the cached type and hides the action`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link())
    referralRepository.convertToAccompaniedResult = Result.success(
      Referral(
        referralId = "referral-1",
        visitId = "server-visit-1",
        sourceSubmissionId = "submission-1",
        beneficiaryId = "beneficiary-1",
        referralTypeLookupValueId = accompaniedLookupId,
        status = ReferralStatus.PENDING_FOLLOWUP,
        facilityName = "Test PHC",
        facilityType = FacilityType.PHC,
        triggeringConditionIds = emptyList(),
        createdAt = null,
        validTill = null,
      ),
    )

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.convertToAccompanied()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(ReferralFollowUpEvent.ConvertedSuccessfully, viewModel.events.first())
    assertFalse(viewModel.uiState.value.canConvertToAccompanied)
    assertEquals(accompaniedLookupId, referralLinkDao.getByLocalScheduleUuid("schedule-1")?.referralTypeLookupValueId)
  }

  @Test
  fun `convertToAccompanied 409 failure surfaces the message and keeps the action available`() = kotlinx.coroutines.test.runTest {
    referralLinkDao.upsert(link())
    referralRepository.convertToAccompaniedResult =
      Result.failure(IllegalStateException("Referral is already Accompanied"))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.convertToAccompanied()
    testDispatcher.scheduler.advanceUntilIdle()

    val event = viewModel.events.first()
    assertTrue(event is ReferralFollowUpEvent.ConvertFailed)
    assertFalse(viewModel.uiState.value.isConverting)
  }
}
