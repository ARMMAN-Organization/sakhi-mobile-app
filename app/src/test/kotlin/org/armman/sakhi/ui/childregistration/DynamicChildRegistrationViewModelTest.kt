package org.armman.sakhi.ui.childregistration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormNumericRange
import org.armman.sakhi.data.forms.FormUploadRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicChildRegistrationViewModelTest {

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

  private class FakeChildDraftRepository(
    private val submitResult: ChildFormSubmitResult = ChildFormSubmitResult.Synced,
  ) : ChildFormDraftRepository {
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
    ): ChildFormSubmitResult {
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
    numericRange: FormNumericRange? = null,
  ) = FormFieldSchema(
    label = questionCode,
    required = required,
    inputTypeRaw = inputType,
    questionCode = questionCode,
    computedFrom = computedFrom,
    numericRange = numericRange,
    section = section,
  )

  private fun fakeCurrentUserRepository() =
    object : org.armman.sakhi.data.auth.CurrentUserRepository {
      override suspend fun getProfile() = null
      override fun clear() = Unit
      override fun clearIfDifferentUser(username: String) = Unit
    }

  private fun viewModel(
    fields: List<FormFieldSchema>,
    draftRepository: FakeChildDraftRepository = FakeChildDraftRepository(),
  ): DynamicChildRegistrationViewModel {
    val version = FormVersion(
      id = "v2",
      formDefinitionId = "def-child",
      versionNo = "v2",
      schemaJson = fields,
      validationJson = emptyList(),
      effectiveFrom = "2026-07-21T00:00:00Z",
      effectiveTo = null,
      status = "PUBLISHED",
    )
    val vm = DynamicChildRegistrationViewModel(
      FakeFormsRepository(version),
      FakeLookupRepository(),
      GeographyFieldOptionsResolver(FakeGeographyRepository(), fakeCurrentUserRepository()),
      draftRepository,
    )
    dispatcher.scheduler.advanceUntilIdle()
    return vm
  }

  private fun viewModelNoSchema(): DynamicChildRegistrationViewModel {
    val vm = DynamicChildRegistrationViewModel(
      FakeFormsRepository(null),
      FakeLookupRepository(),
      GeographyFieldOptionsResolver(FakeGeographyRepository(), fakeCurrentUserRepository()),
      FakeChildDraftRepository(),
    )
    dispatcher.scheduler.advanceUntilIdle()
    return vm
  }

  @Test
  fun `schema loads from the repository`() = runTest {
    val vm = viewModel(listOf(field("name_of_the_child", section = "Infant Details")))

    assertNotNull(vm.uiState.value.version)
    assertNull(vm.uiState.value.loadError)
  }

  @Test
  fun `null active version surfaces a loadError`() = runTest {
    val vm = viewModelNoSchema()

    assertNull(vm.uiState.value.version)
    assertNotNull(vm.uiState.value.loadError)
  }

  @Test
  fun `beneficiary_id and unique_id are never in visibleFields`() = runTest {
    val vm = viewModel(
      listOf(
        field("beneficiary_id", section = "Infant Details", inputType = "number"),
        field("unique_id", section = "Infant Details", inputType = "text", computedFrom = "UNIQUE_ID"),
        field("name_of_the_child", section = "Infant Details"),
      ),
    )

    val codes = vm.visibleFields().map { it.questionCode }

    assertFalse(codes.contains("beneficiary_id"))
    assertFalse(codes.contains("unique_id"))
    assertTrue(codes.contains("name_of_the_child"))
  }

  @Test
  fun `current_age_of_infant_in_days is computed in DAYS from the infant DOB`() = runTest {
    val vm = viewModel(
      listOf(
        field("date_of_birth_of_infant", section = "Infant Details", inputType = "date"),
        field(
          "current_age_of_infant_in_days",
          section = "Infant Details",
          required = false,
          inputType = "number",
          computedFrom = "CHILD_AGE_MONTHS",
        ),
      ),
    )

    val dob = vm.registrationDate.minusDays(45)
    vm.setAnswer("date_of_birth_of_infant", dob.toString())
    dispatcher.scheduler.advanceUntilIdle()

    val expected = ChronoUnit.DAYS.between(dob, vm.registrationDate).toString()
    assertEquals(expected, vm.uiState.value.answers.valueOf("current_age_of_infant_in_days"))
  }

  @Test
  fun `mother_beneficiary_id is hidden on the direct path and shown on the registered-mother path`() = runTest {
    val vm = viewModel(
      listOf(
        field("who_are_you_registering_in_the_program", section = "Personal Info", inputType = "radio"),
        field("mother_beneficiary_id", section = "Personal Info", inputType = "number"),
      ),
    )

    vm.setAnswer("who_are_you_registering_in_the_program", "child_directly_mother_not_registered_in_the_program")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.visibleFields().map { it.questionCode }.contains("mother_beneficiary_id"))

    vm.setAnswer("who_are_you_registering_in_the_program", "child_of_a_registered_pregnant_woman")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.visibleFields().map { it.questionCode }.contains("mother_beneficiary_id"))
  }

  @Test
  fun `mother_beneficiary_id is required only on the registered-mother path`() = runTest {
    val vm = viewModel(
      listOf(
        field("who_are_you_registering_in_the_program", section = "Personal Info", inputType = "radio"),
        field("mother_beneficiary_id", section = "Personal Info", inputType = "number"),
      ),
    )

    // Direct path: mother id hidden, so it does not gate submission.
    vm.setAnswer("who_are_you_registering_in_the_program", "child_directly_mother_not_registered_in_the_program")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())

    // Registered-mother path: mother id now visible + required, blank blocks submission.
    vm.setAnswer("who_are_you_registering_in_the_program", "child_of_a_registered_pregnant_woman")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())

    vm.setAnswer("mother_beneficiary_id", "mother-123")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `numeric range for child length (35-60) gates on boundaries`() = runTest {
    val vm = viewModel(
      listOf(
        field(
          "child_length_at_birth_in_cm",
          section = "Infant Details",
          required = true,
          inputType = "number",
          numericRange = FormNumericRange(min = 35.0, max = 60.0),
        ),
      ),
    )

    vm.setAnswer("child_length_at_birth_in_cm", "34")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())
    vm.setAnswer("child_length_at_birth_in_cm", "35")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
    vm.setAnswer("child_length_at_birth_in_cm", "60")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
    vm.setAnswer("child_length_at_birth_in_cm", "61")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `numeric range for child weight (0_5-15) gates on boundaries`() = runTest {
    val vm = viewModel(
      listOf(
        field(
          "child_weight_at_birth_in_kg",
          section = "Infant Details",
          required = true,
          inputType = "number",
          numericRange = FormNumericRange(min = 0.5, max = 15.0),
        ),
      ),
    )

    vm.setAnswer("child_weight_at_birth_in_kg", "0")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())
    vm.setAnswer("child_weight_at_birth_in_kg", "0.5")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
    vm.setAnswer("child_weight_at_birth_in_kg", "15")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
    vm.setAnswer("child_weight_at_birth_in_kg", "16")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `numeric range for family members (2-15) gates on boundaries`() = runTest {
    val vm = viewModel(
      listOf(
        field(
          "family_members_in_household",
          section = "Personal Info",
          required = true,
          inputType = "number",
          numericRange = FormNumericRange(min = 2.0, max = 15.0),
        ),
      ),
    )

    vm.setAnswer("family_members_in_household", "1")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())
    vm.setAnswer("family_members_in_household", "2")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
    vm.setAnswer("family_members_in_household", "15")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
    vm.setAnswer("family_members_in_household", "16")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())
  }

  // --- eligibility windows ----------------------------------------------------------------------

  private fun eligibilityViewModel() = viewModel(
    listOf(
      field("who_are_you_registering_in_the_program", section = "Consent", inputType = "radio"),
      field("date_of_birth_of_infant", section = "Personal Info", inputType = "date"),
    ),
  )

  @Test
  fun `direct path allows exactly 365 days and rejects 366`() = runTest {
    val vm = eligibilityViewModel()
    vm.setAnswer("who_are_you_registering_in_the_program", "child_directly_mother_not_registered_in_the_program")

    vm.setAnswer("date_of_birth_of_infant", vm.registrationDate.minusDays(365).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.uiState.value.validationError)
    assertTrue(vm.isReadyToSubmit())

    vm.setAnswer("date_of_birth_of_infant", vm.registrationDate.minusDays(366).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(ChildValidationError.INELIGIBLE_DIRECT, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `registered-mother path allows exactly 183 days and rejects 184`() = runTest {
    val vm = eligibilityViewModel()
    vm.setAnswer("who_are_you_registering_in_the_program", "child_of_a_registered_pregnant_woman")

    vm.setAnswer("date_of_birth_of_infant", vm.registrationDate.minusDays(183).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.uiState.value.validationError)

    vm.setAnswer("date_of_birth_of_infant", vm.registrationDate.minusDays(184).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(ChildValidationError.INELIGIBLE_MOTHER, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `future infant DOB is invalid on either path`() = runTest {
    val vm = eligibilityViewModel()
    vm.setAnswer("who_are_you_registering_in_the_program", "child_directly_mother_not_registered_in_the_program")

    vm.setAnswer("date_of_birth_of_infant", vm.registrationDate.plusDays(1).toString())
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(ChildValidationError.DOB_FUTURE, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `consent answered no blocks submission with a consent error`() = runTest {
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", required = true, inputType = "radio")),
    )

    vm.setAnswer("did_we_receive_consent", "no")
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(ChildValidationError.CONSENT_REFUSED, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `consent answered no blocks the Consent tab's next button`() = runTest {
    val vm = viewModel(
      listOf(field("did_we_receive_consent", section = "Consent", required = true, inputType = "radio")),
    )

    vm.setAnswer("did_we_receive_consent", "no")
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isSectionReady("Consent"))

    vm.setAnswer("did_we_receive_consent", "yes")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isSectionReady("Consent"))
  }

  // --- submit -----------------------------------------------------------------------------------

  @Test
  fun `submit on backend success reports Success`() = runTest {
    val draftRepository = FakeChildDraftRepository(ChildFormSubmitResult.Synced)
    val vm = viewModel(listOf(field("name_of_the_child", section = "Infant Details")), draftRepository)
    vm.setAnswer("name_of_the_child", "Aarav")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(SubmissionState.Success, vm.uiState.value.submissionState)
    assertEquals(1, draftRepository.submitCallCount)
  }

  @Test
  fun `submit offline (QueuedOffline) still reports Success`() = runTest {
    val draftRepository = FakeChildDraftRepository(ChildFormSubmitResult.QueuedOffline)
    val vm = viewModel(listOf(field("name_of_the_child", section = "Infant Details")), draftRepository)
    vm.setAnswer("name_of_the_child", "Aarav")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(SubmissionState.Success, vm.uiState.value.submissionState)
  }

  @Test
  fun `submit on duplicate conflict reports Failed with the DUPLICATE kind`() = runTest {
    val draftRepository = FakeChildDraftRepository(ChildFormSubmitResult.DuplicateConflict("dup"))
    val vm = viewModel(listOf(field("name_of_the_child", section = "Infant Details")), draftRepository)
    vm.setAnswer("name_of_the_child", "Aarav")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value.submissionState
    assertTrue(state is SubmissionState.Failed)
    assertEquals(ChildSubmitFailureKind.DUPLICATE, (state as SubmissionState.Failed).kind)
  }

  @Test
  fun `submit on backend validation failure reports Failed with the GENERIC kind and message`() = runTest {
    val draftRepository = FakeChildDraftRepository(ChildFormSubmitResult.Failed("bad data"))
    val vm = viewModel(listOf(field("name_of_the_child", section = "Infant Details")), draftRepository)
    vm.setAnswer("name_of_the_child", "Aarav")

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    val state = vm.uiState.value.submissionState
    assertTrue(state is SubmissionState.Failed)
    assertEquals(ChildSubmitFailureKind.GENERIC, (state as SubmissionState.Failed).kind)
    assertEquals("bad data", state.backendMessage)
  }

  @Test
  fun `submit is a no-op while not ready to submit`() = runTest {
    val draftRepository = FakeChildDraftRepository(ChildFormSubmitResult.Synced)
    val vm = viewModel(
      listOf(field("name_of_the_child", section = "Infant Details", required = true)),
      draftRepository,
    )
    // name_of_the_child left blank -> isReadyToSubmit() is false.

    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, draftRepository.submitCallCount)
    assertEquals(SubmissionState.Idle, vm.uiState.value.submissionState)
  }
}
