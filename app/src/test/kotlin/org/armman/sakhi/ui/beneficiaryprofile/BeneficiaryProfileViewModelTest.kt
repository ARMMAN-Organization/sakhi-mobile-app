package org.armman.sakhi.ui.beneficiaryprofile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.delivery.FakeDeliverySessionDao
import org.armman.sakhi.data.delivery.RoomDeliverySessionRepository
import org.armman.sakhi.data.lmpchange.FakeLmpChangeRepository
import org.armman.sakhi.data.lmpchange.LmpChangeRequestRowDto
import org.armman.sakhi.data.lmpchange.LocalLmpChangeAppliedStore
import org.armman.sakhi.data.reopen.FakeReopenRepository
import org.armman.sakhi.data.visitform.FakeReferralRepository
import org.armman.sakhi.data.reopen.ReopenRequestReason
import org.armman.sakhi.data.reopen.ReopenSubmissionException
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.CcvScheduleGenerator
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.IncScheduleGenerator
import org.armman.sakhi.data.schedule.NnScheduleGenerator
import org.armman.sakhi.data.schedule.PpScheduleGenerator
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryProfileViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeRepository(
    var error: Exception? = null,
    /** CR-Closure-02: lets a test swap in a CLOSED variant of MOTHER/CHILD (with a specific
     * [BeneficiaryProfile.closureReasonCode]) without needing a second fake id per case. */
    var closureOverride: BeneficiaryProfile? = null,
  ) : BeneficiaryProfileRepository {
    /** CR-Closure-04: lets a test confirm loadProfile() re-fetches once (not just clears the
     * local override) after noticing an approved reopen request. */
    var callCount = 0

    override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
      callCount++
      error?.let { throw it }
      closureOverride?.let { if (it.id == id) return it }
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
  private lateinit var statusOverrideStore: LocalBeneficiaryStatusOverrideStore
  private lateinit var lmpChangeRepository: FakeLmpChangeRepository
  private lateinit var visitScheduleCoordinator: VisitScheduleCoordinator
  private lateinit var scheduleRuleSource: HardcodedRuleSource
  private lateinit var lmpChangeAppliedStore: LocalLmpChangeAppliedStore
  private lateinit var referralRepository: FakeReferralRepository

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
    statusOverrideStore = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore())
    lmpChangeRepository = FakeLmpChangeRepository()
    // Real coordinator/generators (mirrors VisitScheduleCoordinatorTest's own construction) --
    // task 3 needs the actual regeneration behavior verified, not just that some call happened.
    scheduleRuleSource = HardcodedRuleSource()
    visitScheduleCoordinator = VisitScheduleCoordinator(
      repository = visitScheduleRepository,
      ancGenerator = AncScheduleGenerator(scheduleRuleSource),
      ppGenerator = PpScheduleGenerator(scheduleRuleSource),
      nnGenerator = NnScheduleGenerator(scheduleRuleSource),
      incGenerator = IncScheduleGenerator(scheduleRuleSource),
      ccvGenerator = CcvScheduleGenerator(scheduleRuleSource),
    )
    lmpChangeAppliedStore = LocalLmpChangeAppliedStore(FakeSecureKeyValueStore())
    referralRepository = FakeReferralRepository()
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
        statusOverrideStore,
        lmpChangeRepository,
        visitScheduleCoordinator,
        scheduleRuleSource,
        lmpChangeAppliedStore,
        referralRepository,
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

    viewModel.submitReopenRequest(ReopenRequestReason.CLOSED_BY_MISTAKE)
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isSubmittingReopen)
    assertFalse(viewModel.uiState.value.hasPendingReopenRequest)
  }

  @Test
  fun `isReopenEligible is false for an ACTIVE beneficiary`() {
    val state = createViewModel("mother").uiState.value
    assertFalse(state.isReopenEligible)
  }

  @Test
  fun `isReopenEligible is true for a CLOSED beneficiary with an unknown closure reason`() {
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = null)
    val state = createViewModel("mother").uiState.value
    assertTrue(state.isReopenEligible)
  }

  @Test
  fun `isReopenEligible is true for a CLOSED beneficiary closed for migration`() {
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    val state = createViewModel("mother").uiState.value
    assertTrue(state.isReopenEligible)
  }

  @Test
  fun `isReopenEligible is false for a CLOSED beneficiary closed for maternal death`() {
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MATERNAL_DEATH")
    val state = createViewModel("mother").uiState.value
    assertFalse(state.isReopenEligible)
  }

  @Test
  fun `bug fix 2026-09-09 - a straggler open visit on an already-CLOSED beneficiary is self-healed on profile load`() = runTest {
    // Regression test: AdHocFormSubmissionCoordinator.submitClosure's lapseAllOpenVisits sweep
    // runs exactly once, best-effort, at closure-submit time -- if that sweep ever misses a row
    // (a race with a just-generated visit, a transient failure), nothing else in the app ever
    // retries it. Reported: "Beneficiary status marked 'Death', but PP1 remains active and
    // available for processing." This seeds a PP1 row still GENERATED (as if it were written
    // after -- or missed by -- the original closure-time sweep) on an already-CLOSED beneficiary,
    // then asserts a profile load alone cancels it.
    visitScheduleRepository.saveGenerated(
      listOf(
        schedule(
          "pp1-schedule",
          localBeneficiaryId = "mother",
          visitCode = "PP1",
          visitType = VisitCodeType.PP,
          sequenceNo = 1,
          status = VisitScheduleStatus.GENERATED,
        ),
      ),
    )
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MATERNAL_DEATH")

    createViewModel("mother")

    val pp1 = scheduleDao.getByLocalUuid("pp1-schedule")
    assertEquals(VisitScheduleStatus.CANCELLED, pp1?.status)
  }

  @Test
  fun `bug fix 2026-09-09 - an ACTIVE beneficiary's open visits are left untouched on profile load`() = runTest {
    // Same seed as above but WITHOUT closure -- the self-heal sweep must stay CLOSED-gated, not
    // start cancelling a live beneficiary's own open visits on every ordinary profile view.
    visitScheduleRepository.saveGenerated(
      listOf(
        schedule(
          "pp1-schedule-active",
          localBeneficiaryId = "mother",
          visitCode = "PP1",
          visitType = VisitCodeType.PP,
          sequenceNo = 1,
          status = VisitScheduleStatus.GENERATED,
        ),
      ),
    )

    createViewModel("mother")

    val pp1 = scheduleDao.getByLocalUuid("pp1-schedule-active")
    assertEquals(VisitScheduleStatus.GENERATED, pp1?.status)
  }

  @Test
  fun `isReopenEligible is false for a CLOSED beneficiary closed for miscarriage or abortion`() {
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MISCARRIAGE")
    assertFalse(createViewModel("mother").uiState.value.isReopenEligible)

    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "ABORTION")
    assertFalse(createViewModel("mother").uiState.value.isReopenEligible)
  }

  @Test
  fun `isReopenEligible is false for a CLOSED infant closed for infant death`() {
    repository.closureOverride = CHILD.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "INFANT_OR_CHILD_DEATH")
    val state = createViewModel("child").uiState.value
    assertFalse(state.isReopenEligible)
  }

  @Test
  fun `loadProfile clears the local override and re-fetches when a reopen request is approved`() = runTest {
    statusOverrideStore.setStatus("mother", BeneficiaryStatus.CLOSED)
    statusOverrideStore.setClosureReason("mother", "MIGRATION")
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.approvedBeneficiaryIds = setOf("mother")

    createViewModel("mother")

    assertNull(statusOverrideStore.getStatus("mother"))
    assertNull(statusOverrideStore.getClosureReason("mother"))
    assertEquals(2, repository.callCount)
  }

  @Test
  fun `loadProfile leaves the local override alone when no reopen request is approved yet`() = runTest {
    statusOverrideStore.setStatus("mother", BeneficiaryStatus.CLOSED)
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.approvedBeneficiaryIds = emptySet()

    createViewModel("mother")

    assertEquals(BeneficiaryStatus.CLOSED, statusOverrideStore.getStatus("mother"))
    assertEquals(1, repository.callCount)
  }

  @Test
  fun `loadProfile never checks for an approved reopen request when the beneficiary is ACTIVE`() = runTest {
    reopenRepository.approvedBeneficiaryIds = setOf("mother")

    createViewModel("mother")

    // ACTIVE the whole time -- the approved-request check is CLOSED-gated, same as
    // hasPendingReopenRequest, so this must not trigger a second getBeneficiary() call.
    assertEquals(1, repository.callCount)
  }

  @Test
  fun `loadProfile clears the local override when the approval is recorded under the resolved server beneficiary id`() = runTest {
    // Bug fix (2026-09-04): on-device testing found the reopen-approval poll queried
    // hasApprovedReopenRequest with the LOCAL beneficiaryId ("mother" here) instead of the
    // resolved server id -- exactly like submitReopenRequest already resolves (see that test
    // above) -- so a real approval (always keyed server-side) never matched and this beneficiary
    // stayed CLOSED forever. Every other test above/below seeds approvedBeneficiaryIds with the
    // bare local id and never calls seedServerBeneficiaryId, so they'd have passed identically
    // with the old, buggy code (the missing schedule row just falls back to the local id) --
    // this test is the one that actually exercises the local/server id split and would have
    // failed before the fix.
    seedServerBeneficiaryId("mother", "server-mother-1")
    statusOverrideStore.setStatus("mother", BeneficiaryStatus.CLOSED)
    statusOverrideStore.setClosureReason("mother", "MIGRATION")
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.approvedBeneficiaryIds = setOf("server-mother-1")

    createViewModel("mother")

    assertNull(statusOverrideStore.getStatus("mother"))
    assertNull(statusOverrideStore.getClosureReason("mother"))
    // 3 calls: initial load, re-fetch after lapse sweep (seedServerBeneficiaryId created an
    // OPEN schedule row that lapseAllOpenVisits cancels), re-fetch after reopen approval clears
    // the local override.
    assertEquals(3, repository.callCount)
  }

  @Test
  fun `hasPendingReopenRequest is true when the pending request is recorded under the resolved server beneficiary id`() = runTest {
    // Bug fix (2026-09-04): same local/server id split as the approved-request test above, for
    // the pending check -- this is what made a just-submitted request's "Reopen pending review"
    // button revert to plain "Reopen" on the next profile load (submitReopenRequest itself always
    // used the resolved server id, but the follow-up hasPendingReopenRequest poll did not).
    seedServerBeneficiaryId("mother", "server-mother-1")
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.pendingBeneficiaryIds = setOf("server-mother-1")

    val state = createViewModel("mother").uiState.value

    assertTrue(state.hasPendingReopenRequest)
  }

  @Test
  fun `hasRejectedReopenRequest is true when the rejected request is recorded under the resolved server beneficiary id`() = runTest {
    // Bug fix (2026-09-04): same local/server id split, for the rejected check.
    seedServerBeneficiaryId("mother", "server-mother-1")
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.rejectedBeneficiaryIds = setOf("server-mother-1")

    val state = createViewModel("mother").uiState.value

    assertTrue(state.hasRejectedReopenRequest)
  }

  @Test
  fun `hasRejectedReopenRequest is true when a CLOSED beneficiary has a rejected reopen request and none pending`() = runTest {
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.rejectedBeneficiaryIds = setOf("mother")

    val state = createViewModel("mother").uiState.value

    assertTrue(state.hasRejectedReopenRequest)
  }

  @Test
  fun `hasRejectedReopenRequest is false when no reopen request has been rejected`() = runTest {
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.rejectedBeneficiaryIds = emptySet()

    val state = createViewModel("mother").uiState.value

    assertFalse(state.hasRejectedReopenRequest)
  }

  @Test
  fun `hasRejectedReopenRequest is false when a fresh reopen request is already pending`() = runTest {
    // A prior request was rejected, but the Sakhi has since submitted a new one that is now
    // PENDING -- the pending-review copy should win, not a stale rejection banner.
    repository.closureOverride = MOTHER.copy(status = BeneficiaryStatus.CLOSED, closureReasonCode = "MIGRATION")
    reopenRepository.rejectedBeneficiaryIds = setOf("mother")
    reopenRepository.pendingBeneficiaryIds = setOf("mother")

    val state = createViewModel("mother").uiState.value

    assertFalse(state.hasRejectedReopenRequest)
    assertTrue(state.hasPendingReopenRequest)
  }

  @Test
  fun `loadProfile never checks for a rejected reopen request when the beneficiary is ACTIVE`() = runTest {
    reopenRepository.rejectedBeneficiaryIds = setOf("mother")

    val state = createViewModel("mother").uiState.value

    // ACTIVE the whole time -- the rejected-request check is CLOSED-gated, same as
    // hasPendingReopenRequest/hasApprovedReopenRequest.
    assertFalse(state.hasRejectedReopenRequest)
  }

  // ---- Task 3/4 (LMP/Reopen/Referral/Audit task list): LMP correction approval/rejection -------

  private fun approvedLmpRow(newLmpDate: String, id: String = "lmp-req-1") = LmpChangeRequestRowDto(
    id = id,
    beneficiaryId = "mother",
    oldLmpDate = "2025-12-01",
    newLmpDate = newLmpDate,
    sonographyImageAssetId = "asset-1",
    requestedByUserId = "sakhi-1",
    requestedAt = "2026-08-30T00:00:00Z",
    supervisorStatus = "APPROVED",
    decidedByUserId = "supervisor-1",
    decidedAt = "2026-08-31T00:00:00Z",
  )

  @Test
  fun `loadProfile regenerates the ANC schedule when an LMP change request is approved`() = runTest {
    repository.closureOverride = MOTHER.copy(registrationDate = LocalDate.of(2026, 2, 1))
    lmpChangeRepository.approvedRequestByBeneficiaryId = mapOf("mother" to approvedLmpRow("2026-02-01"))

    createViewModel("mother")

    val generated = visitScheduleRepository.getForBeneficiary("mother")
    assertEquals(
      "expected 10 ANC visits, got ${generated.size}: types=${generated.map { it.visitType }}, codes=${generated.map { it.visitCode }}",
      10, generated.size,
    )
    assertTrue(generated.all { it.visitType == VisitCodeType.ANC })
    assertEquals("lmp-req-1", lmpChangeAppliedStore.getAppliedRequestId("mother"))
  }

  @Test
  fun `loadProfile does not regenerate the schedule again for an already-applied LMP approval`() = runTest {
    repository.closureOverride = MOTHER.copy(registrationDate = LocalDate.of(2026, 1, 1))
    lmpChangeRepository.approvedRequestByBeneficiaryId = mapOf("mother" to approvedLmpRow("2026-02-01"))
    lmpChangeAppliedStore.setAppliedRequestId("mother", "lmp-req-1")

    createViewModel("mother")

    // Already applied -- must not have generated a second series (or any at all, since this test
    // starts with no schedule to begin with; a re-application would still show up as a fresh 10).
    assertTrue(visitScheduleRepository.getForBeneficiary("mother").isEmpty())
  }

  @Test
  fun `loadProfile regenerates again when a NEWER LMP change request is approved after an earlier one was applied`() = runTest {
    repository.closureOverride = MOTHER.copy(registrationDate = LocalDate.of(2026, 3, 1))
    lmpChangeRepository.approvedRequestByBeneficiaryId =
      mapOf("mother" to approvedLmpRow("2026-03-01", id = "lmp-req-2"))
    lmpChangeAppliedStore.setAppliedRequestId("mother", "lmp-req-1")

    createViewModel("mother")

    assertEquals(10, visitScheduleRepository.getForBeneficiary("mother").size)
    assertEquals("lmp-req-2", lmpChangeAppliedStore.getAppliedRequestId("mother"))
  }

  @Test
  fun `loadProfile does not regenerate when no LMP change request is approved`() = runTest {
    repository.closureOverride = MOTHER.copy(registrationDate = LocalDate.of(2026, 1, 1))

    createViewModel("mother")

    assertTrue(visitScheduleRepository.getForBeneficiary("mother").isEmpty())
    assertNull(lmpChangeAppliedStore.getAppliedRequestId("mother"))
  }

  @Test
  fun `hasRejectedLmpChangeRequest is true for a MOTHER with a rejected request and none pending`() = runTest {
    lmpChangeRepository.rejectedBeneficiaryIds = setOf("mother")

    val state = createViewModel("mother").uiState.value

    assertTrue(state.hasRejectedLmpChangeRequest)
  }

  @Test
  fun `hasRejectedLmpChangeRequest is false when a fresh LMP change request is already pending`() = runTest {
    lmpChangeRepository.rejectedBeneficiaryIds = setOf("mother")
    lmpChangeRepository.pendingBeneficiaryIds = setOf("mother")

    val state = createViewModel("mother").uiState.value

    assertFalse(state.hasRejectedLmpChangeRequest)
  }

  @Test
  fun `LMP change checks are never made for a CHILD profile`() = runTest {
    lmpChangeRepository.approvedRequestByBeneficiaryId = mapOf("child" to approvedLmpRow("2026-02-01"))
    lmpChangeRepository.rejectedBeneficiaryIds = setOf("child")

    val state = createViewModel("child").uiState.value

    assertFalse(state.hasRejectedLmpChangeRequest)
    assertTrue(visitScheduleRepository.getForBeneficiary("child").isEmpty())
  }

  @Test
  fun `loadProfile refreshes referral statuses for this beneficiary`() = runTest {
    createViewModel("mother")

    assertEquals(listOf("mother"), referralRepository.refreshReferralStatusesCalls)
  }

  @Test
  fun `loadProfile still succeeds when the referral status refresh fails`() = runTest {
    referralRepository.refreshReferralStatusesResult = Result.failure(IOException("offline"))

    val state = createViewModel("mother").uiState.value

    assertFalse(state.hasError)
    assertNotNull(state.profile)
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
