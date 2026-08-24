package org.armman.sakhi.ui.delivery

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.childregistration.ChildNonRenderableQuestionCodes
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftRepository
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmitResult
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliveryFormSubmitResult
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.delivery.FakeDeliverySessionDao
import org.armman.sakhi.data.delivery.RoomDeliverySessionRepository
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.auth.FakeCurrentUserRepository
import org.armman.sakhi.data.forms.BeneficiaryNameQuestionCodes
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODE
import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODE_CORRECTED
import org.armman.sakhi.data.forms.dynamicFormDraftGson
import org.armman.sakhi.data.forms.dynamicFormDraftPayloadKey
import org.armman.sakhi.data.geography.GeographyRepository
import org.armman.sakhi.data.geography.GeographyUnit
import org.armman.sakhi.data.geography.SakhiAssignment
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.motherlink.MotherPrefillQuestionCodes
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.schedule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Covers [DeliveryChildRegistrationViewModel] — resolving which child/index to register off the
 * loaded [DeliverySessionEntity], prefill from the stored DELIVERY_VISIT answers, hidden-field
 * exclusion, twin re-entry (self-[DeliveryChildRegistrationViewModel.load]) vs. moving on to PP1,
 * and the three submit outcomes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryChildRegistrationViewModelTest {

  /** Configurable [DeliveryFormDraftRepository.getAnswers] fake — this ViewModel is the one caller
   * that actually reads a stored DELIVERY_VISIT payload back (see [DeliverySessionViewModelTest]'s
   * own fake, which never exercises this method). */
  private class FakeDeliveryFormDraftRepository : DeliveryFormDraftRepository {
    var answersToReturn: FormAnswers? = null
    var lastRequestedLocalSubmissionUuid: String? = null

    override suspend fun submitDraft(
      localSubmissionUuid: String,
      localSessionUuid: String,
      localBeneficiaryId: String,
      formVersionId: String,
      answers: FormAnswers,
      deliveryDate: java.time.LocalDate,
      deliveryFormFilledOn: java.time.LocalDate,
    ): DeliveryFormSubmitResult = throw UnsupportedOperationException("Not exercised by this ViewModel")

    override suspend fun getAnswers(localSubmissionUuid: String): FormAnswers? {
      lastRequestedLocalSubmissionUuid = localSubmissionUuid
      return answersToReturn
    }
  }

  private class FakeDeliveryChildRegistrationDraftRepository : DeliveryChildRegistrationDraftRepository {
    var resultToReturn: DeliveryChildRegistrationSubmitResult = DeliveryChildRegistrationSubmitResult.Synced
    val submittedAnswers = mutableListOf<FormAnswers>()
    var lastServerBeneficiaryId: String? = null

    override suspend fun submitDraft(
      localSubmissionUuid: String,
      localSessionUuid: String,
      serverBeneficiaryId: String,
      formVersionId: String,
      answers: FormAnswers,
    ): DeliveryChildRegistrationSubmitResult {
      submittedAnswers += answers
      lastServerBeneficiaryId = serverBeneficiaryId
      return resultToReturn
    }
  }

  /** Mirrors [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModelTest]'s own
   * copy — none of this file's tests drive the static cascade, only [GeographyFieldOptionsResolver
   * .optionsFromVersionGeography], which never calls this repository at all. */
  private class FakeGeographyRepository : GeographyRepository {
    override suspend fun getSakhiAssignment() = SakhiAssignment("state-1", "district-1", "block-1")
    override suspend fun getStates(): List<GeographyUnit> = emptyList()
    override suspend fun getDistricts(stateId: String): List<GeographyUnit> = emptyList()
    override suspend fun getBlocks(districtId: String): List<GeographyUnit> = emptyList()
    override suspend fun getVillages(blockId: String): List<GeographyUnit> = emptyList()
    override suspend fun getPadas(villageId: String): List<GeographyUnit> = emptyList()
    override suspend fun getPhcs(villageId: String): List<GeographyUnit> = emptyList()
    override suspend fun getSubCentres(villageId: String): List<GeographyUnit> = emptyList()
  }

  private val dispatcher = StandardTestDispatcher()

  private lateinit var formsRepository: FakeFormsRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var fakeCurrentUserRepository: FakeCurrentUserRepository
  private lateinit var geographyFieldOptionsResolver: GeographyFieldOptionsResolver
  private lateinit var deliveryFormDraftRepository: FakeDeliveryFormDraftRepository
  private lateinit var deliveryChildRegistrationDraftRepository: FakeDeliveryChildRegistrationDraftRepository
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: RoomDeliverySessionRepository
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var visitScheduleRepository: RoomVisitScheduleRepository
  private lateinit var motherDraftDao: FakeDynamicFormDraftDao
  private lateinit var motherSecureStore: FakeSecureKeyValueStore
  private lateinit var localEnrolmentBeneficiarySource: LocalEnrolmentBeneficiarySource

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    formsRepository = FakeFormsRepository()
    lookupRepository = FakeLookupRepository(valuesByCategory = mutableMapOf())
    fakeCurrentUserRepository = FakeCurrentUserRepository()
    geographyFieldOptionsResolver = GeographyFieldOptionsResolver(FakeGeographyRepository(), fakeCurrentUserRepository)
    deliveryFormDraftRepository = FakeDeliveryFormDraftRepository()
    deliveryChildRegistrationDraftRepository = FakeDeliveryChildRegistrationDraftRepository()
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
    scheduleDao = FakeVisitScheduleDao()
    visitScheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    motherDraftDao = FakeDynamicFormDraftDao()
    motherSecureStore = FakeSecureKeyValueStore()
    // A second, independent FormsRepository/VisitScheduleRepository fake — LocalEnrolmentBeneficiarySource
    // only ever uses these to resolve pada/village labels and generate its own schedule lookups, neither
    // of which these tests exercise; kept separate from the ViewModel's own formsRepository/
    // visitScheduleRepository above purely so nobody has to reason about the two use cases sharing state.
    localEnrolmentBeneficiarySource = LocalEnrolmentBeneficiarySource(
      motherDraftDao,
      FakeChildFormDraftDao(),
      motherSecureStore,
      RoomVisitScheduleRepository(FakeVisitScheduleDao()),
      FakeFormsRepository(),
      LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore()),
    )
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun textField(questionCode: String, required: Boolean = false, section: String? = null) = FormFieldSchema(
    label = questionCode,
    required = required,
    inputTypeRaw = "text",
    questionCode = questionCode,
    section = section,
  )

  private fun selectField(questionCode: String, options: List<FormFieldOption>, section: String? = null) = FormFieldSchema(
    label = questionCode,
    required = false,
    inputTypeRaw = "select",
    questionCode = questionCode,
    options = options,
    section = section,
  )

  private fun versionWith(fields: List<FormFieldSchema>, geography: List<FormGeographyUnit>? = null) = FormVersion(
    id = "child-reg-version-1",
    formDefinitionId = "definition-1",
    versionNo = "v6",
    schemaJson = fields,
    validationJson = emptyList(),
    effectiveFrom = "2026-01-01",
    effectiveTo = null,
    status = "PUBLISHED",
    geography = geography,
  )

  private fun session(
    localSessionUuid: String = "session-1",
    localBeneficiaryId: String = "mother-1",
    step: DeliverySessionStep = DeliverySessionStep.CHILD_REGISTRATION,
    deliverySubmissionLocalUuid: String? = "delivery-submission-1",
    child1BeneficiaryId: String? = "child-1",
    child2BeneficiaryId: String? = null,
    child3BeneficiaryId: String? = null,
    nextChildIndexToRegister: Int = 0,
  ) = DeliverySessionEntity(
    localSessionUuid = localSessionUuid,
    localBeneficiaryId = localBeneficiaryId,
    step = step,
    deliverySubmissionLocalUuid = deliverySubmissionLocalUuid,
    child1BeneficiaryId = child1BeneficiaryId,
    child2BeneficiaryId = child2BeneficiaryId,
    child3BeneficiaryId = child3BeneficiaryId,
    nextChildIndexToRegister = nextChildIndexToRegister,
    createdAtEpochMillis = 0L,
    updatedAtEpochMillis = 0L,
  )

  private fun buildViewModel(beneficiaryId: String = "mother-1", sessionUuid: String = "session-1") =
    DeliveryChildRegistrationViewModel(
      formsRepository = formsRepository,
      lookupRepository = lookupRepository,
      geographyFieldOptionsResolver = geographyFieldOptionsResolver,
      deliveryFormDraftRepository = deliveryFormDraftRepository,
      deliveryChildRegistrationDraftRepository = deliveryChildRegistrationDraftRepository,
      deliverySessionRepository = deliverySessionRepository,
      visitScheduleRepository = visitScheduleRepository,
      localEnrolmentBeneficiarySource = localEnrolmentBeneficiarySource,
      savedStateHandle = SavedStateHandle(
        mapOf("beneficiaryId" to beneficiaryId, "sessionUuid" to sessionUuid),
      ),
    )

  /** Seeds [motherDraftDao]/[motherSecureStore] with a MOTHER_REGISTRATION draft for
   * `localBeneficiaryId = "mother-1"` (the default [session]'s own [DeliverySessionEntity
   * .localBeneficiaryId]) carrying the given geography answers — mirrors
   * [org.armman.sakhi.data.beneficiary.LocalEnrolmentPadaLabelTest]'s own seeding pattern. */
  private suspend fun saveMotherDraft(geographyAnswers: Map<String, String>) {
    motherDraftDao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = "mother-1",
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "mother-version-1",
        localSubmissionUuid = "mother-submission-1",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 0L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
    motherSecureStore.putString(
      dynamicFormDraftPayloadKey("mother-1"),
      dynamicFormDraftGson.toJson(
        DynamicFormDraftPayload(
          answers = FormAnswers(singleValues = geographyAnswers),
          registrationDateIso = LocalDate.of(2026, 1, 1).toString(),
        ),
      ),
    )
  }

  @Test
  fun `load() with no session row sets hasError`() {
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.hasError)
  }

  @Test
  fun `load() resolves childIndex 0 and serverBeneficiaryId from child1BeneficiaryId`() = runTest {
    deliverySessionRepository.save(session(nextChildIndexToRegister = 0, child1BeneficiaryId = "child-1"))
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertFalse(state.hasError)
    assertEquals(0, state.childIndex)
    assertEquals("child-1", state.serverBeneficiaryId)
  }

  @Test
  fun `load() resolves childIndex 1 and serverBeneficiaryId from child2BeneficiaryId for the second twin`() = runTest {
    deliverySessionRepository.save(
      session(nextChildIndexToRegister = 1, child1BeneficiaryId = "child-1", child2BeneficiaryId = "child-2"),
    )
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(1, state.childIndex)
    assertEquals("child-2", state.serverBeneficiaryId)
  }

  @Test
  fun `load() sets hasError when the session's step index has no corresponding child id`() = runTest {
    deliverySessionRepository.save(
      session(nextChildIndexToRegister = 1, child1BeneficiaryId = "child-1", child2BeneficiaryId = null),
    )
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.hasError)
  }

  @Test
  fun `load() prefills answers from the stored delivery answers via DeliveryToChildRegistrationPrefill`() = runTest {
    deliverySessionRepository.save(session())
    deliveryFormDraftRepository.answersToReturn = FormAnswers(
      singleValues = mapOf(DeliveryQuestionCodes.DATE_OF_DELIVERY to "2026-08-01"),
    )
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("2026-08-01", viewModel.uiState.value.answers.valueOf(ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT))
    assertEquals(
      ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
      viewModel.uiState.value.answers.valueOf(ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING),
    )
    assertEquals("delivery-submission-1", deliveryFormDraftRepository.lastRequestedLocalSubmissionUuid)
  }

  @Test
  fun `load() still succeeds with a degraded prefill when the delivery answers payload is gone`() = runTest {
    deliverySessionRepository.save(session())
    deliveryFormDraftRepository.answersToReturn = null
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.hasError)
    assertEquals(
      ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER,
      viewModel.uiState.value.answers.valueOf(ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING),
    )
  }

  @Test
  fun `load() always seeds mother_beneficiary_id with a placeholder, even with no mother draft on this device`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      "1",
      viewModel.uiState.value.answers.valueOf(MotherPrefillQuestionCodes.MOTHER_BENEFICIARY_ID),
    )
  }

  @Test
  fun `load() prefills the mother's own geography from her local MOTHER_REGISTRATION draft`() = runTest {
    deliverySessionRepository.save(session(localBeneficiaryId = "mother-1"))
    saveMotherDraft(
      mapOf(
        GeographyQuestionCodes.STATE to "state-1",
        GeographyQuestionCodes.PADA to "pada-1",
      ),
    )
    formsRepository.version = versionWith(
      fields = emptyList(),
      geography = listOf(
        FormGeographyUnit(geographyUnitId = "state-1", geoType = "STATE", name = "Maharashtra"),
        FormGeographyUnit(geographyUnitId = "pada-1", geoType = "PADA", name = "Toranmal"),
      ),
    )

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("state-1", viewModel.uiState.value.answers.valueOf(GeographyQuestionCodes.STATE))
    assertEquals("pada-1", viewModel.uiState.value.answers.valueOf(GeographyQuestionCodes.PADA))
  }

  @Test
  fun `load() prefills the mother's own name, mobile number and consent from her local draft`() = runTest {
    deliverySessionRepository.save(session(localBeneficiaryId = "mother-1"))
    saveMotherDraft(
      mapOf(
        BeneficiaryNameQuestionCodes.CURRENT to "Asha Patil",
        MotherPrefillQuestionCodes.MOBILE_NUMBER to "9876543210",
        MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT to "yes",
      ),
    )
    formsRepository.version = versionWith(fields = emptyList())

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val answers = viewModel.uiState.value.answers
    assertEquals("Asha Patil", answers.valueOf(MotherPrefillQuestionCodes.CAREGIVER_NAME))
    assertEquals("9876543210", answers.valueOf(MotherPrefillQuestionCodes.MOBILE_NUMBER))
    assertEquals("yes", answers.valueOf(MotherPrefillQuestionCodes.DID_WE_RECEIVE_CONSENT))
  }

  @Test
  fun `load() leaves geography unfilled when the mother has no local draft on this device`() = runTest {
    deliverySessionRepository.save(session(localBeneficiaryId = "mother-1"))
    formsRepository.version = versionWith(
      fields = emptyList(),
      geography = listOf(FormGeographyUnit(geographyUnitId = "state-1", geoType = "STATE", name = "Maharashtra")),
    )

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.hasError)
    assertNull(viewModel.uiState.value.answers.valueOf(GeographyQuestionCodes.STATE))
  }

  @Test
  fun `visibleFields excludes WHO_ARE_YOU_REGISTERING and every ChildNonRenderableQuestionCodes entry`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        textField(ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING),
        textField(ChildNonRenderableQuestionCodes.BENEFICIARY_ID),
        textField("mother_beneficiary_id"),
        textField("date_of_birth_of_infant"),
      ),
    )

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val visibleCodes = viewModel.visibleFields().map { it.questionCode }
    assertFalse(visibleCodes.contains(ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING))
    assertFalse(visibleCodes.contains(ChildNonRenderableQuestionCodes.BENEFICIARY_ID))
    // mother_beneficiary_id stays visible/Sakhi-fillable — see DeliveryToChildRegistrationPrefill's
    // own doc for why this one is deliberately not hidden or prefilled.
    assertTrue(visibleCodes.contains("mother_beneficiary_id"))
    assertTrue(visibleCodes.contains("date_of_birth_of_infant"))
  }

  @Test
  fun `onSubmit injects the known serverBeneficiaryId as the BENEFICIARY_ID answer`() = runTest {
    deliverySessionRepository.save(session(child1BeneficiaryId = "child-1"))
    formsRepository.version = versionWith(fields = emptyList())
    deliveryChildRegistrationDraftRepository.resultToReturn = DeliveryChildRegistrationSubmitResult.Synced
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("child-1", deliveryChildRegistrationDraftRepository.lastServerBeneficiaryId)
    assertEquals(
      "child-1",
      deliveryChildRegistrationDraftRepository.submittedAnswers.last().valueOf(ChildNonRenderableQuestionCodes.BENEFICIARY_ID),
    )
  }

  @Test
  fun `onSubmit Synced with another child still pending emits hasMoreChildren and reloads for the next twin`() = runTest {
    deliverySessionRepository.save(
      session(child1BeneficiaryId = "child-1", child2BeneficiaryId = "child-2", nextChildIndexToRegister = 0),
    )
    formsRepository.version = versionWith(fields = emptyList())
    deliveryChildRegistrationDraftRepository.resultToReturn = DeliveryChildRegistrationSubmitResult.Synced
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    // Mirrors what DeliveryChildRegistrationSubmissionCoordinator itself would have done on a real
    // success — this fake doesn't run the coordinator, so the test advances the session row the
    // same way, then verifies the ViewModel reacts to it correctly.
    deliverySessionRepository.save(
      session(child1BeneficiaryId = "child-1", child2BeneficiaryId = "child-2", nextChildIndexToRegister = 1),
    )

    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    val submitted = viewModel.events.first() as DeliveryChildRegistrationEvent.Submitted
    assertTrue(submitted.hasMoreChildren)
    assertNull(submitted.pp1LocalScheduleUuid)
    // The reload already resolved the SECOND child by the time this event is observed.
    assertEquals(1, viewModel.uiState.value.childIndex)
    assertEquals("child-2", viewModel.uiState.value.serverBeneficiaryId)
  }

  @Test
  fun `onSubmit Synced with every child registered emits hasMoreChildren false and the PP1 schedule id`() = runTest {
    deliverySessionRepository.save(session(child1BeneficiaryId = "child-1", nextChildIndexToRegister = 0))
    formsRepository.version = versionWith(fields = emptyList())
    deliveryChildRegistrationDraftRepository.resultToReturn = DeliveryChildRegistrationSubmitResult.Synced
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-schedule", localBeneficiaryId = "mother-1", visitCode = "PP1", visitType = VisitCodeType.PP, sequenceNo = 1)),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    // Same rationale as the twin-sequencing test above: advance the session the way the real
    // coordinator would once every child is registered.
    deliverySessionRepository.save(session(step = DeliverySessionStep.PP1, child1BeneficiaryId = "child-1", nextChildIndexToRegister = 1))

    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    val submitted = viewModel.events.first() as DeliveryChildRegistrationEvent.Submitted
    assertFalse(submitted.hasMoreChildren)
    assertEquals("pp1-schedule", submitted.pp1LocalScheduleUuid)
  }

  @Test
  fun `onSubmit QueuedOffline emits QueuedOffline without advancing the session`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = emptyList())
    deliveryChildRegistrationDraftRepository.resultToReturn = DeliveryChildRegistrationSubmitResult.QueuedOffline
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(DeliveryChildRegistrationEvent.QueuedOffline, viewModel.events.first())
    assertFalse(viewModel.uiState.value.isSubmitting)
  }

  @Test
  fun `onSubmit Failed emits SubmitFailed with the message`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = emptyList())
    deliveryChildRegistrationDraftRepository.resultToReturn = DeliveryChildRegistrationSubmitResult.Failed("boom")
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    val failed = viewModel.events.first() as DeliveryChildRegistrationEvent.SubmitFailed
    assertEquals("boom", failed.message)
    assertFalse(viewModel.uiState.value.isSubmitting)
  }

  @Test
  fun `markMediaComplete gates isReadyToSubmit for a required MEDIA field`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        FormFieldSchema(label = "consent_video", required = true, inputTypeRaw = "media", questionCode = "consent_video"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isReadyToSubmit())

    viewModel.markMediaComplete("consent_video")

    assertTrue(viewModel.isReadyToSubmit())
  }

  /**
   * Regression test for the reported 422 (2026-08-19): `arogya_sakhi_video`/`consent_audio` showed
   * "Completed" on the Summary tab (driven off [DeliveryChildRegistrationUiState.mediaCompleted])
   * but the actual submitted answers never carried the field at all, because
   * [DeliveryChildRegistrationViewModel.markMediaComplete] only updated [mediaCompleted], never
   * [FormAnswers] itself — unlike [org.armman.sakhi.ui.childregistration
   * .DynamicChildRegistrationViewModel.markMediaComplete], which this was supposed to mirror.
   */
  @Test
  fun `markMediaComplete also writes the answer, not just the mediaCompleted set`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        FormFieldSchema(label = "consent_video", required = true, inputTypeRaw = "media", questionCode = "consent_video"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.markMediaComplete("consent_video")

    assertEquals("true", viewModel.uiState.value.answers.valueOf("consent_video"))
  }

  /**
   * Regression test for the reported 422 (2026-08-19): `registrtion_date` is hidden from the Sakhi
   * (see [ChildNonRenderableQuestionCodes.ALL]) on the promise that something still auto-fills it —
   * this ViewModel never did, so it submitted blank. Covers both published spellings.
   */
  @Test
  fun `load() auto-fills the hidden registration-date field with today, either spelling`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = listOf(textField(REGISTRATION_DATE_QUESTION_CODE)))

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.now().toString(), viewModel.uiState.value.answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
  }

  @Test
  fun `load() auto-fills the corrected registration_date spelling too, not just the typo`() = runTest {
    // The backend has published both spellings across schema versions (typo on CHILD_REGISTRATION
    // v2, corrected later) — this proves the fill isn't hardcoded to just the typo spelling.
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = listOf(textField(REGISTRATION_DATE_QUESTION_CODE_CORRECTED)))

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED),
    )
  }

  /**
   * Regression test for the reported 422 (2026-08-19): `project_name` is hidden from the Sakhi (see
   * [ChildNonRenderableQuestionCodes.ALL]) on the promise that
   * [org.armman.sakhi.data.forms.GeographyFieldOptionsResolver] still auto-selects it — this
   * ViewModel never called that resolver at all until this fix, so it submitted blank.
   */
  @Test
  fun `load() auto-selects project_name from the Sakhi's own profile`() = runTest {
    fakeCurrentUserRepository.profile = fakeCurrentUserRepository.profile?.copy(projectName = "Kokan Project")
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = listOf(textField(GeographyQuestionCodes.PROJECT_NAME)))

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("Kokan Project", viewModel.uiState.value.answers.valueOf(GeographyQuestionCodes.PROJECT_NAME))
  }

  @Test
  fun `exitForm emits ExitForm`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(fields = emptyList())
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.exitForm()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(DeliveryChildRegistrationEvent.ExitForm, viewModel.events.first())
  }

  @Test
  fun `sections() returns distinct schema sections in first-appearance order, falling back for untagged fields`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        textField("infant_name", section = "Infant Details"),
        textField("infant_weight", section = "Infant Details"),
        textField("delivery_place", section = "Delivery Details"),
        textField("untagged_field"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf("Infant Details", "Delivery Details", FALLBACK_SECTION),
      viewModel.sections(),
    )
  }

  @Test
  fun `fieldsInSection returns only that section's visible fields, in schema order`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        textField("infant_name", section = "Infant Details"),
        textField("delivery_place", section = "Delivery Details"),
        textField("infant_weight", section = "Infant Details"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf("infant_name", "infant_weight"),
      viewModel.fieldsInSection("Infant Details").map { it.questionCode },
    )
  }

  @Test
  fun `isSectionReady is false while a required field in that section is unanswered, true once answered`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        textField("infant_name", required = true, section = "Infant Details"),
        textField("delivery_place", required = true, section = "Delivery Details"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    // Infant Details' own required field is unanswered, but Delivery Details doesn't care about it.
    assertFalse(viewModel.isSectionReady("Infant Details"))

    viewModel.setAnswer("infant_name", "Baby A")

    assertTrue(viewModel.isSectionReady("Infant Details"))
  }

  @Test
  fun `buildSummary groups answered fields by section and drops sections with no answered rows`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        textField("infant_name", section = "Infant Details"),
        textField("delivery_place", section = "Delivery Details"),
        textField("untouched_field", section = "Empty Section"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer("infant_name", "Baby A")
    viewModel.setAnswer("delivery_place", "PHC Toranmal")

    val summary = viewModel.buildSummary(mediaCompletedLabel = "Completed", imageCapturedLabel = "Photo captured")

    assertEquals(listOf("Infant Details", "Delivery Details"), summary.map { it.title })
    assertEquals(listOf(SummaryRow(label = "infant_name", value = "Baby A")), summary.first { it.title == "Infant Details" }.rows)
  }

  @Test
  fun `buildSummary resolves a select field's coded answer to its option label`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(
        selectField(
          "delivery_type",
          options = listOf(FormFieldOption(label = "Normal", sortOrder = 0, valueCode = "normal")),
          section = "Delivery Details",
        ),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer("delivery_type", "normal")

    val summary = viewModel.buildSummary(mediaCompletedLabel = "Completed", imageCapturedLabel = "Photo captured")

    assertEquals("Normal", summary.first { it.title == "Delivery Details" }.rows.first().value)
  }

  /**
   * Regression test for the reported bug (2026-08-19): every geography dropdown on this screen
   * rendered with NO options at all, so even a correctly-prefilled answer (see the
   * "prefills the mother's own geography" test above) had nothing to resolve its label against and
   * looked exactly like the prefill itself had failed. [optionsFor] never had the
   * [GeographyFieldOptionsResolver.optionsFromVersionGeography] branch
   * [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel.optionsFor] already
   * has — this asserts a geography field now resolves against the active version's OWN `geography`
   * array rather than coming back empty.
   */
  @Test
  fun `optionsFor resolves a geography field's options from the active version's own geography array`() = runTest {
    deliverySessionRepository.save(session())
    formsRepository.version = versionWith(
      fields = listOf(textField(GeographyQuestionCodes.STATE)),
      geography = listOf(FormGeographyUnit(geographyUnitId = "state-1", geoType = "STATE", name = "Maharashtra")),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val options = viewModel.optionsFor(viewModel.visibleFields().first { it.questionCode == GeographyQuestionCodes.STATE })

    assertEquals(listOf("Maharashtra"), options.map { it.label })
    assertEquals(listOf("state-1"), options.map { it.valueCode })
  }
}
