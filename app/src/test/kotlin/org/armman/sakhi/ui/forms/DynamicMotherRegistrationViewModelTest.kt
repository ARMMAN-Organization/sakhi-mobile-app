package org.armman.sakhi.ui.forms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.forms.DOB_QUESTION_CODE
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.forms.LMP_DATE_QUESTION_CODE
import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODE
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.geography.GeographyRepository
import org.armman.sakhi.data.geography.GeographyUnit
import org.armman.sakhi.data.geography.SakhiAssignment
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Covers the CR-018 follow-up fixes: `beneficiary_id` must never render as an input (it doesn't
 * exist until the server assigns it post-submission), `computedFrom` fields must never block
 * [DynamicMotherRegistrationViewModel.isReadyToSubmit] even when their value is still unconfirmed
 * (e.g. `unique_id`), and the schema's `section` key (v6) must group into tabs correctly,
 * including a safe fallback for any field missing one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicMotherRegistrationViewModelTest {

  private class FakeFormsRepository(private val version: FormVersion?) : FormsRepository {
    override suspend fun getActiveVersion(formCode: String): FormVersion? = version
  }

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

  private class FakeLookupRepository : LookupRepository {
    override suspend fun getValues(categoryCode: String): List<LookupValue> = emptyList()
  }

  private class FakeDraftRepository(
    private val submitResult: DynamicFormSubmitResult = DynamicFormSubmitResult.Synced,
  ) : DynamicFormDraftRepository {
    var submitCallCount = 0

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
    ): DynamicFormSubmitResult {
      submitCallCount++
      return submitResult
    }

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = flowOf(emptyList())
  }

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun field(
    questionCode: String,
    section: String? = null,
    required: Boolean = true,
    inputType: String = "text",
    computedFrom: String? = null,
  ) = FormFieldSchema(
    label = questionCode,
    required = required,
    inputTypeRaw = inputType,
    questionCode = questionCode,
    computedFrom = computedFrom,
    section = section,
  )

  private fun viewModel(
    fields: List<FormFieldSchema>,
    draftRepository: FakeDraftRepository = FakeDraftRepository(),
    geography: List<FormGeographyUnit>? = null,
    projectName: String? = null,
  ): DynamicMotherRegistrationViewModel {
    val version = FormVersion(
      id = "v6",
      formDefinitionId = "def-1",
      versionNo = "v6",
      schemaJson = fields,
      validationJson = emptyList(),
      effectiveFrom = "2026-07-21T00:00:00Z",
      effectiveTo = null,
      status = "PUBLISHED",
      geography = geography,
    )
    val vm = DynamicMotherRegistrationViewModel(
      FakeFormsRepository(version),
      FakeLookupRepository(),
      GeographyFieldOptionsResolver(FakeGeographyRepository(), fakeCurrentUserRepository(projectName)),
      draftRepository,
    )
    dispatcher.scheduler.advanceUntilIdle()
    return vm
  }

  // [projectName] backs the `project_name` field's single option; null (the default) mirrors a
  // profile that hasn't loaded, exercising the resolver's empty fallback.
  private fun fakeCurrentUserRepository(projectName: String? = null) =
    object : org.armman.sakhi.data.auth.CurrentUserRepository {
      override suspend fun getProfile() =
        projectName?.let {
          org.armman.sakhi.data.auth.CurrentUserProfile(
            username = "test.sakhi",
            displayName = "Test Sakhi",
            mobileNumber = null,
            projectName = it,
            cardNumber = null,
            maskedBankAccount = null,
          )
        }
      override fun clear() = Unit
      override fun clearIfDifferentUser(username: String) = Unit
    }

  @Test
  fun `beneficiary_id is never in visibleFields`() = runTest {
    val vm = viewModel(
      listOf(
        field("beneficiary_id", section = "Personal Info"),
        field("mobile_number", section = "Personal Info"),
      ),
    )

    val codes = vm.visibleFields().map { it.questionCode }

    assertFalse(codes.contains("beneficiary_id"))
    assertTrue(codes.contains("mobile_number"))
  }

  @Test
  fun `sections group fields in schema order and hide beneficiary_id from its section`() = runTest {
    val vm = viewModel(
      listOf(
        field("did_we_receive_consent", section = "Consent"),
        field("beneficiary_id", section = "Personal Info"),
        field("mobile_number", section = "Personal Info"),
        field("gravida_total_number_of_pregnancies", section = "Health History"),
      ),
    )

    assertEquals(listOf("Consent", "Personal Info", "Health History"), vm.sections())
    assertEquals(
      listOf("mobile_number"),
      vm.fieldsInSection("Personal Info").map { it.questionCode },
    )
  }

  @Test
  fun `fields with no section fall back to Additional Information`() = runTest {
    val vm = viewModel(listOf(field("some_new_field", section = null)))

    assertEquals(listOf(FALLBACK_SECTION), vm.sections())
    assertEquals(
      listOf("some_new_field"),
      vm.fieldsInSection(FALLBACK_SECTION).map { it.questionCode },
    )
  }

  @Test
  fun `age_years auto-fills from DOB even though the schema doesn't declare computedFrom`() = runTest {
    val vm = viewModel(
      listOf(
        field("date_of_birth", section = "Personal Info", inputType = "date"),
        field("age_years", section = "Personal Info", required = false, inputType = "number"),
      ),
    )

    vm.setAnswer("date_of_birth", "2009-07-22")
    dispatcher.scheduler.advanceUntilIdle()

    // Whole years between the DOB and this ViewModel's own registrationDate — computed the same
    // way the production code does, rather than hardcoding a number that would silently drift if
    // the test's "today" changes.
    val expectedAge = java.time.temporal.ChronoUnit.YEARS.between(
      java.time.LocalDate.of(2009, 7, 22),
      vm.registrationDate,
    ).toString()
    assertEquals(expectedAge, vm.uiState.value.answers.valueOf("age_years"))
  }

  // One backend geography unit per level, mirroring a real `active-version` `geography` array.
  private fun sampleGeography() = listOf(
    FormGeographyUnit("state-uuid", "STATE", "Maharashtra"),
    FormGeographyUnit("district-uuid", "DISTRICT", "Nandurbar"),
    FormGeographyUnit("block-uuid", "BLOCK", "Dhadgaon"),
    FormGeographyUnit("village-uuid", "VILLAGE", "Sample Village"),
    FormGeographyUnit("pada-uuid", "PADA", "Sample Pada"),
    FormGeographyUnit("phc-uuid", "PHC", "Dhadgaon PHC"),
    FormGeographyUnit("subcentre-uuid", "SUBCENTRE", "Sample Sub-centre"),
  )

  private fun geographyFields() = listOf(
    field("name_of_the_state", section = "Personal Info", inputType = "select"),
    field("name_of_district", section = "Personal Info", inputType = "select"),
    field("name_of_block_taluka", section = "Personal Info", inputType = "select"),
    field("name_of_the_revenue_village_grampanchayat", section = "Personal Info", inputType = "select"),
    field("beneficary_pada_name", section = "Personal Info", inputType = "select"),
    field("beneficary_phc_name", section = "Personal Info", inputType = "select"),
    field("name_of_sub_center", section = "Personal Info", inputType = "select"),
  )

  @Test
  fun `geography fields auto-fill from the backend geography, not a hardcoded cascade`() = runTest {
    val vm = viewModel(geographyFields(), geography = sampleGeography())

    val answers = vm.uiState.value.answers
    // The exact HTTP 422 case: phcId must be the backend unit's id, not a stale static UUID.
    assertEquals("phc-uuid", answers.valueOf("beneficary_phc_name"))
    assertEquals("state-uuid", answers.valueOf("name_of_the_state"))
    assertEquals("district-uuid", answers.valueOf("name_of_district"))
    assertEquals("block-uuid", answers.valueOf("name_of_block_taluka"))
    assertEquals("village-uuid", answers.valueOf("name_of_the_revenue_village_grampanchayat"))
    assertEquals("pada-uuid", answers.valueOf("beneficary_pada_name"))
    assertEquals("subcentre-uuid", answers.valueOf("name_of_sub_center"))
  }

  @Test
  fun `geography options resolve to the backend unit per level`() = runTest {
    val vm = viewModel(geographyFields(), geography = sampleGeography())

    val phcOptions = vm.optionsFor(
      field("beneficary_phc_name", section = "Personal Info", inputType = "select"),
    )
    assertEquals(1, phcOptions.size)
    assertEquals("phc-uuid", phcOptions.first().valueCode)
    assertEquals("Dhadgaon PHC", phcOptions.first().label)
  }

  @Test
  fun `project_name auto-fills from the Sakhi profile`() = runTest {
    val vm = viewModel(
      listOf(field("project_name", section = "Personal Info", inputType = "select")),
      geography = sampleGeography(),
      projectName = "Test Project (seeded)",
    )

    assertEquals("Test Project (seeded)", vm.uiState.value.answers.valueOf("project_name"))
  }

  @Test
  fun `a geography level missing from the backend is not prefilled and leaves no option`() = runTest {
    // Backend omits PHC — must surface as an empty (submit-gating) field, never a wrong id.
    val geographyWithoutPhc = sampleGeography().filterNot { it.geoType == "PHC" }
    val vm = viewModel(geographyFields(), geography = geographyWithoutPhc)

    assertEquals(null, vm.uiState.value.answers.valueOf("beneficary_phc_name"))
    assertTrue(
      vm.optionsFor(field("beneficary_phc_name", section = "Personal Info", inputType = "select")).isEmpty(),
    )
  }

  @Test
  fun `a level with several backend units is left for the Sakhi to pick, not auto-filled`() = runTest {
    val geographyWithTwoPhcs = sampleGeography() + FormGeographyUnit("phc-uuid-2", "PHC", "Second PHC")
    val vm = viewModel(geographyFields(), geography = geographyWithTwoPhcs)

    assertEquals(null, vm.uiState.value.answers.valueOf("beneficary_phc_name"))
    assertEquals(
      2,
      vm.optionsFor(field("beneficary_phc_name", section = "Personal Info", inputType = "select")).size,
    )
  }

  @Test
  fun `age_of_the_beneficiary (live schema code) auto-fills from DOB`() = runTest {
    // The live api.armman.org schema names the age field `age_of_the_beneficiary` (not `age_years`),
    // input_type number, with no computedFrom — the exact case the old single-code stopgap missed.
    val vm = viewModel(
      listOf(
        field("date_of_birth", section = "Personal Info", inputType = "date"),
        field("age_of_the_beneficiary", section = "Personal Info", required = false, inputType = "number"),
      ),
    )

    vm.setAnswer("date_of_birth", "2001-07-27")
    dispatcher.scheduler.advanceUntilIdle()

    val expectedAge = java.time.temporal.ChronoUnit.YEARS.between(
      java.time.LocalDate.of(2001, 7, 27),
      vm.registrationDate,
    ).toString()
    assertEquals(expectedAge, vm.uiState.value.answers.valueOf("age_of_the_beneficiary"))
  }

  @Test
  fun `mobile_number shorter than 10 digits blocks submit and 10 digits unblocks it`() = runTest {
    val vm = viewModel(
      listOf(field("mobile_number", section = "Personal Info", required = true, inputType = "number")),
    )

    vm.setAnswer("mobile_number", "63823")
    assertFalse(vm.isReadyToSubmit())
    assertFalse(vm.isSectionReady("Personal Info"))

    vm.setAnswer("mobile_number", "6382325824")
    assertTrue(vm.isReadyToSubmit())
    assertTrue(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `media completion and image capture are mirrored into answers so they reach formData`() = runTest {
    val vm = viewModel(
      listOf(
        field("arogya_sakhi_video", section = "Consent", inputType = "media"),
        field("consent_form_photo", section = "Consent", inputType = "image"),
      ),
    )

    vm.markMediaComplete("arogya_sakhi_video")
    vm.setCapturedImage("consent_form_photo", "file:///data/photo.jpg")
    dispatcher.scheduler.advanceUntilIdle()

    val answers = vm.uiState.value.answers
    assertEquals("true", answers.valueOf("arogya_sakhi_video"))
    assertEquals("file:///data/photo.jpg", answers.valueOf("consent_form_photo"))

    // Retake clears both the UI state and the mirrored answer.
    vm.setCapturedImage("consent_form_photo", null)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(null, vm.uiState.value.answers.valueOf("consent_form_photo"))
  }

  @Test
  fun `age_years is left untouched when the schema doesn't declare the field at all`() = runTest {
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info", required = false)))

    vm.setAnswer("mobile_number", "9876543210")
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(null, vm.uiState.value.answers.valueOf("age_years"))
  }

  @Test
  fun `required date field (DOB) blocks isReadyToSubmit until answered`() = runTest {
    val vm = viewModel(
      listOf(
        field("date_of_birth", section = "Personal Info", required = true, inputType = "date"),
      ),
    )

    assertFalse(vm.isReadyToSubmit())

    vm.setAnswer("date_of_birth", "2009-07-22")
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `isSectionReady gates one tab independently of others`() = runTest {
    val vm = viewModel(
      listOf(
        field("consent_q", section = "Consent", required = true, inputType = "text"),
        field("pi_q", section = "Personal Info", required = true, inputType = "text"),
      ),
    )

    // Neither tab is ready initially; filling Consent unlocks only Consent, not Personal Info.
    assertFalse(vm.isSectionReady("Consent"))
    assertFalse(vm.isSectionReady("Personal Info"))

    vm.setAnswer("consent_q", "x")
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(vm.isSectionReady("Consent"))
    assertFalse(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `isSectionReady ignores optional fields and out-of-range numbers gate the tab`() = runTest {
    val vm = viewModel(
      listOf(
        field("opt", section = "Consent", required = false, inputType = "text"),
        field("count", section = "Consent", required = true, inputType = "number").copy(
          numericRange = org.armman.sakhi.data.forms.FormNumericRange(min = 1.0, max = 5.0),
        ),
      ),
    )

    // Optional field blank is fine, but the required number must be present AND within range.
    assertFalse(vm.isSectionReady("Consent"))
    vm.setAnswer("count", "9") // out of range
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isSectionReady("Consent"))
    vm.setAnswer("count", "3") // in range
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isSectionReady("Consent"))
  }

  @Test
  fun `buildSummary resolves coded values to labels, groups by section, and skips blanks`() = runTest {
    val vm = viewModel(
      listOf(
        field("first_name", section = "Personal Info", required = false, inputType = "text"),
        field("religion", section = "Personal Info", required = false, inputType = "select").copy(
          options = listOf(
            FormFieldOption(label = "Hindu", sortOrder = 0, valueCode = "hindu"),
            FormFieldOption(label = "Muslim", sortOrder = 1, valueCode = "muslim"),
          ),
        ),
        field("remarks", section = "Personal Info", required = false, inputType = "text"),
      ),
    )
    vm.setAnswer("first_name", "Test")
    vm.setAnswer("religion", "hindu")
    dispatcher.scheduler.advanceUntilIdle()

    val rows = vm.buildSummary("Completed", "Photo captured")
      .single { it.title == "Personal Info" }.rows
    assertEquals("Test", rows.first { it.label == "first_name" }.value)
    assertEquals("Hindu", rows.first { it.label == "religion" }.value) // code resolved to label
    assertTrue(rows.none { it.label == "remarks" }) // blank field skipped
  }

  @Test
  fun `buildSummary joins multiselect labels and shows media completion`() = runTest {
    val vm = viewModel(
      listOf(
        field("conditions", section = "Health History", required = false, inputType = "multiselect").copy(
          options = listOf(
            FormFieldOption(label = "Anemia", sortOrder = 0, valueCode = "anemia"),
            FormFieldOption(label = "Diabetes", sortOrder = 1, valueCode = "diabetes"),
          ),
        ),
        field("arogya_sakhi_video", section = "Consent", required = false, inputType = "media"),
      ),
    )
    vm.setMultiAnswer("conditions", listOf("anemia", "diabetes"))
    vm.markMediaComplete("arogya_sakhi_video")
    dispatcher.scheduler.advanceUntilIdle()

    val summary = vm.buildSummary("Completed", "Photo captured")
    assertEquals(
      "Anemia, Diabetes",
      summary.single { it.title == "Health History" }.rows.single().value,
    )
    assertEquals("Completed", summary.single { it.title == "Consent" }.rows.single().value)
  }

  @Test
  fun `computedFrom field with no value does not block isReadyToSubmit`() = runTest {
    val vm = viewModel(
      listOf(
        field(
          "unique_id",
          section = "Personal Info",
          required = true,
          inputType = "text",
          computedFrom = "UNIQUE_ID",
        ),
        field("mobile_number", section = "Personal Info", required = false),
      ),
    )

    // unique_id's computedFrom formula is unconfirmed (returns null) — it must not permanently
    // block submission just because it's marked required.
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `a real required field with no answer still blocks isReadyToSubmit`() = runTest {
    val vm = viewModel(
      listOf(field("mobile_number", section = "Personal Info", required = true)),
    )

    assertFalse(vm.isReadyToSubmit())

    vm.setAnswer("mobile_number", "9876543210")

    assertTrue(vm.isReadyToSubmit())
  }

  // --- submit(): the submit-then-navigate fix ---------------------------------------------------

  @Test
  fun `submit on backend success reports Success`() = runTest {
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.Synced)
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info")), draftRepository)
    vm.setAnswer("mobile_number", "9876543210")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(SubmissionState.Success, vm.uiState.value.submissionState)
    assertEquals(1, draftRepository.submitCallCount)
  }

  @Test
  fun `submit on backend validation failure reports Failed with the backend message — screen must not navigate`() = runTest {
    // A sentence whose leading token is not a known DTO field name, so SubmitErrorCopy passes it
    // through verbatim (no field-label substitution) — the case this test cares about.
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.Failed("Obstetric totals do not add up"))
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info")), draftRepository)
    vm.setAnswer("mobile_number", "9876543210")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value.submissionState
    assertTrue(state is SubmissionState.Failed)
    assertEquals("Obstetric totals do not add up", (state as SubmissionState.Failed).message)
  }

  @Test
  fun `submit on duplicate conflict reports Failed with a default message when none is given`() = runTest {
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.DuplicateConflict(null))
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info")), draftRepository)
    vm.setAnswer("mobile_number", "9876543210")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value.submissionState
    assertTrue(state is SubmissionState.Failed)
    assertEquals("A possible duplicate beneficiary already exists", (state as SubmissionState.Failed).message)
  }

  @Test
  fun `submit offline (QueuedOffline) still reports Success — offline-first, safe to navigate`() = runTest {
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.QueuedOffline)
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info")), draftRepository)
    vm.setAnswer("mobile_number", "9876543210")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(SubmissionState.Success, vm.uiState.value.submissionState)
  }

  @Test
  fun `submit is a no-op while not ready to submit`() = runTest {
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.Synced)
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info", required = true)), draftRepository)
    // mobile_number left blank -> isReadyToSubmit() is false.

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, draftRepository.submitCallCount)
    assertEquals(SubmissionState.Idle, vm.uiState.value.submissionState)
  }

  // --- submit(): inline field-level errors from a 400 VALIDATION_ERROR --------------------------

  private fun fieldErrorFields() = listOf(
    field("first_name", section = "Personal Info", inputType = "text"),
    field("still_births", section = "Health History", inputType = "number"),
  )

  private fun readyViewModelFor(draftRepository: FakeDraftRepository): DynamicMotherRegistrationViewModel {
    val vm = viewModel(fieldErrorFields(), draftRepository)
    vm.setAnswer("first_name", "x")
    vm.setAnswer("still_births", "1")
    return vm
  }

  @Test
  fun `submit 400 with fieldErrors sets inline errors, a generic banner, and a scroll target`() = runTest {
    val draftRepository = FakeDraftRepository(
      DynamicFormSubmitResult.Failed(
        message = "raw backend text the Sakhi should not have to read",
        fieldErrors = mapOf(
          "pii.firstName" to "First name is required",
          "motherDetails.stillbirths" to "too many",
        ),
      ),
    )
    val vm = readyViewModelFor(draftRepository)

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value
    // DTO paths mapped to question_codes and shown inline.
    assertEquals("First name is required", state.fieldErrors["first_name"])
    assertEquals("too many", state.fieldErrors["still_births"])
    // Generic banner, not the raw backend message, since the fields now carry the detail.
    val banner = state.submissionState
    assertTrue(banner is SubmissionState.Failed)
    assertEquals("Please fix the highlighted fields and submit again.", (banner as SubmissionState.Failed).message)
    // Jumps to the first errored field in schema order (first_name, on Personal Info).
    assertEquals("Personal Info", state.errorScroll?.section)
    assertEquals("first_name", state.errorScroll?.questionCode)
  }

  @Test
  fun `a fieldError on a question this schema does not declare still shows a clean banner`() = runTest {
    // The reported bug: `lmp_date` isn't in this version's schema, so nothing maps, and the banner
    // used to fall through to the raw exception text (endpoint + HTTP code + whole JSON body).
    val draftRepository = FakeDraftRepository(
      DynamicFormSubmitResult.Failed(
        message = "motherDetails.lmpDate: lmpDate cannot be in the future",
        fieldErrors = mapOf("motherDetails.lmpDate" to "lmpDate cannot be in the future"),
      ),
    )
    val vm = readyViewModelFor(draftRepository)

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value
    assertTrue(state.fieldErrors.isEmpty())
    assertEquals(
      "LMP date cannot be in the future",
      (state.submissionState as SubmissionState.Failed).message,
    )
  }

  @Test
  fun `an inline field error is humanized too, not left in DTO wording`() = runTest {
    val draftRepository = FakeDraftRepository(
      DynamicFormSubmitResult.Failed(
        message = null,
        fieldErrors = mapOf("pii.firstName" to "firstName must contain at least 1 character(s)"),
      ),
    )
    val vm = readyViewModelFor(draftRepository)

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      "First name must contain at least 1 character(s)",
      vm.uiState.value.fieldErrors["first_name"],
    )
  }

  @Test
  fun `a failure with no usable message falls back to the generic sentence`() = runTest {
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.Failed(message = null))
    val vm = readyViewModelFor(draftRepository)

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      SubmitErrorCopy.GENERIC,
      (vm.uiState.value.submissionState as SubmissionState.Failed).message,
    )
  }

  @Test
  fun `editing a flagged field clears only its inline error`() = runTest {
    val draftRepository = FakeDraftRepository(
      DynamicFormSubmitResult.Failed(
        message = "raw",
        fieldErrors = mapOf(
          "pii.firstName" to "First name is required",
          "motherDetails.stillbirths" to "too many",
        ),
      ),
    )
    val vm = readyViewModelFor(draftRepository)
    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    vm.setAnswer("first_name", "Meera")

    val fieldErrors = vm.uiState.value.fieldErrors
    assertFalse(fieldErrors.containsKey("first_name"))
    assertEquals("too many", fieldErrors["still_births"])
  }

  @Test
  fun `scroll target points at the section holding the first errored field`() = runTest {
    val draftRepository = FakeDraftRepository(
      DynamicFormSubmitResult.Failed(
        message = "raw",
        fieldErrors = mapOf("motherDetails.stillbirths" to "too many"),
      ),
    )
    val vm = viewModel(
      listOf(
        field("did_we_receive_consent", section = "Consent", inputType = "radio"),
        field("still_births", section = "Health History", inputType = "number"),
      ),
      draftRepository,
    )
    vm.setAnswer("did_we_receive_consent", "yes")
    vm.setAnswer("still_births", "1")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("Health History", vm.uiState.value.errorScroll?.section)
    assertEquals("still_births", vm.uiState.value.errorScroll?.questionCode)
  }

  @Test
  fun `submit failure with no fieldErrors stays banner-only with the raw message`() = runTest {
    val draftRepository = FakeDraftRepository(
      DynamicFormSubmitResult.Failed(
        message = "pii.phcId does not refer to a known geography unit.",
        fieldErrors = emptyMap(),
      ),
    )
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info")), draftRepository)
    vm.setAnswer("mobile_number", "9876543210")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value
    assertTrue(state.fieldErrors.isEmpty())
    assertNull(state.errorScroll)
    assertEquals(
      "pii.phcId does not refer to a known geography unit.",
      (state.submissionState as SubmissionState.Failed).message,
    )
  }

  // --- Obstetric history gate (Registration_PW_D rows 45-50) ------------------------------------

  private fun obstetricViewModel() = viewModel(
    listOf(
      field(FormObstetricRuleset.GRAVIDA, section = "Health History", inputType = "number"),
      field(FormObstetricRuleset.PARA, section = "Health History", inputType = "number"),
      field(FormObstetricRuleset.LIVING_CHILDREN, section = "Health History", inputType = "number"),
      field(FormObstetricRuleset.ABORTIONS, section = "Health History", inputType = "number"),
      field(FormObstetricRuleset.STILL_BIRTHS, section = "Health History", inputType = "number"),
    ),
  )

  /** Fills every obstetric field in one call; unspecified figures default to zero. */
  private fun DynamicMotherRegistrationViewModel.answerObstetrics(
    gravida: String,
    para: String = "0",
    living: String = "0",
    abortions: String = "0",
    stillBirths: String = "0",
  ) {
    setAnswer(FormObstetricRuleset.GRAVIDA, gravida)
    setAnswer(FormObstetricRuleset.PARA, para)
    setAnswer(FormObstetricRuleset.LIVING_CHILDREN, living)
    setAnswer(FormObstetricRuleset.ABORTIONS, abortions)
    setAnswer(FormObstetricRuleset.STILL_BIRTHS, stillBirths)
  }

  @Test
  fun `an inconsistent gravida blocks its tab and submission`() = runTest {
    val vm = obstetricViewModel()

    // QA's case: Gravida 6 alongside zeros, which total 0.
    vm.answerObstetrics(gravida = "6")

    // Blocked on the Health History tab itself, not only at Submit.
    assertFalse(vm.isSectionReady("Health History"))
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `a consistent obstetric history passes the gate`() = runTest {
    val vm = obstetricViewModel()

    vm.answerObstetrics(gravida = "4", para = "3", living = "2", abortions = "1", stillBirths = "1")

    assertTrue(vm.isSectionReady("Health History"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `correcting gravida clears the block`() = runTest {
    val vm = obstetricViewModel()

    vm.answerObstetrics(gravida = "6", living = "1", abortions = "1")
    assertFalse(vm.isSectionReady("Health History"))

    vm.setAnswer(FormObstetricRuleset.GRAVIDA, "2")
    assertTrue(vm.isSectionReady("Health History"))
  }

  @Test
  fun `para above gravida blocks the gate`() = runTest {
    val vm = obstetricViewModel()

    vm.answerObstetrics(gravida = "2", para = "3", living = "2")

    assertFalse(vm.isSectionReady("Health History"))
  }

  // --- Spec date rules gate (Registration_PW_D rows 7 / 13 / 23) -------------------------------

  @Test
  fun `future date of birth blocks its tab and submission`() = runTest {
    val vm = viewModel(
      listOf(field(DOB_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(DOB_QUESTION_CODE, vm.registrationDate.plusDays(1).toString())

    assertFalse(vm.isSectionReady("Personal Info"))
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `date of birth inside the accepted age range passes the gate`() = runTest {
    val vm = viewModel(
      listOf(field(DOB_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(DOB_QUESTION_CODE, vm.registrationDate.minusYears(25).toString())

    assertTrue(vm.isSectionReady("Personal Info"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `date of birth outside the 10 to 50 age range blocks the gate`() = runTest {
    val vm = viewModel(
      listOf(field(DOB_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(DOB_QUESTION_CODE, vm.registrationDate.minusYears(9).toString())
    assertFalse(vm.isSectionReady("Personal Info"))

    vm.setAnswer(DOB_QUESTION_CODE, vm.registrationDate.minusYears(60).toString())
    assertFalse(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `future lmp blocks its tab and submission`() = runTest {
    val vm = viewModel(
      listOf(field(LMP_DATE_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(LMP_DATE_QUESTION_CODE, vm.registrationDate.plusDays(1).toString())

    assertFalse(vm.isSectionReady("Personal Info"))
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `lmp inside the spec window passes the gate`() = runTest {
    val vm = viewModel(
      listOf(field(LMP_DATE_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(LMP_DATE_QUESTION_CODE, vm.registrationDate.minusDays(60).toString())

    assertTrue(vm.isSectionReady("Personal Info"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `future registration date blocks its tab and submission`() = runTest {
    val vm = viewModel(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(REGISTRATION_DATE_QUESTION_CODE, vm.registrationDate.plusDays(1).toString())

    assertFalse(vm.isSectionReady("Personal Info"))
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `registration date of today passes the gate`() = runTest {
    val vm = viewModel(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(REGISTRATION_DATE_QUESTION_CODE, vm.registrationDate.toString())

    assertTrue(vm.isSectionReady("Personal Info"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `a date field with no spec rule is not gated on dates`() = runTest {
    val vm = viewModel(
      listOf(field("some_other_date", section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer("some_other_date", vm.registrationDate.plusYears(1).toString())

    assertTrue(vm.isSectionReady("Personal Info"))
  }

  // --- Consent refused hard stop -------------------------------------------------------------

  @Test
  fun `consent refused blocks the Consent tab and submission`() = runTest {
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", inputType = "radio")),
    )

    vm.setAnswer("did_we_receive_consent", "no")

    assertTrue(vm.consentRefused())
    assertFalse(vm.isSectionReady("Consent"))
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `consent given unblocks the Consent tab and allows submission`() = runTest {
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", inputType = "radio")),
    )

    vm.setAnswer("did_we_receive_consent", "yes")

    assertFalse(vm.consentRefused())
    assertTrue(vm.isSectionReady("Consent"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `re-answering consent no then yes clears the block`() = runTest {
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", inputType = "radio")),
    )

    vm.setAnswer("did_we_receive_consent", "no")
    assertFalse(vm.isSectionReady("Consent"))

    vm.setAnswer("did_we_receive_consent", "yes")
    assertFalse(vm.consentRefused())
    assertTrue(vm.isSectionReady("Consent"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `unanswered consent is not treated as a refusal`() = runTest {
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", inputType = "radio")),
    )

    // No answer yet: not a refusal (no banner), but still blocked by the normal required-field gate.
    assertFalse(vm.consentRefused())
    assertFalse(vm.isSectionReady("Consent"))
  }

  @Test
  fun `consent refused overrides an otherwise complete form`() = runTest {
    val vm = viewModel(
      listOf(
        field("did_we_receive_consent", section = "Consent", inputType = "radio"),
        field("mobile_number", section = "Personal Info"),
      ),
    )

    vm.setAnswer("mobile_number", "9876543210")
    vm.setAnswer("did_we_receive_consent", "no")

    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `submit is a no-op when consent is refused`() = runTest {
    val draftRepository = FakeDraftRepository()
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", inputType = "radio")),
      draftRepository,
    )

    vm.setAnswer("did_we_receive_consent", "no")
    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, draftRepository.submitCallCount)
  }

  // ---------------------------------------------------------------------------------------------
  // Beneficiary name character rule (form spec S.No 19). The renderer filters the input, so these
  // cover values that bypassed it — a draft saved before the rule shipped, or a restored answer.
  // ---------------------------------------------------------------------------------------------

  private fun nameFormFields() = listOf(
    field("first_name", section = "Personal Info"),
    field("middle_name", section = "Personal Info", required = false),
    field("last_name", section = "Personal Info"),
    field("enter_the_beneficiary_address", section = "Personal Info"),
  )

  private fun DynamicMotherRegistrationViewModel.answerCleanNames() {
    setAnswer("first_name", "Reema")
    setAnswer("last_name", "Devi")
    setAnswer("enter_the_beneficiary_address", "Pada 4, Dhadgaon")
  }

  @Test
  fun `a name holding special characters blocks the section and submit`() = runTest {
    val vm = viewModel(nameFormFields())

    vm.answerCleanNames()
    vm.setAnswer("first_name", "Reema#")

    assertFalse(vm.isSectionReady("Personal Info"))
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `clean names leave the section ready`() = runTest {
    val vm = viewModel(nameFormFields())

    vm.answerCleanNames()

    assertTrue(vm.isSectionReady("Personal Info"))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `an optional middle name still blocks when it holds special characters`() = runTest {
    val vm = viewModel(nameFormFields())

    vm.answerCleanNames()
    vm.setAnswer("middle_name", "Devi@")

    assertFalse(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `the name rule does not leak to other text fields`() = runTest {
    // Addresses legitimately contain digits and punctuation — filtering them would be a regression.
    val vm = viewModel(nameFormFields())

    vm.answerCleanNames()
    vm.setAnswer("enter_the_beneficiary_address", "Plot #12, Ward-3")

    assertTrue(vm.isSectionReady("Personal Info"))
    assertTrue(vm.isReadyToSubmit())
  }
}
