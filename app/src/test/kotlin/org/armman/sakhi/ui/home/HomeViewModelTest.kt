package org.armman.sakhi.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.dashboard.ActiveBeneficiaries
import org.armman.sakhi.data.dashboard.ActiveVisits
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FakeDynamicFormSyncScheduler
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  /** Draft store fake backed by a hot flow, so the badge/modal can be observed reacting to status
   * changes exactly as they would against Room. [failObserve] simulates a local read error. */
  private class FakeDynamicFormDraftRepository(
    records: List<FormUploadRecord> = emptyList(),
    var failObserve: Boolean = false,
  ) : DynamicFormDraftRepository {
    private val recordsFlow = MutableStateFlow(records)

    fun setRecords(records: List<FormUploadRecord>) {
      recordsFlow.value = records
    }

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

    override suspend fun getUploadRecords(): List<FormUploadRecord> = recordsFlow.value

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> =
      if (failObserve) flow { throw IOException("db read failed") } else recordsFlow
  }

  private lateinit var repository: FakeDashboardRepository
  private lateinit var draftRepository: FakeDynamicFormDraftRepository
  private lateinit var syncScheduler: FakeDynamicFormSyncScheduler

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeDashboardRepository()
    draftRepository = FakeDynamicFormDraftRepository()
    syncScheduler = FakeDynamicFormSyncScheduler()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = HomeViewModel(repository, draftRepository, syncScheduler)

  /** Keeps the WhileSubscribed StateFlows active for the duration of a test so their derived values
   * are computed (mirrors the screen collecting them). */
  private fun TestScope.observe(viewModel: HomeViewModel) {
    backgroundScope.launch(dispatcher) { viewModel.pendingUploadCount.collect {} }
    backgroundScope.launch(dispatcher) { viewModel.uploadModalState.collect {} }
  }

  private fun record(id: String, status: EnrollmentSyncStatus, createdAtEpochMillis: Long) =
    FormUploadRecord(
      localBeneficiaryId = id,
      formCode = "MOTHER_REGISTRATION",
      syncStatus = status,
      createdAtEpochMillis = createdAtEpochMillis,
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
    draftRepository.setRecords(
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
    draftRepository.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, viewModel.pendingUploadCount.value)

    // Connectivity returned and the worker uploaded it — no user action, count updates itself.
    draftRepository.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
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
    draftRepository.setRecords(
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
    draftRepository.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(EnrollmentSyncStatus.PENDING, viewModel.uploadModalState.value.records.single().syncStatus)

    draftRepository.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(EnrollmentSyncStatus.SYNCED, viewModel.uploadModalState.value.records.single().syncStatus)
  }

  @Test
  fun `onRetryUpload triggers an immediate sync attempt`() = runTest(dispatcher) {
    val viewModel = viewModel()
    observe(viewModel)

    viewModel.onRetryUpload()

    assertEquals(1, syncScheduler.syncNowCallCount)
  }

  @Test
  fun `onDismissUploadModal hides the modal`() = runTest(dispatcher) {
    draftRepository.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
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
      draftRepository.failObserve = true
      val viewModel = viewModel()
      observe(viewModel)

      viewModel.onDataUploadClicked()
      dispatcher.scheduler.advanceUntilIdle()

      assertTrue(viewModel.uploadModalState.value.isVisible)
      assertTrue(viewModel.uploadModalState.value.records.isEmpty())
      assertEquals(0, viewModel.pendingUploadCount.value)
      assertTrue(viewModel.uiState.value is HomeUiState.Success)
    }
}
