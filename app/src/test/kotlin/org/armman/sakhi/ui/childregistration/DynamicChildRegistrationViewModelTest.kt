package org.armman.sakhi.ui.childregistration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormDateRuleset
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormGeographyUnit
import org.armman.sakhi.data.forms.FormNumericRange
import org.armman.sakhi.data.forms.FormUploadRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormVisibleWhen
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODE
import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODE_CORRECTED
import org.armman.sakhi.data.geography.GeographyRepository
import org.armman.sakhi.data.geography.GeographyUnit
import org.armman.sakhi.data.geography.SakhiAssignment
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.motherlink.LinkedMother
import org.armman.sakhi.data.motherlink.LinkedMotherConsent
import org.armman.sakhi.data.motherlink.MotherLinkRepository
import org.armman.sakhi.data.motherlink.MotherPrefill
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

/** Live `question_code`s the CR-031 cases exercise. Re-declared here because the ViewModel's own
 * constants are file-private; a rename there must break these tests loudly rather than silently
 * stop the mother link from applying. Backend typos are reproduced verbatim. */
private const val WHO_ARE_YOU_REGISTERING = "who_are_you_registering_in_the_program"
private const val PATH_REGISTERED_MOTHER = "child_of_a_registered_pregnant_woman"
private const val PATH_DIRECT = "child_directly_mother_not_registered_in_the_program"
private const val MOTHER_BENEFICIARY_ID = "mother_beneficiary_id"
private const val CAREGIVER_NAME = "caregiver_name_first_name_middle_name_last_name"
private const val MOTHER_DATE_OF_BIRTH = "age_or_dob_of_the_mother"
private const val DATE_OF_BIRTH_OF_INFANT = "date_of_birth_of_infant"

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

  /**
   * Stubs the CR-031 mother link. [mothers] null models "nothing fetched and nothing cached" (the
   * offline cold-cache case the screen words differently from an empty list).
   */
  private class FakeMotherLinkRepository(
    private val mothers: List<LinkedMother>? = emptyList(),
    private val consent: LinkedMotherConsent? = null,
  ) : MotherLinkRepository {
    var listCallCount = 0

    override suspend fun getRegisteredMothers(): List<LinkedMother>? {
      listCallCount++
      return mothers
    }

    override suspend fun getMotherConsent(motherId: String): LinkedMotherConsent? = consent
  }

  private fun viewModel(
    fields: List<FormFieldSchema>,
    draftRepository: FakeChildDraftRepository = FakeChildDraftRepository(),
    geography: List<FormGeographyUnit>? = null,
    motherLinkRepository: MotherLinkRepository = FakeMotherLinkRepository(),
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
      geography = geography,
    )
    val vm = DynamicChildRegistrationViewModel(
      FakeFormsRepository(version),
      FakeLookupRepository(),
      GeographyFieldOptionsResolver(FakeGeographyRepository(), fakeCurrentUserRepository()),
      draftRepository,
      motherLinkRepository,
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
      FakeMotherLinkRepository(),
    )
    dispatcher.scheduler.advanceUntilIdle()
    return vm
  }

  // ---------------------------------------------------------------------------------------------
  // CR-031 — mother link & prefill. Covers VM-01 … VM-17 of docs/test-cases/child-mother-link.md.
  // ---------------------------------------------------------------------------------------------

  private fun motherLinkFields() = listOf(
    field(WHO_ARE_YOU_REGISTERING, section = "Consent", inputType = "radio"),
    field(MOTHER_BENEFICIARY_ID, section = "Personal Info", inputType = "number"),
    field(CAREGIVER_NAME, section = "Personal Info"),
    field(MOTHER_DATE_OF_BIRTH, section = "Personal Info", inputType = "date", required = false),
    field("name_of_the_revenue_village_grampanchayat", section = "Personal Info", inputType = "select"),
  )

  private fun sampleMother(
    id: String = "mother-uuid-1",
    fullName: String = "Deepa T Test",
    dateOfBirth: LocalDate? = LocalDate.of(2001, 7, 29),
  ) = LinkedMother(
    id = id,
    fullName = fullName,
    dateOfBirth = dateOfBirth,
    currentPhase = "ANC",
    registrationDate = LocalDate.of(2026, 7, 28),
    stateId = "state-1",
    districtId = "district-1",
    talukaId = "block-1",
    villageId = "village-1",
    padaId = "pada-1",
    phcId = "phc-1",
    healthSubCentreId = "sc-1",
  )

  private fun motherLinkGeography() = listOf(
    FormGeographyUnit("village-1", "VILLAGE", "Kanhe"),
  )

  // VM-04 — a direct-path registration must make no network call at all.
  @Test
  fun `ML-1 the mother list is not fetched on form load`() = runTest {
    val repo = FakeMotherLinkRepository(listOf(sampleMother()))
    viewModel(motherLinkFields(), motherLinkRepository = repo)
    assertEquals(0, repo.listCallCount)
  }

  @Test
  fun `ML-2 choosing the direct path fetches nothing`() = runTest {
    val repo = FakeMotherLinkRepository(listOf(sampleMother()))
    val vm = viewModel(motherLinkFields(), motherLinkRepository = repo)

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, repo.listCallCount)
  }

  // VM-01
  @Test
  fun `ML-3 choosing the registered-mother path loads the picker list`() = runTest {
    val repo = FakeMotherLinkRepository(listOf(sampleMother()))
    val vm = viewModel(motherLinkFields(), motherLinkRepository = repo)

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, repo.listCallCount)
    assertEquals(listOf("mother-uuid-1"), vm.uiState.value.motherOptions.map { it.id })
    assertFalse(vm.uiState.value.motherLoadFailed)
  }

  // VM-02 / VM-03 — the two empty outcomes must stay distinguishable.
  @Test
  fun `ML-4 a cold-cache offline load is flagged separately from having no mothers`() = runTest {
    val offline = viewModel(motherLinkFields(), motherLinkRepository = FakeMotherLinkRepository(mothers = null))
    offline.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(offline.uiState.value.motherLoadFailed)

    val none = viewModel(motherLinkFields(), motherLinkRepository = FakeMotherLinkRepository(mothers = emptyList()))
    none.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(none.uiState.value.motherLoadFailed)
    assertTrue(none.uiState.value.motherOptions.isEmpty())
  }

  // VM-05 / VM-10 — the stored answer must be the UUID the backend validates, not a display name.
  @Test
  fun `ML-5 selecting a mother prefills her record and stores her uuid`() = runTest {
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()

    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    val answers = vm.uiState.value.answers
    assertEquals("mother-uuid-1", answers.valueOf(MOTHER_BENEFICIARY_ID))
    assertEquals("Deepa T Test", answers.valueOf(CAREGIVER_NAME))
    assertEquals("2001-07-29", answers.valueOf(MOTHER_DATE_OF_BIRTH))
    assertEquals("village-1", answers.valueOf("name_of_the_revenue_village_grampanchayat"))
    assertEquals("mother-uuid-1", vm.uiState.value.selectedMotherId)
    assertTrue(vm.isPrefilledFromMother(CAREGIVER_NAME))
  }

  // VM-06 — the hint must clear for the edited field only.
  @Test
  fun `ML-6 editing a prefilled field drops only its own hint`() = runTest {
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    vm.setAnswer(CAREGIVER_NAME, "Corrected Name")

    assertEquals("Corrected Name", vm.uiState.value.answers.valueOf(CAREGIVER_NAME))
    assertFalse(vm.isPrefilledFromMother(CAREGIVER_NAME))
    assertTrue(vm.isPrefilledFromMother(MOTHER_DATE_OF_BIRTH))
  }

  // VM-08 — her own typing survives a path switch; the inherited values do not.
  @Test
  fun `ML-7 switching to the direct path clears untouched prefill but keeps edits`() = runTest {
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()
    vm.setAnswer(CAREGIVER_NAME, "Her Own Entry")

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)
    dispatcher.scheduler.advanceUntilIdle()

    val answers = vm.uiState.value.answers
    assertEquals("Her Own Entry", answers.valueOf(CAREGIVER_NAME))
    assertNull(answers.valueOf(MOTHER_BENEFICIARY_ID))
    assertNull(answers.valueOf(MOTHER_DATE_OF_BIRTH))
    assertNull(vm.uiState.value.selectedMotherId)
    assertTrue(vm.uiState.value.motherPrefilledCodes.isEmpty())
  }

  // VM-12 — CR-031 must not regress the CR-020 direct-path fallback.
  @Test
  fun `ML-8 the mother link field stays hidden on the direct path`() = runTest {
    val vm = viewModel(motherLinkFields(), motherLinkRepository = FakeMotherLinkRepository())

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(vm.visibleFields().any { it.questionCode == MOTHER_BENEFICIARY_ID })
    assertFalse(vm.isMotherLinkField(field(MOTHER_BENEFICIARY_ID)))
  }

  @Test
  fun `ML-9 the picker replaces the generic renderer only on the registered-mother path`() = runTest {
    val vm = viewModel(motherLinkFields(), motherLinkRepository = FakeMotherLinkRepository())

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(vm.isMotherLinkField(field(MOTHER_BENEFICIARY_ID)))
    assertFalse(vm.isMotherLinkField(field(CAREGIVER_NAME)))
  }

  // VM-11
  @Test
  fun `ML-10 the section is not ready until a mother is linked`() = runTest {
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isSectionReady("Personal Info"))

    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(vm.isSectionReady("Personal Info"))
  }

  // VM-17 — better a raw id the Sakhi can re-pick than a blank that reads as unanswered.
  @Test
  fun `ML-11 the label falls back to the raw id when the selection is not in the list`() = runTest {
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(mothers = emptyList()),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("mother-uuid-1", vm.selectedMotherLabel())
  }

  @Test
  fun `ML-12 the label resolves to the mother's name once the list is loaded`() = runTest {
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("Deepa T Test", vm.selectedMotherLabel())
  }

  @Test
  fun `ML-13 no mother is linked before a selection`() = runTest {
    val vm = viewModel(motherLinkFields(), motherLinkRepository = FakeMotherLinkRepository())
    assertNull(vm.selectedMotherLabel())
  }

  // VM-15
  @Test
  fun `ML-14 selecting a mother with no schema loaded is a no-op`() = runTest {
    val vm = viewModelNoSchema()

    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.uiState.value.selectedMotherId)
    assertTrue(vm.uiState.value.motherPrefilledCodes.isEmpty())
  }

  // VM-14 — prefill must never touch fields that describe the baby.
  @Test
  fun `ML-15 infant fields are never prefilled from the mother`() = runTest {
    val fields = motherLinkFields() + listOf(
      field("date_of_birth_of_infant", section = "Infant Details", inputType = "date"),
      field("name_of_the_child", section = "Infant Details"),
      field("sex_of_child", section = "Infant Details", inputType = "select"),
    )
    val vm = viewModel(
      fields,
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    val answers = vm.uiState.value.answers
    assertNull(answers.valueOf("date_of_birth_of_infant"))
    assertNull(answers.valueOf("name_of_the_child"))
    assertNull(answers.valueOf("sex_of_child"))
  }

  // VM-16 — the 183-day window is unaffected by prefill.
  @Test
  fun `ML-16 the registered-mother eligibility window still applies after prefill`() = runTest {
    val fields = motherLinkFields() +
      field("date_of_birth_of_infant", section = "Infant Details", inputType = "date")
    val vm = viewModel(
      fields,
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(sampleMother())),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    vm.setAnswer("date_of_birth_of_infant", vm.registrationDate.minusDays(200).toString())
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(ChildValidationError.INELIGIBLE_MOTHER, vm.uiState.value.validationError)
  }

  // VM-07 — an explicit re-pick is authoritative over an edit made in between.
  @Test
  fun `ML-17 re-selecting a different mother overwrites edited values`() = runTest {
    val first = sampleMother()
    val second = sampleMother(id = "mother-uuid-2", fullName = "Meera Meera")
    val vm = viewModel(
      motherLinkFields(),
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(listOf(first, second)),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(first)
    dispatcher.scheduler.advanceUntilIdle()
    vm.setAnswer(CAREGIVER_NAME, "Edited Between")

    vm.selectMother(second)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("Meera Meera", vm.uiState.value.answers.valueOf(CAREGIVER_NAME))
    assertEquals("mother-uuid-2", vm.uiState.value.answers.valueOf(MOTHER_BENEFICIARY_ID))
    assertTrue(vm.isPrefilledFromMother(CAREGIVER_NAME))
  }

  // Decision D2 / spec row 4. Flipping MotherPrefill.INHERIT_CONSENT_FROM_MOTHER reverses this.
  @Test
  fun `ML-18 consent is inherited when the mother consented`() = runTest {
    val fields = motherLinkFields() + field("did_we_receive_consent", section = "Consent", inputType = "radio")
    val vm = viewModel(
      fields,
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(
        mothers = listOf(sampleMother()),
        consent = LinkedMotherConsent(consentGiven = true),
      ),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    val expected = if (MotherPrefill.INHERIT_CONSENT_FROM_MOTHER) "yes" else null
    assertEquals(expected, vm.uiState.value.answers.valueOf("did_we_receive_consent"))
  }

  // A failed consent lookup must not fail the selection — geography/name still land.
  @Test
  fun `ML-19 an unavailable consent record does not block prefill`() = runTest {
    val fields = motherLinkFields() + field("did_we_receive_consent", section = "Consent", inputType = "radio")
    val vm = viewModel(
      fields,
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(mothers = listOf(sampleMother()), consent = null),
    )
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectMother(sampleMother())
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.uiState.value.answers.valueOf("did_we_receive_consent"))
    assertEquals("Deepa T Test", vm.uiState.value.answers.valueOf(CAREGIVER_NAME))
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

  // --- Registration date auto-populates with today (spec row 13) ------------------------------

  @Test
  fun `registration date is prefilled with today under the typo spelling v2 declares`() = runTest {
    val vm = viewModel(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    assertEquals(
      vm.registrationDate.toString(),
      vm.uiState.value.answers.valueOf(REGISTRATION_DATE_QUESTION_CODE),
    )
    // Prefilled with a valid value, so the tab is satisfied without the Sakhi touching the field —
    // the reported defect was this gate staying blocked on an empty required date.
    assertTrue(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `registration date is prefilled with today under the corrected spelling`() = runTest {
    val vm = viewModel(
      listOf(
        field(
          REGISTRATION_DATE_QUESTION_CODE_CORRECTED,
          section = "Personal Info",
          inputType = "date",
        ),
      ),
    )

    assertEquals(
      vm.registrationDate.toString(),
      vm.uiState.value.answers.valueOf(REGISTRATION_DATE_QUESTION_CODE_CORRECTED),
    )
    assertNull(vm.uiState.value.answers.valueOf(REGISTRATION_DATE_QUESTION_CODE))
  }

  @Test
  fun `a future registration date blocks its tab`() = runTest {
    val vm = viewModel(
      listOf(field(REGISTRATION_DATE_QUESTION_CODE, section = "Personal Info", inputType = "date")),
    )

    vm.setAnswer(REGISTRATION_DATE_QUESTION_CODE, vm.registrationDate.plusDays(1).toString())

    assertFalse(vm.isSectionReady("Personal Info"))
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

  // The ceilings are read from FormDateRuleset rather than retyped: it uses the SAME constants to
  // bound the date picker, so if prevention and detection ever drift apart these tests fail instead
  // of the Sakhi being handed a date the gate then refuses. FormDateRulesetTest ID-9 pins the
  // literal values against the backend's CHILD_AGE_CEILING_DAYS.
  private val ceilingDirect = FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT
  private val ceilingMotherLinked = FormDateRuleset.CHILD_AGE_CEILING_DAYS_MOTHER_LINKED

  @Test
  fun `CD-1 direct path allows exactly the ceiling in days and rejects one more`() = runTest {
    val vm = eligibilityViewModel()
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)

    vm.setAnswer(DATE_OF_BIRTH_OF_INFANT, vm.registrationDate.minusDays(ceilingDirect).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.uiState.value.validationError)
    assertTrue(vm.isReadyToSubmit())

    vm.setAnswer(DATE_OF_BIRTH_OF_INFANT, vm.registrationDate.minusDays(ceilingDirect + 1).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(ChildValidationError.INELIGIBLE_DIRECT, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `CD-2 registered-mother path allows exactly the ceiling in days and rejects one more`() = runTest {
    val vm = eligibilityViewModel()
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)

    vm.setAnswer(
      DATE_OF_BIRTH_OF_INFANT,
      vm.registrationDate.minusDays(ceilingMotherLinked).toString(),
    )
    dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.uiState.value.validationError)

    vm.setAnswer(
      DATE_OF_BIRTH_OF_INFANT,
      vm.registrationDate.minusDays(ceilingMotherLinked + 1).toString(),
    )
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(ChildValidationError.INELIGIBLE_MOTHER, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `CD-3 switching to the registered-mother path re-judges a DOB the direct path allowed`() = runTest {
    // The one case path-aware picker bounds CANNOT prevent: the value is already in state when the
    // path changes, so detection has to catch it. A DOB between the two ceilings is legal on the
    // direct path and illegal once a mother is linked.
    val betweenCeilings = ceilingMotherLinked + 50
    val vm = eligibilityViewModel()
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)
    vm.setAnswer(DATE_OF_BIRTH_OF_INFANT, vm.registrationDate.minusDays(betweenCeilings).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.uiState.value.validationError)

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(ChildValidationError.INELIGIBLE_MOTHER, vm.uiState.value.validationError)
    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `CD-4 switching back to the direct path clears the eligibility error`() = runTest {
    val betweenCeilings = ceilingMotherLinked + 50
    val vm = eligibilityViewModel()
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
    vm.setAnswer(DATE_OF_BIRTH_OF_INFANT, vm.registrationDate.minusDays(betweenCeilings).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(ChildValidationError.INELIGIBLE_MOTHER, vm.uiState.value.validationError)

    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.uiState.value.validationError)
  }

  @Test
  fun `CD-5 a blank infant DOB is a required-field question, not an eligibility error`() = runTest {
    val vm = eligibilityViewModel()
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.uiState.value.validationError)
    // Still blocked, but by the required-field gate — the field is declared required here.
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

  // --- Consent refused exits registration -------------------------------------------------------
  //
  // Answering "no" to `did_we_receive_consent` no longer produces an inline ChildValidationError —
  // it aborts the registration: the ViewModel emits a one-shot event and the screen toasts +
  // navigates Home. The age-eligibility errors are unaffected and still gate inline.

  /**
   * Collects [DynamicChildRegistrationViewModel.consentRefused] for the duration of [block].
   * Turbine isn't a dependency, so the flow is drained by a background collector.
   *
   * The collector MUST run on an [UnconfinedTestDispatcher]: it starts eagerly, so the subscription
   * is live before [block] emits. On the class's [StandardTestDispatcher] the `launch` only *queues*
   * the collector, and `consentRefused` has `replay = 0` — a `tryEmit` with no subscriber is
   * silently dropped, so the event would be lost no matter how far the scheduler is advanced.
   */
  private fun TestScope.recordConsentRefusals(
    vm: DynamicChildRegistrationViewModel,
    block: () -> Unit,
  ): List<Unit> {
    val events = mutableListOf<Unit>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      vm.consentRefused.collect { events += it }
    }
    block()
    dispatcher.scheduler.advanceUntilIdle()
    return events
  }

  private fun consentOnlyForm() =
    listOf(field("did_we_receive_consent", section = "Consent", required = true, inputType = "radio"))

  @Test
  fun `answering consent no emits the consent-refused event`() = runTest {
    val vm = viewModel(consentOnlyForm())

    val events = recordConsentRefusals(vm) { vm.setAnswer("did_we_receive_consent", "no") }

    assertEquals(1, events.size)
  }

  @Test
  fun `answering consent yes emits nothing`() = runTest {
    val vm = viewModel(consentOnlyForm())

    val events = recordConsentRefusals(vm) { vm.setAnswer("did_we_receive_consent", "yes") }

    assertTrue(events.isEmpty())
  }

  @Test
  fun `consent no no longer sets a validation error`() = runTest {
    val vm = viewModel(consentOnlyForm())

    vm.setAnswer("did_we_receive_consent", "no")
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.uiState.value.validationError)
  }

  @Test
  fun `consent no no longer blocks the Consent tab`() = runTest {
    val vm = viewModel(consentOnlyForm())

    vm.setAnswer("did_we_receive_consent", "no")
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(vm.isSectionReady("Consent"))
  }

  // The surviving ChildValidationError members are already covered by the CD-1..CD-5 eligibility
  // tests above; they double as the regression guard that removing CONSENT_REFUSED from the enum
  // did not disturb the age gates.

  // Prefill writes consent through a different path than setAnswer and only ever writes "yes" —
  // inheriting a mother's consent must never bounce the Sakhi out of the form.
  @Test
  fun `ML-18 mother-inherited consent does not emit the consent-refused event`() = runTest {
    val fields = motherLinkFields() + field("did_we_receive_consent", section = "Consent", inputType = "radio")
    val vm = viewModel(
      fields,
      geography = motherLinkGeography(),
      motherLinkRepository = FakeMotherLinkRepository(
        mothers = listOf(sampleMother()),
        consent = LinkedMotherConsent(consentGiven = true),
      ),
    )

    val events = recordConsentRefusals(vm) {
      vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_REGISTERED_MOTHER)
      dispatcher.scheduler.advanceUntilIdle()
      vm.selectMother(sampleMother())
    }

    assertTrue(events.isEmpty())
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

  // ---------------------------------------------------------------------------------------------
  // Geography (CR-020 regression — the child flow never received the CR-018 fix)
  //
  // Symptom in the field: Pada / PHC / Sub Centre rendered as EMPTY dropdowns and the "Infant
  // Details" button stayed disabled no matter what the Sakhi typed. Cause: optionsFor() called the
  // static GeographyRepository cascade (whose PADA/PHC/SUBCENTRE branches all bail out with
  // emptyList() unless a village answer already exists) instead of the backend's version geography,
  // and load() had no prefillAutoSelectedGeography() step to write those answers at all.
  //
  // FakeGeographyRepository below returns emptyList() for every level, so any regression back to
  // the static cascade makes these tests fail rather than accidentally pass.
  // ---------------------------------------------------------------------------------------------

  /** One backend unit per level, mirroring a real `active-version` `geography` array. */
  private fun sampleGeography() = listOf(
    FormGeographyUnit("state-uuid", "STATE", "Maharashtra"),
    FormGeographyUnit("district-uuid", "DISTRICT", "Nandurbar"),
    FormGeographyUnit("block-uuid", "BLOCK", "Dhadgaon"),
    FormGeographyUnit("village-uuid", "VILLAGE", "Sample Village"),
    FormGeographyUnit("pada-uuid", "PADA", "Sample Pada"),
    FormGeographyUnit("phc-uuid", "PHC", "Dhadgaon PHC"),
    FormGeographyUnit("subcentre-uuid", "SUBCENTRE", "Sample Sub-centre"),
  )

  /** The three fields that rendered blank on the Personal Info tab, plus the village they cascade
   * from under the old static resolver. */
  private fun geographyFields() = listOf(
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
    assertEquals("pada-uuid", answers.valueOf("beneficary_pada_name"))
    assertEquals("subcentre-uuid", answers.valueOf("name_of_sub_center"))
    assertEquals("village-uuid", answers.valueOf("name_of_the_revenue_village_grampanchayat"))
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
  fun `Pada PHC and Sub Centre offer options without a village being picked first`() = runTest {
    // Under the static cascade each of these returned emptyList() until a village answer existed,
    // which is what left them unfillable.
    val vm = viewModel(geographyFields(), geography = sampleGeography())

    listOf("beneficary_pada_name", "beneficary_phc_name", "name_of_sub_center").forEach { code ->
      val options = vm.optionsFor(field(code, section = "Personal Info", inputType = "select"))
      assertTrue("Expected options for $code, got none", options.isNotEmpty())
    }
  }

  @Test
  fun `Personal Info section becomes ready once geography auto-fills`() = runTest {
    // The user-visible symptom: the "Infant Details" button was permanently disabled because these
    // required geography fields could never be answered.
    val vm = viewModel(geographyFields(), geography = sampleGeography())

    assertTrue(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `a geography level the backend omits stays unanswered rather than taking a wrong id`() = runTest {
    // A real backend gap must surface as an unfillable, submit-gated field — never as a silently
    // wrong geographyUnitId that 422s at submit time.
    val withoutPhc = sampleGeography().filterNot { it.geoType == "PHC" }
    val vm = viewModel(geographyFields(), geography = withoutPhc)

    assertNull(vm.uiState.value.answers.valueOf("beneficary_phc_name"))
    assertTrue(
      vm.optionsFor(field("beneficary_phc_name", section = "Personal Info", inputType = "select"))
        .isEmpty(),
    )
    assertFalse(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `a level with several backend units stays an interactive dropdown and is not auto-filled`() = runTest {
    val twoPhcs = sampleGeography() + FormGeographyUnit("phc-uuid-2", "PHC", "Second PHC")
    val vm = viewModel(geographyFields(), geography = twoPhcs)

    assertNull(vm.uiState.value.answers.valueOf("beneficary_phc_name"))
    assertEquals(
      2,
      vm.optionsFor(field("beneficary_phc_name", section = "Personal Info", inputType = "select")).size,
    )
  }

  // ---------------------------------------------------------------------------------------------
  // mother_beneficiary_id gating — app fallback vs schema `visibleWhen`.
  //
  // The backend validator (form-validation.ts) only skips a required field when the SCHEMA declares
  // visibleWhen. An app-only rule is invisible to it, which is what produced
  // `422 — Missing required field: mother_beneficiary_id` on the direct path. The fallback below is
  // written to retire itself the moment the schema carries the rule.
  // ---------------------------------------------------------------------------------------------

  private val whoAreYouRegistering = "who_are_you_registering_in_the_program"
  private val pathDirect = "child_directly_mother_not_registered_in_the_program"
  private val pathRegisteredMother = "child_of_a_registered_pregnant_woman"

  private fun motherIdFields(visibleWhen: FormVisibleWhen? = null) = listOf(
    field(whoAreYouRegistering, section = "Consent", inputType = "radio"),
    FormFieldSchema(
      label = "mother_beneficiary_id",
      required = true,
      inputTypeRaw = "text",
      questionCode = "mother_beneficiary_id",
      section = "Personal Info",
      visibleWhen = visibleWhen,
    ),
  )

  @Test
  fun `without schema visibleWhen the app fallback hides mother_beneficiary_id on the direct path`() = runTest {
    val vm = viewModel(motherIdFields())
    vm.setAnswer(whoAreYouRegistering, pathDirect)

    assertFalse(vm.visibleFields().any { it.questionCode == "mother_beneficiary_id" })
  }

  @Test
  fun `mother_beneficiary_id is shown on the registered-mother path`() = runTest {
    val vm = viewModel(motherIdFields())
    vm.setAnswer(whoAreYouRegistering, pathRegisteredMother)

    assertTrue(vm.visibleFields().any { it.questionCode == "mother_beneficiary_id" })
  }

  @Test
  fun `once the schema declares visibleWhen the generic evaluator hides the field and the fallback retires`() = runTest {
    // This is the state after the pending schema change. The app fallback must NOT be what's doing
    // the hiding any more — FormVisibilityEvaluator handles it, and both app and backend derive the
    // same rule from one declaration.
    val schemaRule = FormVisibleWhen(field = whoAreYouRegistering, operator = "eq", value = pathRegisteredMother)
    val vm = viewModel(motherIdFields(schemaRule))

    vm.setAnswer(whoAreYouRegistering, pathDirect)
    assertFalse(vm.visibleFields().any { it.questionCode == "mother_beneficiary_id" })

    vm.setAnswer(whoAreYouRegistering, pathRegisteredMother)
    assertTrue(vm.visibleFields().any { it.questionCode == "mother_beneficiary_id" })
  }

  // ---------------------------------------------------------------------------------------------
  // Mother's DOB (Infant Registration spec row 20.0, "Age or DOB of the mother") — age must floor
  // into 10..50.
  //
  // Two defects covered here: the mother-DOB question code had no entry in FormDateRuleset (so it was
  // never checked at all), and the child form's gate never consulted FormDateRuleset (so even a
  // known-bad date only produced an inline message while Next/Submit stayed enabled).
  // ---------------------------------------------------------------------------------------------

  private val motherDob = MOTHER_DATE_OF_BIRTH

  private fun motherDobFields() = listOf(
    field("name_of_the_child", section = "Infant Details"),
    field(motherDob, section = "Personal Info", inputType = "date"),
  )

  /** Answers both required fields, with the mother's DOB [years] before the registration date. */
  private fun DynamicChildRegistrationViewModel.answerAll(years: Long) {
    setAnswer("name_of_the_child", "Aarav")
    setAnswer(motherDob, registrationDate.minusYears(years).toString())
  }

  @Test
  fun `CG-1 a mother dob below the age floor blocks submission`() = runTest {
    val vm = viewModel(motherDobFields())

    vm.answerAll(years = 8)
    dispatcher.scheduler.advanceUntilIdle()

    assertFalse(vm.isReadyToSubmit())
  }

  @Test
  fun `CG-2 correcting the mother dob into range unblocks submission`() = runTest {
    val vm = viewModel(motherDobFields())

    vm.answerAll(years = 8)
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isReadyToSubmit())

    vm.setAnswer(motherDob, vm.registrationDate.minusYears(25).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `CG-3 a mother dob above the age ceiling blocks its section's next button`() = runTest {
    val vm = viewModel(motherDobFields())

    vm.answerAll(years = 55)
    dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.isSectionReady("Personal Info"))

    vm.setAnswer(motherDob, vm.registrationDate.minusYears(50).toString())
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.isSectionReady("Personal Info"))
  }

  @Test
  fun `CG-4 an optional blank mother dob does not block the gate`() = runTest {
    // The live schema declares this field required = false. Blank must stay a required-field
    // question, never a range error.
    val vm = viewModel(
      listOf(
        field("name_of_the_child", section = "Infant Details"),
        field(motherDob, section = "Personal Info", required = false, inputType = "date"),
      ),
    )

    vm.setAnswer("name_of_the_child", "Aarav")
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(vm.uiState.value.answers.valueOf(motherDob))
    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `CG-5 the shared date gate does not double-gate the infant DOB`() = runTest {
    // date_of_birth_of_infant is BOUNDED by FormDateRuleset (picker) but reports no violation from
    // it — its eligibility windows are this ViewModel's own gate. So allDatesValid must stay inert
    // here: a valid infant DOB must not be rejected, and an invalid one must be reported once, by
    // validationError only. FormDateRulesetTest ID-6/ID-7 lock the other half of that contract.
    val vm = eligibilityViewModel()
    vm.setAnswer(WHO_ARE_YOU_REGISTERING, PATH_DIRECT)

    vm.setAnswer(DATE_OF_BIRTH_OF_INFANT, vm.registrationDate.minusDays(30).toString())
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(vm.isReadyToSubmit())
  }

  @Test
  fun `CG-6 an out-of-range mother dob is a no-op at submit`() = runTest {
    // The gate must actually stop the network call, not just grey out the button.
    val draftRepository = FakeChildDraftRepository(ChildFormSubmitResult.Synced)
    val vm = viewModel(motherDobFields(), draftRepository)

    vm.answerAll(years = 8)
    dispatcher.scheduler.advanceUntilIdle()
    vm.submit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, draftRepository.submitCallCount)
    assertEquals(SubmissionState.Idle, vm.uiState.value.submissionState)
  }

  @Test
  fun `an existing draft answer is not clobbered by geography prefill`() = runTest {
    val vm = viewModel(geographyFields(), geography = sampleGeography())
    vm.setAnswer("beneficary_phc_name", "phc-chosen-earlier")

    vm.load()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("phc-chosen-earlier", vm.uiState.value.answers.valueOf("beneficary_phc_name"))
  }
}
