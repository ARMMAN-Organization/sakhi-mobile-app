package org.armman.sakhi.ui.forms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.geography.GeographyRepository
import org.armman.sakhi.data.geography.GeographyUnit
import org.armman.sakhi.data.geography.SakhiAssignment
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    )
    val vm = DynamicMotherRegistrationViewModel(
      FakeFormsRepository(version),
      FakeLookupRepository(),
      GeographyFieldOptionsResolver(FakeGeographyRepository(), fakeCurrentUserRepository()),
      draftRepository,
    )
    dispatcher.scheduler.advanceUntilIdle()
    return vm
  }

  // Avoids pulling in a full CurrentUserRepository fake for a resolver path these tests never hit
  // (no geography question_codes below) — see GeographyFieldOptionsResolver's own fallback.
  private fun fakeCurrentUserRepository() =
    object : org.armman.sakhi.data.auth.CurrentUserRepository {
      override suspend fun getProfile() = null
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
    val draftRepository = FakeDraftRepository(DynamicFormSubmitResult.Failed("gravida total mismatch"))
    val vm = viewModel(listOf(field("mobile_number", section = "Personal Info")), draftRepository)
    vm.setAnswer("mobile_number", "9876543210")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value.submissionState
    assertTrue(state is SubmissionState.Failed)
    assertEquals("gravida total mismatch", (state as SubmissionState.Failed).message)
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
}
