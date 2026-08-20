package org.armman.sakhi.ui.visitform

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliveryFormSubmitResult
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.forms.FakeFormsApi
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.VisitCodeFormResolver
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.schedule
import org.armman.sakhi.data.visitform.VisitContext
import org.armman.sakhi.data.visitform.VisitFormDraftRepository
import org.armman.sakhi.data.visitform.VisitFormQuestionCodes
import org.armman.sakhi.data.visitform.VisitFormRepository
import org.armman.sakhi.data.visitform.VisitFormSubmitResult
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers CR-035's audit-trail wiring on [DynamicVisitFormViewModel.load] only — this ViewModel had
 * no tests at all before this pass, and full coverage is out of scope here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicVisitFormViewModelTest {

  private class FakeBeneficiaryProfileRepository(
    private val profilesById: MutableMap<String, BeneficiaryProfile> = mutableMapOf(),
  ) : BeneficiaryProfileRepository {
    fun put(id: String, profile: BeneficiaryProfile) {
      profilesById[id] = profile
    }

    override suspend fun getBeneficiary(id: String): BeneficiaryProfile =
      profilesById[id] ?: throw NoSuchElementException("no beneficiary $id")
  }

  private class FakeVisitFormRepository : VisitFormRepository {
    override suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext =
      throw NoSuchElementException("no context for test")

    override suspend fun canStartVisit(beneficiaryId: String): Boolean = true
  }

  /** [scheduleByUuid] lets a test route [load] to POSTPARTUM_VISIT/NEONATAL_VISIT (via
   * [VisitCodeFormResolver.resolve]'s `visitType` lookup) instead of the beneficiary-type-only
   * fallback, which only ever produces ANC_VISIT/INFANT_VISIT. Null (the old always-null stub)
   * keeps every existing test on that fallback path unchanged. */
  private class FakeVisitScheduleRepository : VisitScheduleRepository {
    var scheduleByUuid: VisitScheduleEntity? = null
    override suspend fun saveGenerated(schedules: List<VisitScheduleEntity>) {}
    override suspend fun getForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity> = emptyList()
    override suspend fun getByLocalScheduleUuid(localScheduleUuid: String): VisitScheduleEntity? = scheduleByUuid
    override suspend fun getActiveForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity> = emptyList()
    override fun observeActiveForBeneficiary(localBeneficiaryId: String): Flow<List<VisitScheduleEntity>> =
      MutableStateFlow(emptyList())
    override suspend fun getOpenByType(
      localBeneficiaryId: String,
      visitType: VisitCodeType,
    ): List<VisitScheduleEntity> = emptyList()
    override suspend fun hasSchedule(localBeneficiaryId: String): Boolean = false
    override suspend fun hasScheduleOfType(localBeneficiaryId: String, visitType: VisitCodeType): Boolean = false
    override suspend fun getUnsynced(): List<VisitScheduleEntity> = emptyList()
    override fun observeUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
    override suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String) {}
    override suspend fun attachServerBeneficiaryId(localBeneficiaryId: String, serverBeneficiaryId: String) {}
    override suspend fun updateStatus(
      localScheduleUuid: String,
      status: VisitScheduleStatus,
      reasonCode: String?,
    ) {}
    override suspend fun lapseOpenAncVisits(localBeneficiaryId: String): Int = 0
    override suspend fun supersedeOpenVisits(localBeneficiaryId: String): Int = 0
  }

  /** Always a no-op ("no delivery session yet"), same convention as
   * [FakeVisitFormRepository]/[FakeVisitFormDraftRepository] below — the visit-date-prefill tests
   * that route through NEONATAL_VISIT only care that [prefillDefaultVisitDate] ran, not about
   * [org.armman.sakhi.data.delivery.DeliveryToNeonatalPrefill]'s own separate prefill. */
  private class FakeDeliverySessionRepository : DeliverySessionRepository {
    override suspend fun save(session: DeliverySessionEntity) {}
    override suspend fun getBySessionUuid(localSessionUuid: String): DeliverySessionEntity? = null
    override suspend fun getActiveForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity? = null
    override suspend fun getMostRecentForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity? = null
  }

  /** Never exercised by the load()-only tests here — [DeliverySessionRepository] always reporting
   * no session means [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.prefillFromDeliveryVisit]
   * returns before ever calling [getAnswers]. */
  private class FakeDeliveryFormDraftRepository : DeliveryFormDraftRepository {
    override suspend fun submitDraft(
      localSubmissionUuid: String,
      localSessionUuid: String,
      localBeneficiaryId: String,
      formVersionId: String,
      answers: FormAnswers,
      deliveryDate: LocalDate,
      deliveryFormFilledOn: LocalDate,
    ): DeliveryFormSubmitResult = throw NotImplementedError("not exercised by these tests")

    override suspend fun getAnswers(localSubmissionUuid: String): FormAnswers? = null
  }

  /** Never exercised by the load()-only tests here — onFinish() is out of scope. */
  private class FakeVisitFormDraftRepository : VisitFormDraftRepository {
    override suspend fun submitDraft(
      localScheduleUuid: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      visitDate: java.time.LocalDate,
    ): VisitFormSubmitResult = throw NotImplementedError("not exercised by these tests")

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()
    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = MutableStateFlow(emptyList())
  }

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var formsRepository: FakeFormsRepository
  private lateinit var beneficiaryProfileRepository: FakeBeneficiaryProfileRepository
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var visitScheduleRepository: FakeVisitScheduleRepository
  private lateinit var deliverySessionRepository: FakeDeliverySessionRepository
  private lateinit var deliveryFormDraftRepository: FakeDeliveryFormDraftRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    formsRepository = FakeFormsRepository()
    beneficiaryProfileRepository = FakeBeneficiaryProfileRepository()
    formAuditRepository = FakeFormAuditRepository()
    visitScheduleRepository = FakeVisitScheduleRepository()
    deliverySessionRepository = FakeDeliverySessionRepository()
    deliveryFormDraftRepository = FakeDeliveryFormDraftRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun infantProfile() = BeneficiaryProfile(
    id = "beneficiary-1",
    name = "Test Infant",
    type = BeneficiaryType.INFANT,
    ageLabel = "2 mo",
    village = "Village",
    pada = "Pada",
    husbandName = "",
    mobileNumber = "9999999999",
    status = BeneficiaryStatus.ACTIVE,
    riskLevel = RiskLevel.LOW,
  )

  private fun infantVersion() = FormVersion(
    id = "version-1",
    formDefinitionId = "definition-1",
    versionNo = "v1",
    schemaJson = emptyList(),
    validationJson = emptyList(),
    effectiveFrom = "2026-01-01",
    effectiveTo = null,
    status = "PUBLISHED",
    geography = null,
  )

  private fun versionWithFields(fields: List<FormFieldSchema>) = infantVersion().copy(schemaJson = fields)

  private fun dateField(questionCode: String) = FormFieldSchema(
    label = "Actual visit date",
    required = true,
    inputTypeRaw = "date",
    questionCode = questionCode,
  )

  private fun buildViewModel(beneficiaryId: String = "beneficiary-1", visitId: String = "visit-1") =
    DynamicVisitFormViewModel(
      formsRepository = formsRepository,
      visitFormRepository = FakeVisitFormRepository(),
      beneficiaryProfileRepository = beneficiaryProfileRepository,
      visitFormDraftRepository = FakeVisitFormDraftRepository(),
      visitScheduleRepository = visitScheduleRepository,
      visitCodeFormResolver = VisitCodeFormResolver(FakeFormsApi(), FakeSecureKeyValueStore()),
      formAuditRepository = formAuditRepository,
      deliverySessionRepository = deliverySessionRepository,
      deliveryFormDraftRepository = deliveryFormDraftRepository,
      savedStateHandle = SavedStateHandle(
        mapOf(
          "beneficiaryId" to beneficiaryId,
          "visitId" to visitId,
          "label" to "Visit 1",
        ),
      ),
    )

  @Test
  fun `load() success writes an OPENED audit event exactly once`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()

    buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(FormAuditEventType.OPENED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("visit-1", formAuditRepository.recordedEvents.single().subjectId)
    assertEquals("INFANT_VISIT", formAuditRepository.recordedEvents.single().formCode)
  }

  @Test
  fun `load() failure (blank ids) does not write an OPENED event`() {
    formsRepository.version = infantVersion()

    buildViewModel(beneficiaryId = "", visitId = "")
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(formAuditRepository.recordedEvents.isEmpty())
  }

  @Test
  fun `load() failure (beneficiary not found) does not write an OPENED event`() {
    // Nothing put into beneficiaryProfileRepository — getBeneficiary throws NoSuchElementException.
    formsRepository.version = infantVersion()

    buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(formAuditRepository.recordedEvents.isEmpty())
  }

  @Test
  fun `load() failure (schema-version fetch returns null) does not write an OPENED event`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    // FakeFormsRepository.version defaults to null and geography defaults to empty, so
    // getActiveVersion() returns null — mirrors a formCode with no published schema yet.
    formsRepository.version = null

    buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(formAuditRepository.recordedEvents.isEmpty())
  }

  @Test
  fun `calling load() again on the same visit writes a second OPENED event`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.load()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(FormAuditEventType.OPENED, FormAuditEventType.OPENED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
  }

  // --- Visit-date auto-fill (spec: "Actual visit date... Should automatically select today's
  // date", Postpartum I-IV and Neonate Visit 1 & 2, plus the pre-existing ANC/INFANT behaviour) ---

  @Test
  fun `load() auto-fills date_of_visit with today for INFANT_VISIT`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.DATE_OF_VISIT)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("INFANT_VISIT", viewModel.uiState.value.formCode)
    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(VisitFormQuestionCodes.DATE_OF_VISIT),
    )
  }

  @Test
  fun `load() auto-fills actual_visit_date with today for POSTPARTUM_VISIT`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "PP1",
      visitType = VisitCodeType.PP,
    )
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.ACTUAL_VISIT_DATE)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("POSTPARTUM_VISIT", viewModel.uiState.value.formCode)
    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(VisitFormQuestionCodes.ACTUAL_VISIT_DATE),
    )
  }

  @Test
  fun `load() auto-fills actual_visit_date with today for NEONATAL_VISIT`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "NN1",
      visitType = VisitCodeType.NN,
    )
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.ACTUAL_VISIT_DATE)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("NEONATAL_VISIT", viewModel.uiState.value.formCode)
    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(VisitFormQuestionCodes.ACTUAL_VISIT_DATE),
    )
  }

  @Test
  fun `load() only writes the visit-date code the active schema actually declares`() {
    // Schema declares ONLY actual_visit_date (POSTPARTUM_VISIT/NEONATAL_VISIT's spelling) — the
    // fix must not also stamp date_of_visit, a question_code this schema never asked for.
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "PP1",
      visitType = VisitCodeType.PP,
    )
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.ACTUAL_VISIT_DATE)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertNull(viewModel.uiState.value.answers.valueOf(VisitFormQuestionCodes.DATE_OF_VISIT))
  }
}
