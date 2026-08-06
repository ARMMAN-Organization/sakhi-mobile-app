package org.armman.sakhi.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.childregistration.FakeChildFormSyncScheduler
import org.armman.sakhi.data.dashboard.ActiveBeneficiaries
import org.armman.sakhi.data.dashboard.ActiveVisits
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.enrollment.FakeEnrollmentSyncScheduler
import org.armman.sakhi.data.schedule.FakeVisitScheduleSyncScheduler
import org.armman.sakhi.data.forms.FakeDynamicFormSyncScheduler
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.sync.ManualSyncTrigger
import org.armman.sakhi.data.sync.UploadRecordsSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  /** Controllable fake: returns [summary] or throws when [error] is set. */
  private class FakeDashboardRepository(
    var summary: DashboardSummary = defaultSummary(),
    var error: Exception? = null,
  ) : DashboardRepository {
    override suspend fun getSummary(): DashboardSummary {
      error?.let { throw it }
      return summary
    }

    companion object {
      fun defaultSummary(
        mothersTotal: Int = 21,
        infantsTotal: Int = 10,
      ) = DashboardSummary(
        sakhiName = "Test Sakhi",
        lastUploadedOn = LocalDate.of(2026, 4, 14),
        activeVisits = ActiveVisits(
          month = YearMonth.of(2026, 1),
          openCount = 12,
          endingCount = 2,
          pendingReferralCount = 5,
        ),
        activeBeneficiaries = ActiveBeneficiaries(
          mothersTotal = mothersTotal,
          mothersHighRisk = 0,
          infantsTotal = infantsTotal,
          infantsHighRisk = 0,
        ),
      )
    }
  }

  /**
   * Fake for the merged upload-records read model, backed by a hot flow so the badge/modal can be
   * observed reacting to status changes exactly as they would against Room. [failObserve] simulates
   * a local read error.
   *
   * Replaces the old `FakeDynamicFormDraftRepository`: Home no longer reads one queue's repository
   * directly, it reads [UploadRecordsSource], which merges every surfaced offline queue.
   */
  private class FakeUploadRecordsSource(
    records: List<FormUploadRecord> = emptyList(),
    var failObserve: Boolean = false,
  ) : UploadRecordsSource {
    private val recordsFlow = MutableStateFlow(records)

    fun setRecords(records: List<FormUploadRecord>) {
      recordsFlow.value = records
    }

    override fun observeAll(): Flow<List<FormUploadRecord>> =
      if (failObserve) flow { throw IOException("db read failed") } else recordsFlow
  }

  /**
   * Only the two duplicate-resolution methods matter here — Home reads the draft list through
   * [UploadRecordsSource], and uses this repository purely to record the Sakhi's answer.
   */
  private class FakeDraftRepository : DynamicFormDraftRepository {
    val confirmed = mutableListOf<Pair<String, String>>()
    val dismissed = mutableListOf<String>()

    override suspend fun saveDraft(
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      localSubmissionUuid: String,
      answers: FormAnswers,
      registrationDate: LocalDate,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun submitDraft(
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      localSubmissionUuid: String,
      answers: FormAnswers,
      registrationDate: LocalDate,
    ): DynamicFormSubmitResult = DynamicFormSubmitResult.Synced

    override suspend fun confirmNewPregnancy(
      localBeneficiaryId: String,
      existingBeneficiaryId: String,
    ): DynamicFormSubmitResult {
      confirmed += localBeneficiaryId to existingBeneficiaryId
      return DynamicFormSubmitResult.Synced
    }

    override suspend fun dismissNewPregnancyPrompt(localBeneficiaryId: String) {
      dismissed += localBeneficiaryId
    }

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = flowOf(emptyList())
  }

  private lateinit var repository: FakeDashboardRepository
  private lateinit var uploadRecordsSource: FakeUploadRecordsSource
  private lateinit var draftRepository: FakeDraftRepository
  private lateinit var dynamicScheduler: FakeDynamicFormSyncScheduler
  private lateinit var childScheduler: FakeChildFormSyncScheduler
  private lateinit var enrollmentScheduler: FakeEnrollmentSyncScheduler
  private lateinit var visitScheduleScheduler: FakeVisitScheduleSyncScheduler
  private lateinit var manualSyncTrigger: ManualSyncTrigger

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeDashboardRepository()
    uploadRecordsSource = FakeUploadRecordsSource()
    draftRepository = FakeDraftRepository()
    dynamicScheduler = FakeDynamicFormSyncScheduler()
    childScheduler = FakeChildFormSyncScheduler()
    enrollmentScheduler = FakeEnrollmentSyncScheduler()
    visitScheduleScheduler = FakeVisitScheduleSyncScheduler()
    // Real ManualSyncTrigger over fake schedulers: its whole job is the fan-out, so faking the
    // trigger itself would test nothing.
    manualSyncTrigger = ManualSyncTrigger(
      dynamicScheduler,
      childScheduler,
      enrollmentScheduler,
      visitScheduleScheduler,
    )
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() =
    HomeViewModel(repository, uploadRecordsSource, manualSyncTrigger, draftRepository)

  /** Keeps the WhileSubscribed StateFlows active for the duration of a test so their derived values
   * are computed (mirrors the screen collecting them). */
  private fun TestScope.observe(viewModel: HomeViewModel) {
    backgroundScope.launch(dispatcher) { viewModel.pendingUploadCount.collect {} }
    backgroundScope.launch(dispatcher) { viewModel.uploadModalState.collect {} }
  }

  private fun record(
    id: String,
    status: EnrollmentSyncStatus,
    createdAtEpochMillis: Long,
    pendingNewPregnancyBeneficiaryId: String? = null,
  ) =
    FormUploadRecord(
      localBeneficiaryId = id,
      formCode = "MOTHER_REGISTRATION",
      syncStatus = status,
      createdAtEpochMillis = createdAtEpochMillis,
      pendingNewPregnancyBeneficiaryId = pendingNewPregnancyBeneficiaryId,
    )

  @Test
  fun `initial state is Loading`() {
    assertEquals(HomeUiState.Loading, viewModel().uiState.value)
  }

  @Test
  fun `successful load exposes the summary`() = runTest(dispatcher) {
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(state is HomeUiState.Success)
    assertEquals("Test Sakhi", (state as HomeUiState.Success).summary.sakhiName)
  }

  @Test
  fun `repository failure results in Error state`() = runTest(dispatcher) {
    repository.error = IOException("network down")
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(HomeUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun `retry after error reloads and reaches Success`() = runTest(dispatcher) {
    repository.error = IOException("network down")
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(HomeUiState.Error, viewModel.uiState.value)

    repository.error = null
    viewModel.loadSummary()
    assertEquals(HomeUiState.Loading, viewModel.uiState.value)
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value is HomeUiState.Success)
  }

  // --- Data Upload badge count (live, from the draft store) ---------------------------------

  @Test
  fun `pendingUploadCount counts every draft that is not yet synced`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(
      listOf(
        record("local-1", EnrollmentSyncStatus.SYNCED, 1L),
        record("local-2", EnrollmentSyncStatus.SYNCED, 2L),
        record("local-3", EnrollmentSyncStatus.PENDING, 3L),
        record("local-4", EnrollmentSyncStatus.FAILED, 4L),
      ),
    )
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(2, viewModel.pendingUploadCount.value)
  }

  @Test
  fun `pendingUploadCount drops live as the sync worker marks drafts synced`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, viewModel.pendingUploadCount.value)

    // Connectivity returned and the worker uploaded it — no user action, count updates itself.
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, viewModel.pendingUploadCount.value)
  }

  // --- "Forms Uploaded" sync-status modal ---------------------------------------------------

  @Test
  fun `initial upload modal state is hidden and empty`() {
    val state = viewModel().uploadModalState.value
    assertFalse(state.isVisible)
    assertTrue(state.records.isEmpty())
  }

  @Test
  fun `onDataUploadClicked shows the modal with the live draft list`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(
      listOf(
        record("local-1", EnrollmentSyncStatus.SYNCED, 1L),
        record("local-2", EnrollmentSyncStatus.PENDING, 2L),
      ),
    )
    val viewModel = viewModel()
    observe(viewModel)

    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uploadModalState.value
    assertTrue(state.isVisible)
    assertEquals(2, state.records.size)
  }

  @Test
  fun `open modal reflects status changes live without reopening`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(EnrollmentSyncStatus.PENDING, viewModel.uploadModalState.value.records.single().syncStatus)

    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(EnrollmentSyncStatus.SYNCED, viewModel.uploadModalState.value.records.single().syncStatus)
  }

  @Test
  fun `Data Upload tap starts an upload on every offline queue`() = runTest(dispatcher) {
    val viewModel = viewModel()
    observe(viewModel)

    viewModel.onDataUploadClicked()

    // All three queues must be drained by the one manual trigger. Nothing else syncs any more, so a
    // queue this tap skips is a queue that can never reach the server.
    assertEquals(1, dynamicScheduler.syncNowCallCount)
    assertEquals(1, childScheduler.syncNowCallCount)
    assertEquals(1, enrollmentScheduler.syncNowCallCount)
  }

  @Test
  fun `Data Upload tap also opens the progress modal`() = runTest(dispatcher) {
    val viewModel = viewModel()
    observe(viewModel)

    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uploadModalState.value.isVisible)
  }

  @Test
  fun `repeat Data Upload taps each start a fresh attempt - this is the retry affordance`() = runTest(dispatcher) {
    val viewModel = viewModel()
    observe(viewModel)

    // The modal has no Retry button (per the Figma board); retrying a FAILED draft is simply
    // tapping the pill again. Duplicate work is de-duplicated downstream by
    // ExistingWorkPolicy.KEEP, so re-triggering is always safe.
    viewModel.onDataUploadClicked()
    viewModel.onDataUploadClicked()

    assertEquals(2, dynamicScheduler.syncNowCallCount)
    assertEquals(2, childScheduler.syncNowCallCount)
    assertEquals(2, enrollmentScheduler.syncNowCallCount)
  }

  @Test
  fun `onDismissUploadModal hides the modal`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onDismissUploadModal()
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uploadModalState.value.isVisible)
  }

  @Test
  fun `a draft read error fails closed to an empty modal and leaves the dashboard untouched`() =
    runTest(dispatcher) {
      uploadRecordsSource.failObserve = true
      val viewModel = viewModel()
      observe(viewModel)

      viewModel.onDataUploadClicked()
      dispatcher.scheduler.advanceUntilIdle()

      assertTrue(viewModel.uploadModalState.value.isVisible)
      assertTrue(viewModel.uploadModalState.value.records.isEmpty())
      assertEquals(0, viewModel.pendingUploadCount.value)
      assertTrue(viewModel.uiState.value is HomeUiState.Success)
    }

  // --- CR-033 duplicate review (SRS FR-S-2.5) ---------------------------------------------------

  private fun TestScope.observeDuplicateReview(viewModel: HomeViewModel) {
    backgroundScope.launch(dispatcher) { viewModel.duplicateReview.collect {} }
  }

  @Test
  fun `no duplicate review while nothing is awaiting an answer`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(
      listOf(
        record("a", EnrollmentSyncStatus.PENDING, 1L),
        // Rejected as a hard duplicate: nothing for the Sakhi to answer.
        record("b", EnrollmentSyncStatus.DUPLICATE_CONFLICT, 2L),
      ),
    )
    val vm = viewModel()
    observeDuplicateReview(vm)
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.duplicateReview.value)
  }

  @Test
  fun `a draft rejected during upload with a completed earlier pregnancy is offered for review`() =
    runTest(dispatcher) {
      uploadRecordsSource.setRecords(
        listOf(record("a", EnrollmentSyncStatus.DUPLICATE_CONFLICT, 5L, "earlier-case")),
      )
      val vm = viewModel()
      observeDuplicateReview(vm)
      dispatcher.scheduler.advanceUntilIdle()

      assertEquals(DuplicateReview("a", "earlier-case", 5L), vm.duplicateReview.value)
    }

  @Test
  fun `the oldest pending question is asked first, one at a time`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(
      listOf(
        record("newer", EnrollmentSyncStatus.DUPLICATE_CONFLICT, 900L, "case-2"),
        record("older", EnrollmentSyncStatus.DUPLICATE_CONFLICT, 100L, "case-1"),
      ),
    )
    val vm = viewModel()
    observeDuplicateReview(vm)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("older", vm.duplicateReview.value?.localBeneficiaryId)
  }

  @Test
  fun `confirming a review acknowledges that draft`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.onConfirmNewPregnancy(DuplicateReview("a", "earlier-case", 5L))
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf("a" to "earlier-case"), draftRepository.confirmed)
    assertTrue(draftRepository.dismissed.isEmpty())
  }

  @Test
  fun `declining a review clears the question without acknowledging anything`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.onDismissDuplicateReview(DuplicateReview("a", "earlier-case", 5L))
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf("a"), draftRepository.dismissed)
    assertTrue(draftRepository.confirmed.isEmpty())
  }
}
