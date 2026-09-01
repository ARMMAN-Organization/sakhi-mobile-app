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
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.FakeFormsApi
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormVisibleWhen
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
import org.junit.Assert.assertFalse
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
    var childRegistrationAnswersById: MutableMap<String, FormAnswers> = mutableMapOf()

    fun put(id: String, profile: BeneficiaryProfile) {
      profilesById[id] = profile
    }

    override suspend fun getBeneficiary(id: String): BeneficiaryProfile =
      profilesById[id] ?: throw NoSuchElementException("no beneficiary $id")

    override suspend fun getChildRegistrationAnswers(id: String): FormAnswers? = childRegistrationAnswersById[id]
  }

  private class FakeVisitFormRepository(
    private val context: VisitContext? = null,
  ) : VisitFormRepository {
    override suspend fun getVisitContext(beneficiaryId: String, visitId: String): VisitContext =
      context ?: throw NoSuchElementException("no context for test")

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
    override suspend fun lapseAllOpenVisits(localBeneficiaryId: String): Int = 0
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

  /** No cached risk pack, so [org.armman.sakhi.data.rules.GoRulesRiskAdapter] returns null
   * before reaching the evaluator — see the ctor wiring comment in buildViewModel(). */
  private class NoRuleCachedRuleSetRepository : org.armman.sakhi.data.rules.RuleSetRepository {
    override suspend fun getPublishedRuleSet(
      ruleSetId: String,
    ): org.armman.sakhi.data.rules.CachedRuleSet? = null

    override suspend fun prefetchRuleSets(ruleSetIds: List<String>) = Unit
  }

  /** Paired with [NoRuleCachedRuleSetRepository]: proves the adapter never reaches evaluation. */
  private class NeverCalledRuleEvaluator : org.armman.sakhi.data.rules.RuleEvaluator {
    override suspend fun evaluate(
      rulesJson: com.google.gson.JsonObject,
      context: com.google.gson.JsonObject,
    ): com.google.gson.JsonObject? = error("no risk pack cached — evaluate should never be reached")
  }

  /** Real [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource] over the same fakes
   * the beneficiary tests use; AncRiskRegistrationResolver degrades to a no-op when it finds no
   * local registration answers, which is exactly this test's situation. */
  private fun localEnrolmentBeneficiarySource() =
    org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource(
      org.armman.sakhi.data.forms.FakeDynamicFormDraftDao(),
      org.armman.sakhi.data.childregistration.FakeChildFormDraftDao(),
      FakeSecureKeyValueStore(),
      org.armman.sakhi.data.schedule.RoomVisitScheduleRepository(
        org.armman.sakhi.data.schedule.FakeVisitScheduleDao(),
      ),
      FakeFormsRepository(),
      org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore()),
    )

  /** Never exercised by the load()-only tests here — onFinish() is out of scope. */
  private class FakeVisitFormDraftRepository : VisitFormDraftRepository {
    override suspend fun submitDraft(
      localScheduleUuid: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      visitDate: java.time.LocalDate,
      riskResult: org.armman.sakhi.data.rules.RiskGradingResult?,
      referralCapture: org.armman.sakhi.data.referral.ReferralCapture?,
    ): VisitFormSubmitResult = throw NotImplementedError("not exercised by these tests")

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()
    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = MutableStateFlow(emptyList())
  }

  /** CR-Referral-01 Pass 4: records every [submitDraft] call (in particular the [referralCapture]
   * threaded through) so a test can assert what actually reached the repository, unlike
   * [FakeVisitFormDraftRepository] above which is only for tests that never call [onFinish]. */
  private class RecordingVisitFormDraftRepository : VisitFormDraftRepository {
    data class Call(
      val localScheduleUuid: String,
      val formCode: String,
      val riskResult: org.armman.sakhi.data.rules.RiskGradingResult?,
      val referralCapture: org.armman.sakhi.data.referral.ReferralCapture?,
    )

    val calls = mutableListOf<Call>()
    var result: VisitFormSubmitResult = VisitFormSubmitResult.Synced()

    override suspend fun submitDraft(
      localScheduleUuid: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      visitDate: java.time.LocalDate,
      riskResult: org.armman.sakhi.data.rules.RiskGradingResult?,
      referralCapture: org.armman.sakhi.data.referral.ReferralCapture?,
    ): VisitFormSubmitResult {
      calls += Call(localScheduleUuid, formCode, riskResult, referralCapture)
      return result
    }

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()
    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = MutableStateFlow(emptyList())
  }

  /** CR-M3-06: minimal fake — none of the existing tests in this file exercise health-education
   * content resolution (that's covered in [org.armman.sakhi.ui.healtheducation
   * .HealthEducationViewModelTest] instead), so this only needs to satisfy the constructor and
   * never actually be called by [DynamicVisitFormViewModel.load]/`onFinish`. */
  private class NeverCalledHealthEducationRepository : org.armman.sakhi.data.healtheducation.HealthEducationRepository {
    override suspend fun getEducationContentForBeneficiary(
      beneficiaryId: String,
      conditionCodes: Set<String>,
    ): Map<String, org.armman.sakhi.data.healtheducation.HealthEducationTopic> =
      throw UnsupportedOperationException("not used by DynamicVisitFormViewModelTest")

    override suspend fun getPlaceholderTopic(): org.armman.sakhi.data.healtheducation.HealthEducationTopic =
      throw UnsupportedOperationException("not used by DynamicVisitFormViewModelTest")
  }

  /** Pairs with [ScriptedRuleEvaluator] below: a single cached pack for whichever [ruleSetId] is
   * requested, so [org.armman.sakhi.data.rules.GoRulesRiskAdapter] always reaches evaluation
   * instead of short-circuiting the way [NoRuleCachedRuleSetRepository] deliberately does. */
  private class AlwaysCachedRuleSetRepository : org.armman.sakhi.data.rules.RuleSetRepository {
    override suspend fun getPublishedRuleSet(
      ruleSetId: String,
    ): org.armman.sakhi.data.rules.CachedRuleSet? = org.armman.sakhi.data.rules.CachedRuleSet(
      ruleSetId = ruleSetId,
      ruleVersionId = "rule-version-1",
      versionNo = "v1",
      rulesJson = com.google.gson.JsonObject(),
    )

    override suspend fun prefetchRuleSets(ruleSetIds: List<String>) = Unit
  }

  /** Returns a fixed on-device grading result regardless of the real input — [onFinish]'s
   * referral-trigger gating only cares about [org.armman.sakhi.data.rules.RiskConditionFinding
   * .isReferralTrigger], so the rest of the response shape is minimal but valid. */
  private class ScriptedRuleEvaluator(private val isReferralTrigger: Boolean) : org.armman.sakhi.data.rules.RuleEvaluator {
    override suspend fun evaluate(
      rulesJson: com.google.gson.JsonObject,
      context: com.google.gson.JsonObject,
    ): com.google.gson.JsonObject = com.google.gson.JsonObject().apply {
      addProperty("overallRiskCategory", if (isReferralTrigger) "HIGH" else "NORMAL")
      add(
        "conditions",
        com.google.gson.JsonArray().apply {
          add(
            com.google.gson.JsonObject().apply {
              addProperty("riskConditionId", "condition-1")
              addProperty("grade", if (isReferralTrigger) "SEVERE" else "NORMAL")
              addProperty("gradeRank", if (isReferralTrigger) 3 else 0)
              addProperty("isReferralTrigger", isReferralTrigger)
              addProperty("isEducationTrigger", false)
              addProperty("isHrVisitTrigger", false)
            },
          )
        },
      )
    }
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

  private fun versionWithFields(fields: List<FormFieldSchema>, validationJson: List<FormCrossFieldRule>) =
    infantVersion().copy(schemaJson = fields, validationJson = validationJson)

  private fun multiselectField(questionCode: String, vararg valueCodes: String) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "multiselect",
    questionCode = questionCode,
    options = valueCodes.mapIndexed { index, code ->
      FormFieldOption(label = code, sortOrder = index, valueCode = code)
    },
  )

  private fun dateField(questionCode: String) = FormFieldSchema(
    label = "Actual visit date",
    required = true,
    inputTypeRaw = "date",
    questionCode = questionCode,
  )

  private fun radioField(
    questionCode: String,
    label: String = questionCode,
    visibleWhen: FormVisibleWhen? = null,
  ) = FormFieldSchema(
    label = label,
    required = true,
    inputTypeRaw = "radio",
    questionCode = questionCode,
    options = listOf(
      FormFieldOption(label = "Yes", sortOrder = 0, valueCode = "yes"),
      FormFieldOption(label = "No", sortOrder = 1, valueCode = "no"),
    ),
    visibleWhen = visibleWhen,
  )

  private fun dropdownField(
    questionCode: String,
    vararg valueCodes: String,
    visibleWhen: FormVisibleWhen? = null,
  ) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "dropdown",
    questionCode = questionCode,
    options = valueCodes.mapIndexed { index, code -> FormFieldOption(label = code, sortOrder = index, valueCode = code) },
    visibleWhen = visibleWhen,
  )

  private fun textField(questionCode: String, visibleWhen: FormVisibleWhen? = null) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "text",
    questionCode = questionCode,
    visibleWhen = visibleWhen,
  )

  /** A reduced but structurally real REFERRAL_VISIT schema — same question codes/branching as the
   * live `GET /forms/REFERRAL_VISIT/active-version` payload (CR-Referral-01 Pass 6), just without
   * the 3 metadata fields [DynamicVisitFormViewModel.visibleReferralFields] hides anyway (no need
   * to fixture what the ViewModel never shows). */
  private fun referralVersion() = infantVersion().copy(
    schemaJson = listOf(
      radioField("referral_needed_new_condition"),
      radioField(
        "beneficiary_willing_for_referral",
        visibleWhen = FormVisibleWhen(field = "referral_needed_new_condition", value = "yes", operator = "eq"),
      ),
      dropdownField(
        "referral_declined_reason", "condition_not_serious_enough", "family_opposition",
        visibleWhen = FormVisibleWhen(field = "beneficiary_willing_for_referral", value = "no", operator = "eq"),
      ),
      radioField(
        "is_accompanied_referral",
        visibleWhen = FormVisibleWhen(field = "beneficiary_willing_for_referral", value = "yes", operator = "eq"),
      ),
      dateField("decided_visit_date").copy(
        visibleWhen = FormVisibleWhen(field = "beneficiary_willing_for_referral", value = "yes", operator = "eq"),
      ),
      dropdownField(
        "place_of_referral", "sc", "phc", "rh", "sdh", "dh", "private_clinic",
        visibleWhen = FormVisibleWhen(field = "beneficiary_willing_for_referral", value = "yes", operator = "eq"),
      ),
      textField(
        "health_facility_name",
        visibleWhen = FormVisibleWhen(field = "beneficiary_willing_for_referral", value = "yes", operator = "eq"),
      ),
    ),
  )

  private fun buildViewModel(
    beneficiaryId: String = "beneficiary-1",
    visitId: String = "visit-1",
    visitFormRepository: VisitFormRepository = FakeVisitFormRepository(),
    visitFormDraftRepository: VisitFormDraftRepository = FakeVisitFormDraftRepository(),
    // Risk grading is not exercised by the load()-only tests: NoRuleCachedRepository returns no
    // cached pack, so GoRulesRiskAdapter short-circuits before ever reaching the evaluator (which
    // throws if called). onFinish()-focused tests override this with AlwaysCachedRuleSetRepository
    // + ScriptedRuleEvaluator to control the referral-trigger outcome deterministically.
    goRulesRiskAdapter: org.armman.sakhi.data.rules.GoRulesRiskAdapter = org.armman.sakhi.data.rules.GoRulesRiskAdapter(
      NoRuleCachedRuleSetRepository(),
      NeverCalledRuleEvaluator(),
    ),
    healthEducationRepository: org.armman.sakhi.data.healtheducation.HealthEducationRepository = NeverCalledHealthEducationRepository(),
    // CR-Closure-03: a real HardcodedRuleSource-backed generator is fine as the default for every
    // existing test here -- none of them exercise PP5-triggers-closure directly (that's covered by
    // PpScheduleGeneratorTest's own unit tests of isClosurePromptTrigger itself), so this only
    // needs to be constructible, not scripted.
    ppScheduleGenerator: org.armman.sakhi.data.schedule.PpScheduleGenerator =
      org.armman.sakhi.data.schedule.PpScheduleGenerator(org.armman.sakhi.data.schedule.HardcodedRuleSource()),
  ) =
    DynamicVisitFormViewModel(
      formsRepository = formsRepository,
      visitFormRepository = visitFormRepository,
      beneficiaryProfileRepository = beneficiaryProfileRepository,
      visitFormDraftRepository = visitFormDraftRepository,
      visitScheduleRepository = visitScheduleRepository,
      visitCodeFormResolver = VisitCodeFormResolver(FakeFormsApi(), FakeSecureKeyValueStore()),
      formAuditRepository = formAuditRepository,
      deliverySessionRepository = deliverySessionRepository,
      deliveryFormDraftRepository = deliveryFormDraftRepository,
      goRulesRiskAdapter = goRulesRiskAdapter,
      ancRiskRegistrationResolver = org.armman.sakhi.data.visitform.AncRiskRegistrationResolver(
        localEnrolmentBeneficiarySource(),
      ),
      healthEducationRepository = healthEducationRepository,
      ppScheduleGenerator = ppScheduleGenerator,
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

  // --- Bug fix (2026-08-22): INC_VISIT/CCV_VISIT are their own form codes now (the backend's
  // live visit-code-form-map no longer aliases them to INFANT_VISIT), so every FORM_CODE_INFANT-
  // only behaviour (child-registration prefill, age_in_months computed field) must also fire for
  // them via FORM_CODES_INFANT_FAMILY — this is exactly the "age in months not auto-filled on
  // INC1" report. ---

  @Test
  fun `load() prefills child details and computes age_in_months for INC_VISIT`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "INC1",
      visitType = VisitCodeType.INC,
    )
    val dob = LocalDate.now().minusMonths(3)
    beneficiaryProfileRepository.childRegistrationAnswersById["beneficiary-1"] =
      FormAnswers().withSingleValue(ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT, dob.toString())
    formsRepository.version = versionWithFields(
      listOf(
        dateField("date_of_birth"),
        FormFieldSchema(
          label = "Age in months",
          required = false,
          inputTypeRaw = "number",
          questionCode = "age_in_months",
          computedFrom = "CHILD_AGE_MONTHS",
        ),
      ),
    )

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("INC_VISIT", viewModel.uiState.value.formCode)
    assertEquals(
      dob.toString(),
      viewModel.uiState.value.answers.valueOf("date_of_birth"),
    )
    assertEquals("3", viewModel.uiState.value.answers.valueOf("age_in_months"))
  }

  @Test
  fun `load() prefills child details for CCV_VISIT`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "CCV1",
      visitType = VisitCodeType.CCV,
    )
    val dob = LocalDate.now().minusMonths(6)
    beneficiaryProfileRepository.childRegistrationAnswersById["beneficiary-1"] =
      FormAnswers().withSingleValue(ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT, dob.toString())
    formsRepository.version = versionWithFields(
      listOf(dateField("date_of_birth")),
    )

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("CCV_VISIT", viewModel.uiState.value.formCode)
    assertEquals(
      dob.toString(),
      viewModel.uiState.value.answers.valueOf("date_of_birth"),
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

  // CR-Closure-03: PP5 is the SRS's mother-closure trigger point ("PP5 completion triggers the
  // mother closure prompt"). These two tests pin down the boundary using the real
  // HardcodedRuleSource (PP has 5 scheduled visits, so sequenceNo 5 is the last one) rather than a
  // literal "5" here, so a change to the PP schedule length can't silently desync this from
  // PpScheduleGenerator's own already-unit-tested isClosurePromptTrigger check.
  @Test
  fun `load() sets triggersClosurePrompt for PP5, the last scheduled postpartum visit`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "PP5",
      visitType = VisitCodeType.PP,
      sequenceNo = 5,
    )
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.ACTUAL_VISIT_DATE)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.triggersClosurePrompt)
  }

  @Test
  fun `load() does not set triggersClosurePrompt for an earlier postpartum visit`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "PP1",
      visitType = VisitCodeType.PP,
      sequenceNo = 1,
    )
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.ACTUAL_VISIT_DATE)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.triggersClosurePrompt)
  }

  @Test
  fun `load() does not set triggersClosurePrompt for a non-PP visit`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "ANC5",
      visitType = VisitCodeType.ANC,
      sequenceNo = 5,
    )
    // A version must resolve non-null or load() short-circuits into the hasError branch before
    // ever reaching the triggersClosurePrompt computation -- which would make this test pass
    // trivially (default false) without exercising the check at all.
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.DATE_OF_VISIT)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("ANC_VISIT", viewModel.uiState.value.formCode)
    assertFalse(viewModel.uiState.value.triggersClosurePrompt)
  }

  @Test
  fun `load() does not set triggersClosurePrompt when no schedule row is found`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    // visitScheduleRepository.scheduleByUuid left null -- load() falls back to the
    // beneficiary-type-only form resolution, and there is no schedule row to check at all.
    // See the sibling test's comment for why a resolvable version is required here too.
    formsRepository.version = versionWithFields(listOf(dateField(VisitFormQuestionCodes.DATE_OF_VISIT)))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.triggersClosurePrompt)
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

  // --- PP1 "Current BMI" auto-calculation (spec row 28: BMI = weight / height(m)^2) ---

  private fun postpartumContext(heightCm: Int?) = VisitContext(
    visitTypeLabel = "PP1",
    rchNumber = "",
    lmp = LocalDate.of(2026, 1, 1),
    heightCm = heightCm,
    previousHb = null,
    advisedDeliveryPlace = null,
    sickleCell = null,
    registrationWeightKg = null,
  )

  @Test
  fun `load() carries forward height and computes BMI on POSTPARTUM_VISIT`() {
    // Bug fix regression test (2026-08-21): before this fix, POSTPARTUM_VISIT had no dispatch
    // branch in recomputeDerivedFields() at all, and no height carry-forward of its own — "Current
    // BMI" silently stayed unset on every PP1 form. This exercises both fixes together.
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "PP1",
      visitType = VisitCodeType.PP,
    )
    formsRepository.version = versionWithFields(
      listOf(
        FormFieldSchema(
          label = "Current weight (kg)",
          required = true,
          inputTypeRaw = "number",
          questionCode = "current_weight_kg",
        ),
        FormFieldSchema(
          label = "Current BMI",
          required = true,
          inputTypeRaw = "number",
          questionCode = "current_bmi",
          computedFrom = "BMI",
        ),
      ),
    )

    val viewModel = buildViewModel(visitFormRepository = FakeVisitFormRepository(postpartumContext(heightCm = 160)))
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer("current_weight_kg", "64.0")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("160", viewModel.uiState.value.answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM))
    assertTrue(viewModel.uiState.value.heightLockedFromContext)
    assertEquals("25.0", viewModel.uiState.value.answers.valueOf("current_bmi"))
  }

  @Test
  fun `load() leaves BMI unset on POSTPARTUM_VISIT when no prior height is on file`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    visitScheduleRepository.scheduleByUuid = schedule(
      localScheduleUuid = "visit-1",
      localBeneficiaryId = "beneficiary-1",
      visitCode = "PP1",
      visitType = VisitCodeType.PP,
    )
    formsRepository.version = versionWithFields(
      listOf(
        FormFieldSchema(
          label = "Current BMI",
          required = true,
          inputTypeRaw = "number",
          questionCode = "current_bmi",
          computedFrom = "BMI",
        ),
      ),
    )

    val viewModel = buildViewModel(visitFormRepository = FakeVisitFormRepository(postpartumContext(heightCm = null)))
    testDispatcher.scheduler.advanceUntilIdle()

    assertNull(viewModel.uiState.value.answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM))
    assertNull(viewModel.uiState.value.answers.valueOf("current_bmi"))
  }

  // --- Submit gating on validationJson EXCLUSIVE_OPTION rules (see FormMultiSelectExclusivity's
  // doc — the checkbox-greying alone can't undo a conflicting answer that arrived some other way,
  // e.g. a legacy draft, so isReadyToSubmit() needs its own guard, same as every sibling dynamic
  // form ViewModel via crossFieldViolations()/FormCrossFieldValidator) ---

  @Test
  fun `isReadyToSubmit is false when an EXCLUSIVE_OPTION rule is violated`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = versionWithFields(
      listOf(multiselectField("urine_test", "normal", "infection", "sugar", "protein")),
      validationJson = listOf(
        FormCrossFieldRule(
          rule = "EXCLUSIVE_OPTION",
          fields = emptyList(),
          field = "urine_test",
          exclusiveValues = listOf("normal"),
        ),
      ),
    )

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setMultiAnswer("urine_test", listOf("normal", "infection"))
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isReadyToSubmit())
  }

  @Test
  fun `isReadyToSubmit is true once the EXCLUSIVE_OPTION conflict is resolved`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = versionWithFields(
      listOf(multiselectField("urine_test", "normal", "infection", "sugar", "protein")),
      validationJson = listOf(
        FormCrossFieldRule(
          rule = "EXCLUSIVE_OPTION",
          fields = emptyList(),
          field = "urine_test",
          exclusiveValues = listOf("normal"),
        ),
      ),
    )

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setMultiAnswer("urine_test", listOf("infection"))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isReadyToSubmit())
  }

  // --- CR-Referral-01 Pass 4: onFinish() on-device referral-trigger gating ---

  private fun fillReferralCaptureFields(viewModel: DynamicVisitFormViewModel) {
    // CR-Referral-01 Pass 6 (2026-08-31): walks the schema's "Yes -> Yes" branch (new condition,
    // willing to go) via the generic setReferralAnswer -- the only branch that ever produces a
    // ReferralCapture, see referralCaptureOrNull's doc. is_accompanied_referral = "no" maps to
    // ReferralType.STANDARD.
    viewModel.setReferralAnswer("referral_needed_new_condition", "yes")
    viewModel.setReferralAnswer("beneficiary_willing_for_referral", "yes")
    viewModel.setReferralAnswer("is_accompanied_referral", "no")
    viewModel.setReferralAnswer("decided_visit_date", LocalDate.now().toString())
    viewModel.setReferralAnswer("place_of_referral", "phc")
    viewModel.setReferralAnswer("health_facility_name", "Test PHC")
  }

  private fun triggeringGoRulesAdapter() = org.armman.sakhi.data.rules.GoRulesRiskAdapter(
    AlwaysCachedRuleSetRepository(),
    ScriptedRuleEvaluator(isReferralTrigger = true),
  )

  private fun nonTriggeringGoRulesAdapter() = org.armman.sakhi.data.rules.GoRulesRiskAdapter(
    AlwaysCachedRuleSetRepository(),
    ScriptedRuleEvaluator(isReferralTrigger = false),
  )

  @Test
  fun `onFinish sets triggersChildClosurePrompt when the submission reports no HR at the last CCV visit`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    val draftRepository = RecordingVisitFormDraftRepository()
    draftRepository.result = VisitFormSubmitResult.Synced(
      org.armman.sakhi.data.visitform.VisitSubmitOutcome(closureDeferredForExtension = false),
    )

    val viewModel = buildViewModel(visitFormDraftRepository = draftRepository)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.triggersChildClosurePrompt)
    assertNull(viewModel.uiState.value.ccvHrExtensionWindow)
  }

  @Test
  fun `onFinish sets ccvHrExtensionWindow when the submission reports HR detected at the last CCV visit`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    val draftRepository = RecordingVisitFormDraftRepository()
    val window = org.armman.sakhi.data.forms.ExtensionVisitWindowDto(
      scheduledDate = "2026-09-15",
      windowStartDate = "2026-09-10",
      windowEndDate = "2026-09-20",
    )
    draftRepository.result = VisitFormSubmitResult.Synced(
      org.armman.sakhi.data.visitform.VisitSubmitOutcome(
        closureDeferredForExtension = true,
        extensionVisit = window,
      ),
    )

    val viewModel = buildViewModel(visitFormDraftRepository = draftRepository)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.triggersChildClosurePrompt)
    assertEquals(window, viewModel.uiState.value.ccvHrExtensionWindow)
  }

  @Test
  fun `onFinish leaves both CCV routing fields unset for a non-boundary visit submission`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    val draftRepository = RecordingVisitFormDraftRepository()
    draftRepository.result = VisitFormSubmitResult.Synced()

    val viewModel = buildViewModel(visitFormDraftRepository = draftRepository)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.triggersChildClosurePrompt)
    assertNull(viewModel.uiState.value.ccvHrExtensionWindow)
  }

  @Test
  fun `onFinish shows the referral capture step and does not submit when the on-device result triggers a referral`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.showReferralCaptureStep)
    assertTrue("submitDraft must not be called on the first onFinish() that discovers a trigger", draftRepository.calls.isEmpty())
  }

  @Test
  fun `onFinish fetches the real REFERRAL_VISIT schema before showing the step`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    val referral = referralVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referral
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    // The exact regression this pass fixes: the step's fields must come from the live schema, not
    // a hand-copied lookalike -- confirmed here by checking the ViewModel actually holds the
    // fetched version and renders that version's fields, not some baked-in field list.
    assertEquals(referral, viewModel.uiState.value.referralFormVersion)
    // Only the ungated first question shows initially: every other field in the real REFERRAL_VISIT
    // schema is `visibleWhen`-gated behind referral_needed_new_condition /
    // beneficiary_willing_for_referral (see referralVersion()), and visibleReferralFields() honours
    // that the same way the main form's visibleFields() does. Asserting the whole schema list here
    // would be asserting that gating is BROKEN.
    assertEquals(
      listOf("referral_needed_new_condition"),
      viewModel.visibleReferralFields().map { it.questionCode },
    )

    // ...and the rest genuinely come from the fetched version once their branch opens, which is
    // what "from the live schema, not a baked-in list" actually means here.
    viewModel.setReferralAnswer("referral_needed_new_condition", "yes")
    viewModel.setReferralAnswer("beneficiary_willing_for_referral", "yes")
    assertEquals(
      referral.schemaJson.map { it.questionCode } - "referral_declined_reason",
      viewModel.visibleReferralFields().map { it.questionCode },
    )
  }

  @Test
  fun `onFinish does not show the referral capture step when the on-device result has no trigger`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = nonTriggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.uiState.value.showReferralCaptureStep)
    assertEquals(1, draftRepository.calls.size)
    assertNull(draftRepository.calls.single().referralCapture)
  }

  @Test
  fun `a second onFinish call after the referral capture step submits with the captured referral`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish() // first call: discovers the trigger, fetches the schema, shows the step
    testDispatcher.scheduler.advanceUntilIdle()
    fillReferralCaptureFields(viewModel)
    viewModel.onFinish() // second call: already past the trigger check, submits for real
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, draftRepository.calls.size)
    val capture = draftRepository.calls.single().referralCapture
    assertEquals("Test PHC", capture?.facilityName)
    assertEquals(org.armman.sakhi.data.referral.ReferralType.STANDARD, capture?.referralType)
    // place_of_referral's raw value_code is mapped into the 6 values POST /referrals actually
    // accepts (backend-confirmed live 2026-08-31 it rejects anything else) -- "phc" already IS one
    // of the 6, so it maps to itself uppercased. See referralCaptureOrNull's doc.
    assertEquals("PHC", capture?.facilityType)
  }

  @Test
  fun `skipReferralCapture clears the captured answers and submits with no referral`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()
    fillReferralCaptureFields(viewModel) // she started filling it in, then decides not to
    viewModel.skipReferralCapture()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, draftRepository.calls.size)
    assertNull(draftRepository.calls.single().referralCapture)
    assertNull(viewModel.uiState.value.referralAnswers.valueOf("referral_needed_new_condition"))
    assertNull(viewModel.uiState.value.referralAnswers.valueOf("health_facility_name"))
  }

  @Test
  fun `cancelReferralCapture hides the step and preserves whatever was already typed`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setReferralAnswer("health_facility_name", "Partly typed facility")
    viewModel.cancelReferralCapture()

    assertFalse(viewModel.uiState.value.showReferralCaptureStep)
    assertEquals("Partly typed facility", viewModel.uiState.value.referralAnswers.valueOf("health_facility_name"))
    assertTrue("cancelling must not submit anything", draftRepository.calls.isEmpty())
  }

  @Test
  fun `referral capture step ends with no referral when it is not a new condition`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setReferralAnswer("referral_needed_new_condition", "no")
    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, draftRepository.calls.size)
    assertNull(draftRepository.calls.single().referralCapture)
  }

  @Test
  fun `referral capture step ends with no referral when the beneficiary declines`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setReferralAnswer("referral_needed_new_condition", "yes")
    viewModel.setReferralAnswer("beneficiary_willing_for_referral", "no")
    viewModel.setReferralAnswer("referral_declined_reason", "family_opposition")
    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, draftRepository.calls.size)
    assertNull(draftRepository.calls.single().referralCapture)
    // The declined reason has no field on POST /referrals today (see ReferralCapture's doc) --
    // it's captured in UI state for her to see, but was never expected to be submitted anywhere.
    assertEquals("family_opposition", viewModel.uiState.value.referralAnswers.valueOf("referral_declined_reason"))
  }

  @Test
  fun `answering an earlier branch again resets whatever was filled in further down`() {
    beneficiaryProfileRepository.put("beneficiary-1", infantProfile())
    formsRepository.version = infantVersion()
    formsRepository.versionByFormCode["REFERRAL_VISIT"] = referralVersion()
    val draftRepository = RecordingVisitFormDraftRepository()

    val viewModel = buildViewModel(
      visitFormDraftRepository = draftRepository,
      goRulesRiskAdapter = triggeringGoRulesAdapter(),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onFinish()
    testDispatcher.scheduler.advanceUntilIdle()
    fillReferralCaptureFields(viewModel)
    viewModel.setReferralAnswer("referral_needed_new_condition", "no")

    val answers = viewModel.uiState.value.referralAnswers
    assertNull(answers.valueOf("beneficiary_willing_for_referral"))
    assertNull(answers.valueOf("is_accompanied_referral"))
    assertNull(answers.valueOf("decided_visit_date"))
    assertNull(answers.valueOf("place_of_referral"))
    assertNull(answers.valueOf("health_facility_name"))
  }
}
