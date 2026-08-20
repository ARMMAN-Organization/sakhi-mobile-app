package org.armman.sakhi.ui.beneficiaryprofile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.delivery.FakeDeliverySessionDao
import org.armman.sakhi.data.delivery.RoomDeliverySessionRepository
import org.armman.sakhi.data.reopen.FakeReopenRepository
import org.armman.sakhi.data.reopen.ReopenRequestReason
import org.armman.sakhi.data.reopen.ReopenSubmissionException
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.schedule
import org.armman.sakhi.data.visitform.VisitContext
import org.armman.sakhi.data.visitform.VisitFormRepository
import org.armman.sakhi.data.beneficiaryprofile.VitalStat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryProfileViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeRepository(
    var error: Exception? = null,
  ) : BeneficiaryProfileRepository {
    override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
      error?.let { throw it }
      return when (id) {
        "mother" -> MOTHER
        "child" -> CHILD
        else -> throw NoSuchElementException("Unknown id: $id")
      }
    }
  }

  /**
   * The Visit Form is still backed by seeded data that only recognises its own ids, so a Sakhi's
   * own enrolment cannot open it (CR-022g). [knownBeneficiaries] models which ids it accepts.
   */
  private class FakeVisitFormRepository(
    val knownBeneficiaries: Set<String> = setOf("mother", "child"),
  ) : VisitFormRepository {
    override suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext =
      throw NoSuchElementException("Not used by these tests")

    override suspend fun canStartVisit(beneficiaryId: String): Boolean =
      beneficiaryId in knownBeneficiaries
  }

  private lateinit var repository: FakeRepository
  private lateinit var visitFormRepository: FakeVisitFormRepository
  private lateinit var reopenRepository: FakeReopenRepository
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var visitScheduleRepository: RoomVisitScheduleRepository
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: RoomDeliverySessionRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeRepository()
    visitFormRepository = FakeVisitFormRepository()
    reopenRepository = FakeReopenRepository()
    scheduleDao = FakeVisitScheduleDao()
    visitScheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(id: String?): BeneficiaryProfileViewModel {
    val args = if (id == null) emptyMap() else mapOf(BeneficiaryProfileViewModel.NAV_ARG_ID to id)
    val viewModel =
      BeneficiaryProfileViewModel(
        repository,
        visitFormRepository,
        reopenRepository,
        visitScheduleRepository,
        deliverySessionRepository,
        SavedStateHandle(args),
      )
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  private suspend fun seedServerBeneficiaryId(localBeneficiaryId: String, serverBeneficiaryId: String) {
    visitScheduleRepository.saveGenerated(
      listOf(
        schedule(
          "schedule-for-$localBeneficiaryId",
          localBeneficiaryId = localBeneficiaryId,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = serverBeneficiaryId,
        ),
      ),
    )
  }

  /**
   * CR-022g. Tapping Start Visit on a Sakhi's own enrolment used to land her on an error screen:
   * the Visit Form only recognises seeded ids, and hers is a generated UUID. The screen now shows
   * "coming soon" instead, which this flag drives.
   */
  @Test
  fun `a beneficiary the visit form does not recognise cannot start a visit`() {
    visitFormRepository = FakeVisitFormRepository(knownBeneficiaries = emptySet())

    val viewModel = createViewModel("mother")

    assertFalse(viewModel.uiState.value.canStartVisit)
    // The profile itself must still load — only the button is affected.
    assertNotNull(viewModel.uiState.value.profile)
    assertFalse(viewModel.uiState.value.hasError)
  }

  @Test
  fun `a beneficiary the visit form recognises can start a visit`() {
    val viewModel = createViewModel("mother")

    assertTrue(viewModel.uiState.value.canStartVisit)
  }

  /**
   * CR-042 (Delivery Event Session). Backed by [org.armman.sakhi.data.schedule.VisitScheduleRepository.hasScheduleOfType]
   * against [VisitCodeType.PP] — see [BeneficiaryProfileUiState.hasDeliveryRecorded]'s doc for why.
   */
  @Test
  fun `hasDeliveryRecorded is false for a mother with no PP schedule yet`() {
    val viewModel = createViewModel("mother")

    assertFalse(viewModel.uiState.value.hasDeliveryRecorded)
  }

  @Test
  fun `hasDeliveryRecorded is true once a PP schedule exists for the mother`() = runTest {
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-for-mother", localBeneficiaryId = "mother", visitCode = "PP1", visitType = VisitCodeType.PP)),
    )

    val viewModel = createViewModel("mother")

    assertTrue(viewModel.uiState.value.hasDeliveryRecorded)
  }

  /**
   * Delivery only applies to a mother's own journey (matches the Footer's own "only shown for
   * MOTHER" gating) — an infant profile must never report a delivery as recorded against it, even
   * if a PP-type row somehow existed under its id.
   */
  @Test
  fun `hasDeliveryRecorded is false for an infant profile even if a PP row exists under its id`() = runTest {
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-for-child", localBeneficiaryId = "child", visitCode = "PP1", visitType = VisitCodeType.PP)),
    )

    val viewModel = createViewModel("child")

    assertFalse(viewModel.uiState.value.hasDeliveryRecorded)
  }

  // --- DeliveryButtonState (CR-042 session-aware Delivery button) ---

  @Test
  fun `deliveryButtonState is NotApplicable for an infant profile`() {
    val viewModel = createViewModel("child")

    assertEquals(DeliveryButtonState.NotApplicable, viewModel.uiState.value.deliveryButtonState)
  }

  @Test
  fun `deliveryButtonState is NotStarted for a mother with no delivery session at all`() {
    val viewModel = createViewModel("mother")

    assertEquals(DeliveryButtonState.NotStarted, viewModel.uiState.value.deliveryButtonState)
  }

  @Test
  fun `deliveryButtonState is ChildRegistrationPending when the active session is at that step`() = runTest {
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-for-mother", localBeneficiaryId = "mother", visitCode = "PP1", visitType = VisitCodeType.PP)),
    )
    deliverySessionRepository.save(deliverySession("mother", DeliverySessionStep.CHILD_REGISTRATION))

    val viewModel = createViewModel("mother")

    assertEquals(
      DeliveryButtonState.ChildRegistrationPending("session-for-mother"),
      viewModel.uiState.value.deliveryButtonState,
    )
  }

  @Test
  fun `deliveryButtonState resolves to ResumeVisit PP1 when the active session is at that step`() = runTest {
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-schedule", localBeneficiaryId = "mother", visitCode = "PP1", visitType = VisitCodeType.PP, sequenceNo = 1)),
    )
    deliverySessionRepository.save(deliverySession("mother", DeliverySessionStep.PP1))

    val viewModel = createViewModel("mother")

    val state = viewModel.uiState.value.deliveryButtonState
    assertTrue(state is DeliveryButtonState.ResumeVisit)
    assertEquals("pp1-schedule", (state as DeliveryButtonState.ResumeVisit).localScheduleUuid)
    assertEquals("PP1", state.label)
  }

  /**
   * A session parked at PP1 whose PP1 row has already been COMPLETED (e.g. the Sakhi submitted it
   * through the visit tracker directly, bypassing this button) has nothing left to resume —
   * [BeneficiaryProfileViewModel.resolveResumeVisit] must not offer a finished visit back.
   */
  @Test
  fun `deliveryButtonState falls back to Completed when the PP1 row is already completed`() = runTest {
    visitScheduleRepository.saveGenerated(
      listOf(
        schedule(
          "pp1-schedule",
          localBeneficiaryId = "mother",
          visitCode = "PP1",
          visitType = VisitCodeType.PP,
          sequenceNo = 1,
          status = VisitScheduleStatus.COMPLETED,
        ),
      ),
    )
    deliverySessionRepository.save(deliverySession("mother", DeliverySessionStep.PP1))

    val viewModel = createViewModel("mother")

    assertEquals(DeliveryButtonState.Completed, viewModel.uiState.value.deliveryButtonState)
  }

  @Test
  fun `deliveryButtonState is Completed once delivery is recorded and no session remains active`() = runTest {
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-for-mother", localBeneficiaryId = "mother", visitCode = "PP1", visitType = VisitCodeType.PP)),
    )
    // No DeliverySessionEntity saved at all — mirrors a session that reached DONE (excluded by
    // getActiveForBeneficiary) or one from before this session-aware button existed.

    val viewModel = createViewModel("mother")

    assertEquals(DeliveryButtonState.Completed, viewModel.uiState.value.deliveryButtonState)
  }

  private fun deliverySession(localBeneficiaryId: String, step: DeliverySessionStep) = DeliverySessionEntity(
    localSessionUuid = "session-for-$localBeneficiaryId",
    localBeneficiaryId = localBeneficiaryId,
    step = step,
    deliverySubmissionLocalUuid = "submission-1",
    createdAtEpochMillis = 1_754_265_600_000L,
    updatedAtEpochMillis = 1_754_265_600_000L,
  )

  @Test
  fun `loads the profile for the given id`() {
    val viewModel = createViewModel("mother")
    val state = viewModel.uiState.value

    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals("Aishwarya Pawar", state.profile?.name)
  }

  @Test
  fun `mother variant exposes lmp and edd but not dob or weight`() {
    val state = createViewModel("mother").uiState.value.profile

    assertEquals(BeneficiaryType.MOTHER, state?.type)
    assertTrue(!state?.lmp.isNullOrBlank())
    assertTrue(!state?.edd.isNullOrBlank())
    assertNull(state?.dob)
    assertNull(state?.weight)
  }

  @Test
  fun `child variant exposes dob and weight but not lmp or edd`() {
    val state = createViewModel("child").uiState.value.profile

    assertEquals(BeneficiaryType.INFANT, state?.type)
    assertTrue(!state?.dob.isNullOrBlank())
    assertTrue(!state?.weight.isNullOrBlank())
    assertNull(state?.lmp)
    assertNull(state?.edd)
  }

  @Test
  fun `repository failure sets error state without leaking profile`() {
    repository.error = IOException("offline")
    val state = createViewModel("mother").uiState.value

    assertFalse(state.isLoading)
    assertTrue(state.hasError)
    assertNull(state.profile)
  }

  @Test
  fun `unknown id sets error state`() {
    val state = createViewModel("ghost").uiState.value
    assertTrue(state.hasError)
  }

  @Test
  fun `missing id argument sets error state`() {
    val state = createViewModel(null).uiState.value
    assertTrue(state.hasError)
  }

  @Test
  fun `retry after error loads successfully`() {
    repository.error = IOException("offline")
    val viewModel = createViewModel("mother")
    assertTrue(viewModel.uiState.value.hasError)

    repository.error = null
    viewModel.loadProfile()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertFalse(state.hasError)
    assertEquals("Aishwarya Pawar", state.profile?.name)
  }

  @Test
  fun `submitReopenRequest calls ReopenRepository with the resolved server beneficiary id and updates state`() = runTest {
    seedServerBeneficiaryId("mother", "server-mother-1")
    val viewModel = createViewModel("mother")

    viewModel.submitReopenRequest(ReopenRequestReason.MIGRATION_RETURNED)
    dispatcher.scheduler.advanceUntilIdle()

    val recorded = reopenRepository.recordedRequests.single()
    assertEquals("server-mother-1", recorded.beneficiaryId)
    assertEquals(ReopenRequestReason.MIGRATION_RETURNED, recorded.reason)
    val state = viewModel.uiState.value
    assertFalse(state.isSubmittingReopen)
    assertTrue(state.hasPendingReopenRequest)
  }

  @Test
  fun `submitReopenRequest failure surfaces a ReopenFailed event and clears isSubmittingReopen`() = runTest {
    seedServerBeneficiaryId("mother", "server-mother-1")
    reopenRepository.exceptionToThrow = ReopenSubmissionException.Failed(httpCode = 500, apiMessage = "boom")
    val viewModel = createViewModel("mother")

    viewModel.submitReopenRequest(ReopenRequestReason.OTHER)
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isSubmittingReopen)
    assertFalse(viewModel.uiState.value.hasPendingReopenRequest)
  }

  private companion object {
    val MOTHER = BeneficiaryProfile(
      id = "mother",
      name = "Aishwarya Pawar",
      type = BeneficiaryType.MOTHER,
      ageLabel = "25",
      village = "Rampur",
      pada = "Chausa",
      husbandName = "Akash Sharma",
      mobileNumber = "987563421",
      status = BeneficiaryStatus.ACTIVE,
      riskLevel = RiskLevel.HIGH,
      lmp = "1 Dec 2025",
      edd = "1 Sep 2026",
      diagnoses = listOf("Sickle Cell", "Chronic Diabetes"),
      lastVisitStats = listOf(VitalStat("9 (12)", "Low Hb.", abnormal = true)),
    )

    val CHILD = BeneficiaryProfile(
      id = "child",
      name = "Baby of Aishwarya",
      type = BeneficiaryType.INFANT,
      ageLabel = "2 mo",
      village = "Rampur",
      pada = "Chausa",
      husbandName = "Akash Sharma",
      mobileNumber = "987563421",
      status = BeneficiaryStatus.ACTIVE,
      riskLevel = RiskLevel.MODERATE,
      dob = "10 Nov 2025",
      weight = "2.1 Kg",
      diagnoses = listOf("Low Birth Weight"),
      lastVisitStats = listOf(VitalStat("2.1 (3.2)", "Low Weight", abnormal = true)),
    )
  }
}
