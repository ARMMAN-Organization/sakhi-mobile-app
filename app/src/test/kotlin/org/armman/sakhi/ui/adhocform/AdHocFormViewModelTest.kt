package org.armman.sakhi.ui.adhocform

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.adhocform.AdHocFormDraftRepository
import org.armman.sakhi.data.adhocform.AdHocFormSubmitResult
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormDateRuleset
import java.time.LocalDate
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.visitform.FakeReferralRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [AdHocFormViewModel] — mirrors [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModelTest]'s
 * CR-035 audit-trail coverage, plus [AdHocFormViewModel.isReadyToSubmit]'s required-field and
 * cross-field gating (mirroring [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel
 * .isReadyToSubmit]'s contract).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdHocFormViewModelTest {

  /** Never exercised by these tests — submission is out of scope here. */
  private class FakeAdHocFormDraftRepository : AdHocFormDraftRepository {
    /** Configurable per test — see `referral_visit_name is auto-numbered from past local referrals`. */
    var referralCount: Int = 0

    override suspend fun submitDraft(
      localFormInstanceUuid: String,
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      referralId: String?,
      capturedImagePaths: Map<String, String>,
    ): AdHocFormSubmitResult = throw NotImplementedError("not exercised by these tests")

    override suspend fun countByFormCode(localBeneficiaryId: String, formCode: String): Int = referralCount

    override fun observeUploadRecords(): kotlinx.coroutines.flow.Flow<List<org.armman.sakhi.data.forms.FormUploadRecord>> =
      kotlinx.coroutines.flow.flowOf(emptyList())
  }

  /** Mirrors [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModelTest]'s own fake — a missing
   * id throws, matching [BeneficiaryProfileRepository]'s real contract; a registered one returns
   * whatever profile the test configured (usually just to exercise
   * [AdHocFormViewModel.loadBeneficiaryRegistrationDate]). */
  private class FakeBeneficiaryProfileRepository(
    private val profilesById: MutableMap<String, BeneficiaryProfile> = mutableMapOf(),
  ) : BeneficiaryProfileRepository {
    fun put(id: String, profile: BeneficiaryProfile) {
      profilesById[id] = profile
    }

    override suspend fun getBeneficiary(id: String): BeneficiaryProfile =
      profilesById[id] ?: throw NoSuchElementException("no beneficiary $id")
  }

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var formsRepository: FakeFormsRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var draftRepository: FakeAdHocFormDraftRepository
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var beneficiaryProfileRepository: FakeBeneficiaryProfileRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    formsRepository = FakeFormsRepository()
    lookupRepository = FakeLookupRepository(valuesByCategory = mutableMapOf())
    draftRepository = FakeAdHocFormDraftRepository()
    formAuditRepository = FakeFormAuditRepository()
    beneficiaryProfileRepository = FakeBeneficiaryProfileRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun requiredTextField(questionCode: String) = FormFieldSchema(
    label = questionCode,
    required = true,
    inputTypeRaw = "text",
    questionCode = questionCode,
  )

  private fun versionWith(
    fields: List<FormFieldSchema> = emptyList(),
    rules: List<FormCrossFieldRule> = emptyList(),
  ) = FormVersion(
    id = "version-1",
    formDefinitionId = "definition-1",
    versionNo = "v1",
    schemaJson = fields,
    validationJson = rules,
    effectiveFrom = "2026-01-01",
    effectiveTo = null,
    status = "PUBLISHED",
    geography = null,
  )

  private val referralRepository = FakeReferralRepository()

  private fun buildViewModel(
    beneficiaryId: String = "beneficiary-1",
    formCode: String = "REFERRAL_VISIT",
    visitName: String = "",
    forced: Boolean = false,
    referralId: String = "",
  ) =
    AdHocFormViewModel(
      formsRepository = formsRepository,
      lookupRepository = lookupRepository,
      adHocFormDraftRepository = draftRepository,
      formAuditRepository = formAuditRepository,
      beneficiaryProfileRepository = beneficiaryProfileRepository,
      referralRepository = referralRepository,
      savedStateHandle = SavedStateHandle(
        mapOf(
          "beneficiaryId" to beneficiaryId,
          "formCode" to formCode,
          "visitName" to visitName,
          "forced" to forced,
          "referralId" to referralId,
        ),
      ),
    )

  // CR-Closure-03: the PP5-triggered forced mother-closure prompt reads this flag to suppress its
  // own back affordance (see AdHocFormScreen) -- covering just the nav-arg readback here, since the
  // actual back-suppression is a Compose-layer concern this JVM test suite doesn't exercise.
  @Test
  fun `forced defaults to false when the nav arg is absent`() {
    formsRepository.version = versionWith()
    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.forced)
  }

  @Test
  fun `forced is true when the nav arg is set`() {
    formsRepository.version = versionWith()
    val viewModel = buildViewModel(formCode = "ANC_CLOSURE_VISIT", forced = true)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.forced)
  }

  @Test
  fun `load() success writes an OPENED audit event exactly once`() {
    formsRepository.version = versionWith()

    buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(FormAuditEventType.OPENED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("REFERRAL_VISIT", formAuditRepository.recordedEvents.single().formCode)
  }

  @Test
  fun `load() failure (schema fetch returns null) does not write an OPENED event`() {
    formsRepository.version = null

    buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(formAuditRepository.recordedEvents.isEmpty())
  }

  @Test
  fun `calling load() again writes a second OPENED event`() {
    formsRepository.version = versionWith()

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.load()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(FormAuditEventType.OPENED, FormAuditEventType.OPENED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
  }

  @Test
  fun `isReadyToSubmit() is false when a required field is blank`() {
    formsRepository.version = versionWith(fields = listOf(requiredTextField("referral_facility")))

    val viewModel = buildViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isReadyToSubmit())
  }

  @Test
  fun `isReadyToSubmit() is false when an ANY_OF_REQUIRED rule is violated`() {
    // Mirrors the Referral Follow-up form's real cross-field rule: neither field required on its
    // own, but at least one of the two must be answered.
    val fields = listOf(
      requiredTextField("outcome_notes").copy(required = false),
      requiredTextField("next_followup_date").copy(required = false),
    )
    val rule = FormCrossFieldRule(rule = "ANY_OF_REQUIRED", fields = listOf("outcome_notes", "next_followup_date"))
    formsRepository.version = versionWith(fields = fields, rules = listOf(rule))

    val viewModel = buildViewModel(formCode = "REFERRAL_FOLLOWUP_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isReadyToSubmit())
    assertEquals(listOf(rule), viewModel.crossFieldViolations())
  }

  @Test
  fun `isReadyToSubmit() is true when all required fields are answered and no cross-field rule is violated`() {
    val fields = listOf(
      requiredTextField("outcome_notes").copy(required = false),
      requiredTextField("next_followup_date").copy(required = false),
    )
    val rule = FormCrossFieldRule(rule = "ANY_OF_REQUIRED", fields = listOf("outcome_notes", "next_followup_date"))
    formsRepository.version = versionWith(fields = fields, rules = listOf(rule))

    val viewModel = buildViewModel(formCode = "REFERRAL_FOLLOWUP_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer("outcome_notes", "Referred to PHC")

    assertTrue(viewModel.isReadyToSubmit())
  }

  // --- Closure visit date prefill / beneficiary registration date (bharath, 2026-08-18) --------

  @Test
  fun `visit_name is prefilled from the visit picked on the profile screen`() {
    val visitNameField = FormFieldSchema(
      label = "Visit name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "visit_name",
    )
    formsRepository.version = versionWith(fields = listOf(visitNameField))

    val viewModel = buildViewModel(formCode = "REFERRAL_VISIT", visitName = "ANC 3")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("ANC 3", viewModel.uiState.value.answers.valueOf("visit_name"))
  }

  @Test
  fun `visit_name stays blank when no visit was picked`() {
    val visitNameField = FormFieldSchema(
      label = "Visit name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "visit_name",
    )
    formsRepository.version = versionWith(fields = listOf(visitNameField))

    val viewModel = buildViewModel(formCode = "REFERRAL_VISIT", visitName = "")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(null, viewModel.uiState.value.answers.valueOf("visit_name"))
  }

  @Test
  fun `referral_visit_name is auto-numbered from past local referrals on this device`() {
    val referralVisitNameField = FormFieldSchema(
      label = "Referral visit name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "referral_visit_name",
    )
    formsRepository.version = versionWith(fields = listOf(referralVisitNameField))
    draftRepository.referralCount = 2

    val viewModel = buildViewModel(formCode = "REFERRAL_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("RV3", viewModel.uiState.value.answers.valueOf("referral_visit_name"))
  }

  @Test
  fun `referral_followup_visit_name is auto-numbered from past local follow-ups on this device`() {
    val followupVisitNameField = FormFieldSchema(
      label = "Referral followup visit name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "referral_followup_visit_name",
    )
    formsRepository.version = versionWith(fields = listOf(followupVisitNameField))
    draftRepository.referralCount = 1

    val viewModel = buildViewModel(formCode = "REFERRAL_FOLLOWUP_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("RFU2", viewModel.uiState.value.answers.valueOf("referral_followup_visit_name"))
  }

  @Test
  fun `referral_visit_name on REFERRAL_FOLLOWUP_VISIT is autopopulated from the parent referral, not RV-numbered`() {
    // Regression: REFERRAL_FOLLOWUP_VISIT's own schema carries referral_visit_name too (its row 2,
    // "Autopopulate the referral form linked to this visit") -- before this fix it collided with
    // REFERRAL_VISIT's row-3 auto-numbering and got wrongly assigned "RV<count>" instead.
    val referralVisitNameField = FormFieldSchema(
      label = "Referral visit name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "referral_visit_name",
    )
    formsRepository.version = versionWith(fields = listOf(referralVisitNameField))
    draftRepository.referralCount = 5 // would produce "RV6" if the old bug were still present
    referralRepository.cachedReferralVisitName = "RV1"

    val viewModel = buildViewModel(formCode = "REFERRAL_FOLLOWUP_VISIT", referralId = "referral-1")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("RV1", viewModel.uiState.value.answers.valueOf("referral_visit_name"))
  }

  @Test
  fun `referral_visit_name on REFERRAL_FOLLOWUP_VISIT stays blank when nothing was cached for the referral`() {
    val referralVisitNameField = FormFieldSchema(
      label = "Referral visit name",
      required = true,
      inputTypeRaw = "text",
      questionCode = "referral_visit_name",
    )
    formsRepository.version = versionWith(fields = listOf(referralVisitNameField))
    referralRepository.cachedReferralVisitName = null

    val viewModel = buildViewModel(formCode = "REFERRAL_FOLLOWUP_VISIT", referralId = "referral-1")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(null, viewModel.uiState.value.answers.valueOf("referral_visit_name"))
  }

  @Test
  fun `followup form_filled_date is prefilled with today when the schema carries it and it's blank`() {
    val filledDateField = FormFieldSchema(
      label = "Date of the form filled",
      required = true,
      inputTypeRaw = "date",
      questionCode = FormDateRuleset.FOLLOWUP_FORM_FILLED_DATE_QUESTION_CODE,
    )
    formsRepository.version = versionWith(fields = listOf(filledDateField))

    val viewModel = buildViewModel(formCode = "REFERRAL_FOLLOWUP_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(FormDateRuleset.FOLLOWUP_FORM_FILLED_DATE_QUESTION_CODE),
    )
  }

  @Test
  fun `referral_form_filled_date is prefilled with today when the schema carries it and it's blank`() {
    val filledDateField = FormFieldSchema(
      label = "Referral visit form filled date",
      required = true,
      inputTypeRaw = "date",
      questionCode = FormDateRuleset.REFERRAL_FORM_FILLED_DATE_QUESTION_CODE,
    )
    formsRepository.version = versionWith(fields = listOf(filledDateField))

    val viewModel = buildViewModel(formCode = "REFERRAL_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(FormDateRuleset.REFERRAL_FORM_FILLED_DATE_QUESTION_CODE),
    )
  }

  @Test
  fun `closure_visit_date is prefilled with today when the schema carries it and it's blank`() {
    val closureDateField = FormFieldSchema(
      label = "Closure visit date",
      required = true,
      inputTypeRaw = "date",
      questionCode = FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE,
    )
    formsRepository.version = versionWith(fields = listOf(closureDateField))

    val viewModel = buildViewModel(formCode = "ANC_CLOSURE_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      LocalDate.now().toString(),
      viewModel.uiState.value.answers.valueOf(FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE),
    )
  }

  @Test
  fun `closure_visit_date prefill leaves an already-answered field alone`() {
    // AdHocFormViewModel has no draft-restore step yet (see the class doc's tradeoff note), so the
    // only way to get an answer into state before the prefill runs is the Sakhi setting it herself
    // — this covers that guard directly rather than via a full load() (which resets all of
    // AdHocFormUiState, including answers, so it can't exercise "don't clobber an existing value").
    val closureDateField = FormFieldSchema(
      label = "Closure visit date",
      required = true,
      inputTypeRaw = "date",
      questionCode = FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE,
    )
    formsRepository.version = versionWith(fields = listOf(closureDateField))

    val viewModel = buildViewModel(formCode = "ANC_CLOSURE_VISIT")
    testDispatcher.scheduler.advanceUntilIdle()
    val editedDate = LocalDate.now().minusDays(2).toString()
    viewModel.setAnswer(FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE, editedDate)

    assertEquals(editedDate, viewModel.uiState.value.answers.valueOf(FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE))
  }

  @Test
  fun `beneficiaryRegistrationDate loads from the beneficiary's profile`() {
    val registrationDate = LocalDate.of(2026, 5, 1)
    beneficiaryProfileRepository.put(
      "beneficiary-1",
      BeneficiaryProfile(
        id = "beneficiary-1",
        name = "Test Beneficiary",
        type = org.armman.sakhi.data.beneficiary.BeneficiaryType.MOTHER,
        ageLabel = "25",
        village = "Village",
        pada = "Pada",
        husbandName = "",
        mobileNumber = "",
        status = org.armman.sakhi.data.beneficiary.BeneficiaryStatus.ACTIVE,
        riskLevel = org.armman.sakhi.data.beneficiary.RiskLevel.LOW,
        registrationDate = registrationDate,
      ),
    )
    formsRepository.version = versionWith()

    val viewModel = buildViewModel(beneficiaryId = "beneficiary-1")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(registrationDate, viewModel.uiState.value.beneficiaryRegistrationDate)
  }

  @Test
  fun `beneficiaryRegistrationDate stays null when the beneficiary lookup fails`() {
    // No profile registered for "beneficiary-1" — FakeBeneficiaryProfileRepository throws, and
    // AdHocFormViewModel must swallow that (best-effort) rather than crash the whole form load.
    formsRepository.version = versionWith()

    val viewModel = buildViewModel(beneficiaryId = "beneficiary-1")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(null, viewModel.uiState.value.beneficiaryRegistrationDate)
  }
}
