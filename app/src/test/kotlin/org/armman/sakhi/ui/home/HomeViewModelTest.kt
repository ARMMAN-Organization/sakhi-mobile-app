package org.armman.sakhi.ui.home

import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.toList
import org.armman.sakhi.data.childregistration.FakeChildFormSyncScheduler
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.data.dashboard.NoDashboardCacheAvailableException
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.enrollment.FakeEnrollmentSyncScheduler
import org.armman.sakhi.data.schedule.FakeVisitScheduleSyncScheduler
import org.armman.sakhi.data.visitform.FakeVisitFormSyncScheduler
import org.armman.sakhi.data.adhocform.FakeAdHocFormSyncScheduler
import org.armman.sakhi.data.forms.FakeDynamicFormSyncScheduler
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.EditableSubmissionInfo
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.notification.FakeNotificationRepository
import org.armman.sakhi.data.sync.ManualSyncTrigger
import org.armman.sakhi.data.sync.UploadRecordsSource
import org.armman.sakhi.data.visitform.FakeReferralRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  /** Controllable fake: returns [summary] or throws when [error] is set. */
  private class FakeDashboardRepository(
    var summary: DashboardSummary = defaultSummary(),
    var error: Exception? = null,
  ) : DashboardRepository {
    var getSummaryCallCount = 0

    override suspend fun getSummary(): DashboardSummary {
      getSummaryCallCount++
      error?.let { throw it }
      return summary
    }

    companion object {
      // M3: DashboardSummary's shape changed to match the real dashboard API (percentages +
      // due/overdue/referral counts, no high-risk-count breakdown) — fixture updated to match,
      // no behavioral test below depended on the removed fields.
      fun defaultSummary() = DashboardSummary(
        sakhiName = "Test Sakhi",
        lastSyncedAt = null,
        totalActiveBeneficiaries = 96,
        activeMothersCount = 42,
        activeChildrenCount = 54,
        activeMothersHighRiskCount = 5,
        activeChildrenHighRiskCount = 2,
        activeMothersPercent = 43.75,
        activeChildrenPercent = 56.25,
        accompaniedReferralsCount = 7,
        pendingFollowUpsCount = 3,
        dueVisitsCount = 15,
        overdueVisitsCount = 4,
        endingSoonVisitsCount = 3,
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

  /** Controllable fake, defaulting to online so every pre-existing test below (written before the
   * offline guard existed) keeps exercising the online path unchanged. */
  private class FakeConnectivityChecker(var online: Boolean = true) : ConnectivityChecker {
    override fun isOnline(): Boolean = online
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

    override suspend fun getEditableSubmission(remoteBeneficiaryId: String): EditableSubmissionInfo? = null

    override suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>) = Unit

    override suspend fun getRemoteBeneficiaryId(localBeneficiaryId: String): String? = null
  }

  private lateinit var repository: FakeDashboardRepository
  private lateinit var uploadRecordsSource: FakeUploadRecordsSource
  private lateinit var draftRepository: FakeDraftRepository
  private lateinit var dynamicScheduler: FakeDynamicFormSyncScheduler
  private lateinit var childScheduler: FakeChildFormSyncScheduler
  private lateinit var enrollmentScheduler: FakeEnrollmentSyncScheduler
  private lateinit var visitScheduleScheduler: FakeVisitScheduleSyncScheduler
  private lateinit var visitFormScheduler: FakeVisitFormSyncScheduler
  private lateinit var adHocFormScheduler: FakeAdHocFormSyncScheduler
  private lateinit var manualSyncTrigger: ManualSyncTrigger
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var notificationRepository: FakeNotificationRepository
  private lateinit var referralRepository: FakeReferralRepository

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
    visitFormScheduler = FakeVisitFormSyncScheduler()
    adHocFormScheduler = FakeAdHocFormSyncScheduler()
    // Real ManualSyncTrigger over fake schedulers: its whole job is the fan-out, so faking the
    // trigger itself would test nothing.
    manualSyncTrigger = ManualSyncTrigger(
      dynamicScheduler,
      childScheduler,
      enrollmentScheduler,
      visitScheduleScheduler,
      visitFormScheduler,
      adHocFormScheduler,
      org.armman.sakhi.data.referral.FakeReferralEvidenceSyncScheduler(),
    )
    connectivityChecker = FakeConnectivityChecker()
    notificationRepository = FakeNotificationRepository()
    referralRepository = FakeReferralRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() =
    HomeViewModel(
      repository,
      uploadRecordsSource,
      manualSyncTrigger,
      draftRepository,
      connectivityChecker,
      notificationRepository,
      referralRepository,
    )

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
    formCode: String = "MOTHER_REGISTRATION",
  ) =
    FormUploadRecord(
      localBeneficiaryId = id,
      formCode = formCode,
      syncStatus = status,
      createdAtEpochMillis = createdAtEpochMillis,
      pendingNewPregnancyBeneficiaryId = pendingNewPregnancyBeneficiaryId,
    )

  /** Same shape as [record], for the Children Register queue (`formCode == "CHILD_REGISTRATION"`). */
  private fun childRecord(id: String, status: EnrollmentSyncStatus, createdAtEpochMillis: Long) =
    record(id, status, createdAtEpochMillis, formCode = "CHILD_REGISTRATION")

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
  fun `NoDashboardCacheAvailableException results in NeedsInitialSync, not the generic Error`() = runTest(dispatcher) {
    // Distinguishes "never synced on this device and offline right now" (a known, explainable
    // cause) from any other failure — see HomeUiState.NeedsInitialSync's doc.
    repository.error = NoDashboardCacheAvailableException()
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(HomeUiState.NeedsInitialSync, viewModel.uiState.value)
  }

  @Test
  fun `an unrelated failure still resolves to the generic Error, not NeedsInitialSync`() = runTest(dispatcher) {
    repository.error = IOException("network down")
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(HomeUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun `retry from NeedsInitialSync after connectivity returns reaches Success`() = runTest(dispatcher) {
    repository.error = NoDashboardCacheAvailableException()
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(HomeUiState.NeedsInitialSync, viewModel.uiState.value)

    repository.error = null
    viewModel.loadSummary()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value is HomeUiState.Success)
  }

  @Test
  fun `retry from NeedsInitialSync while still offline re-emits NeedsInitialSync, not stuck Loading`() = runTest(dispatcher) {
    repository.error = NoDashboardCacheAvailableException()
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(HomeUiState.NeedsInitialSync, viewModel.uiState.value)

    viewModel.loadSummary()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(HomeUiState.NeedsInitialSync, viewModel.uiState.value)
  }

  @Test
  fun `a cancelled load propagates instead of resolving to Error`() = runTest(dispatcher) {
    // CancellationException is an Exception subclass, so a bare `catch (e: Exception)` would
    // swallow it and paint HomeUiState.Error on a screen the Sakhi is navigating away from,
    // breaking structured concurrency. It must be rethrown, leaving the state as it was.
    repository.error = CancellationException("scope cancelled")
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(HomeUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun `retry after error reloads and reaches Success`() = runTest(dispatcher) {
    repository.error = IOException("network down")
    val viewModel = viewModel()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(HomeUiState.Error, viewModel.uiState.value)

    // `uiState` is a combine(...).stateIn(...) over `_uiState` (see HomeViewModel.uiState), not
    // `_uiState.asStateFlow()`, so the transient Loading is only observable to a real collector —
    // sampling `.value` once the scheduler settles can only ever show the terminal state.
    // Collecting asserts the retry passes THROUGH Loading on its way to Success.
    val observed = mutableListOf<HomeUiState>()
    val collectJob = backgroundScope.launch(dispatcher) { viewModel.uiState.collect { observed += it } }
    dispatcher.scheduler.runCurrent()
    observed.clear()

    repository.error = null
    viewModel.loadSummary()
    dispatcher.scheduler.advanceUntilIdle()
    collectJob.cancel()

    assertTrue("expected a Loading before the reload resolved, saw $observed", observed.contains(HomeUiState.Loading))
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

  // --- Dashboard summary refresh after a sync completes -------------------------------------

  @Test
  fun `summary reloads once a sync completes so the Updated caption catches up`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()

    // Server now reports a sync time -- as it would right after this device's upload landed.
    repository.summary = repository.summary.copy(lastSyncedAt = java.time.Instant.parse("2026-08-19T09:00:00Z"))

    // The sync worker marks the draft SYNCED with no further user action.
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(state is HomeUiState.Success)
    assertEquals(
      java.time.Instant.parse("2026-08-19T09:00:00Z"),
      (state as HomeUiState.Success).summary.lastSyncedAt,
    )
  }

  @Test
  fun `an account with nothing ever queued does not trigger a spurious reload on launch`() =
    runTest(dispatcher) {
      val viewModel = viewModel()
      observe(viewModel)
      dispatcher.scheduler.advanceUntilIdle()
      val callsAfterInitialLoad = repository.getSummaryCallCount

      // pendingUploadCount starts and stays at zero -- nothing to complete, nothing to refresh.
      dispatcher.scheduler.advanceUntilIdle()

      assertEquals(callsAfterInitialLoad, repository.getSummaryCallCount)
    }

  @Test
  fun `a failed background refresh after sync leaves the existing summary on screen`() = runTest(dispatcher) {
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()

    repository.error = IOException("network down")
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    // The background refresh failed silently -- still Success with the last good summary, not Error.
    val state = viewModel.uiState.value
    assertTrue(state is HomeUiState.Success)
    assertEquals("Test Sakhi", (state as HomeUiState.Success).summary.sakhiName)
  }

  // --- Offline pending-beneficiary-count overlay (M3) ---------------------------------------

  @Test
  fun `a pending local mother draft increments the displayed count immediately`() = runTest(dispatcher) {
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()
    val before = viewModel.uiState.value as HomeUiState.Success

    // A registration saved offline — no sync attempted yet, so the server summary above hasn't
    // moved, but the local queue already has the new draft.
    uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    val after = viewModel.uiState.value as HomeUiState.Success
    assertEquals(before.summary.activeMothersCount + 1, after.summary.activeMothersCount)
    assertEquals(before.summary.totalActiveBeneficiaries + 1, after.summary.totalActiveBeneficiaries)
    assertEquals(before.summary.activeChildrenCount, after.summary.activeChildrenCount)
  }

  @Test
  fun `a pending local child draft increments the displayed count immediately`() = runTest(dispatcher) {
    val viewModel = viewModel()
    observe(viewModel)
    dispatcher.scheduler.advanceUntilIdle()
    val before = viewModel.uiState.value as HomeUiState.Success

    uploadRecordsSource.setRecords(listOf(childRecord("local-1", EnrollmentSyncStatus.PENDING, 1L)))
    dispatcher.scheduler.advanceUntilIdle()

    val after = viewModel.uiState.value as HomeUiState.Success
    assertEquals(before.summary.activeChildrenCount + 1, after.summary.activeChildrenCount)
    assertEquals(before.summary.totalActiveBeneficiaries + 1, after.summary.totalActiveBeneficiaries)
    assertEquals(before.summary.activeMothersCount, after.summary.activeMothersCount)
  }

  @Test
  fun `the overlay returns to the server-only value once the draft's syncStatus flips to SYNCED`() =
    runTest(dispatcher) {
      val viewModel = viewModel()
      observe(viewModel)
      dispatcher.scheduler.advanceUntilIdle()
      val serverOnly = viewModel.uiState.value as HomeUiState.Success

      uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.PENDING, 1L)))
      dispatcher.scheduler.advanceUntilIdle()
      assertEquals(
        serverOnly.summary.activeMothersCount + 1,
        (viewModel.uiState.value as HomeUiState.Success).summary.activeMothersCount,
      )

      // The sync worker marks it SYNCED — the beneficiary is now counted by the server too, so the
      // local overlay must drop back out rather than double-count her.
      uploadRecordsSource.setRecords(listOf(record("local-1", EnrollmentSyncStatus.SYNCED, 1L)))
      dispatcher.scheduler.advanceUntilIdle()

      assertEquals(
        serverOnly.summary.activeMothersCount,
        (viewModel.uiState.value as HomeUiState.Success).summary.activeMothersCount,
      )
    }

  @Test
  fun `a DUPLICATE_CONFLICT mother draft still counts as pending, matching the badge's own rule`() =
    runTest(dispatcher) {
      val viewModel = viewModel()
      observe(viewModel)
      dispatcher.scheduler.advanceUntilIdle()
      val before = viewModel.uiState.value as HomeUiState.Success

      uploadRecordsSource.setRecords(
        listOf(record("local-1", EnrollmentSyncStatus.DUPLICATE_CONFLICT, 1L, "earlier-case")),
      )
      dispatcher.scheduler.advanceUntilIdle()

      assertEquals(
        before.summary.activeMothersCount + 1,
        (viewModel.uiState.value as HomeUiState.Success).summary.activeMothersCount,
      )
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

  // --- Offline Data Upload tap (2026-08-08) ------------------------------------------------

  @Test
  fun `Data Upload tap while offline starts no sync and never opens the modal`() = runTest(dispatcher) {
    connectivityChecker.online = false
    val viewModel = viewModel()
    observe(viewModel)

    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, dynamicScheduler.syncNowCallCount)
    assertEquals(0, childScheduler.syncNowCallCount)
    assertEquals(0, enrollmentScheduler.syncNowCallCount)
    assertFalse(viewModel.uploadModalState.value.isVisible)
  }

  @Test
  fun `Data Upload tap while offline emits OfflineUploadBlocked`() = runTest(dispatcher) {
    connectivityChecker.online = false
    val viewModel = viewModel()
    observe(viewModel)

    val events = mutableListOf<HomeEvent>()
    backgroundScope.launch(dispatcher) { viewModel.events.toList(events) }

    viewModel.onDataUploadClicked()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(HomeEvent.OfflineUploadBlocked), events)
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
