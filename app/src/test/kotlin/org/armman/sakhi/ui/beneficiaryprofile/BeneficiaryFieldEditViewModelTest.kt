package org.armman.sakhi.ui.beneficiaryprofile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.forms.EditableSubmissionInfo
import org.armman.sakhi.data.forms.FieldEditResult
import org.armman.sakhi.data.forms.FieldEditsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Covers [BeneficiaryFieldEditViewModel] (CR-Registration-Edit Phase 1) — schema-driven field
 * loading, the diff-only edit submission, and both documented `PATCH
 * /form-submissions/:id/answers` error shapes surfacing verbatim.
 */
class BeneficiaryFieldEditViewModelTest {

  private class FakeFormsRepository : FormsRepository {
    var version: FormVersion? = null
    override suspend fun getActiveVersion(formCode: String): FormVersion? = version
  }

  private class FakeMotherDraftRepository : DynamicFormDraftRepository {
    var editable: EditableSubmissionInfo? = null
    val appliedEdits = mutableListOf<Pair<String, Map<String, String>>>()

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
    ): DynamicFormSubmitResult = DynamicFormSubmitResult.Synced

    override suspend fun dismissNewPregnancyPrompt(localBeneficiaryId: String) = Unit

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = flowOf(emptyList())

    override suspend fun getEditableSubmission(remoteBeneficiaryId: String): EditableSubmissionInfo? = editable

    override suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>) {
      appliedEdits += localBeneficiaryId to edits
    }

    override suspend fun getRemoteBeneficiaryId(localBeneficiaryId: String): String? = null
  }

  private class FakeChildDraftRepository : ChildFormDraftRepository {
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
    ) = throw NotImplementedError("not exercised — mother-only test cases")

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = flowOf(emptyList())

    override suspend fun getEditableSubmission(remoteBeneficiaryId: String): EditableSubmissionInfo? = null

    override suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>) = Unit
  }

  private class FakeFieldEditsRepository : FieldEditsRepository {
    var result: FieldEditResult = FieldEditResult.Success
    var lastSubmissionId: String? = null
    var lastEdits: Map<String, String>? = null

    override suspend fun submitEdits(submissionId: String, edits: Map<String, String>): FieldEditResult {
      lastSubmissionId = submissionId
      lastEdits = edits
      return result
    }
  }

  private val dispatcher = StandardTestDispatcher()
  private lateinit var formsRepository: FakeFormsRepository
  private lateinit var motherDraftRepository: FakeMotherDraftRepository
  private lateinit var childDraftRepository: FakeChildDraftRepository
  private lateinit var fieldEditsRepository: FakeFieldEditsRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    formsRepository = FakeFormsRepository()
    motherDraftRepository = FakeMotherDraftRepository()
    childDraftRepository = FakeChildDraftRepository()
    fieldEditsRepository = FakeFieldEditsRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun field(
    code: String,
    inputType: String = "text",
    visibleWhen: org.armman.sakhi.data.forms.FormVisibleWhen? = null,
  ) = FormFieldSchema(
    label = code,
    required = false,
    inputTypeRaw = inputType,
    questionCode = code,
    visibleWhen = visibleWhen,
  )

  private fun createViewModel(beneficiaryId: String = "server-beneficiary-1"): BeneficiaryFieldEditViewModel {
    val handle = SavedStateHandle(
      mapOf(
        FIELD_EDIT_NAV_ARG_BENEFICIARY_ID to beneficiaryId,
        FIELD_EDIT_NAV_ARG_TYPE to BeneficiaryType.MOTHER.name,
      ),
    )
    val viewModel = BeneficiaryFieldEditViewModel(
      formsRepository,
      motherDraftRepository,
      childDraftRepository,
      fieldEditsRepository,
      handle,
    )
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `load populates Content from the allowlisted schema fields, pre-filled from local answers`() = runTest {
    motherDraftRepository.editable = EditableSubmissionInfo(
      localBeneficiaryId = "local-1",
      formVersionId = "version-1",
      remoteSubmissionId = "server-sub-1",
      answers = FormAnswers(singleValues = mapOf("mobile_number" to "9876543210")),
    )
    formsRepository.version = FormVersion(
      id = "version-1",
      formDefinitionId = "def-1",
      versionNo = "v1",
      schemaJson = listOf(field("mobile_number"), field("lmp_date")), // lmp_date is NOT allowlisted
      validationJson = emptyList(),
      effectiveFrom = "2026-01-01",
      effectiveTo = null,
      status = "PUBLISHED",
    )

    val viewModel = createViewModel()

    val state = viewModel.uiState.first()
    assertTrue(state is FieldEditUiState.Content)
    val content = state as FieldEditUiState.Content
    assertEquals(listOf("mobile_number"), content.fields.map { it.schema.questionCode })
    assertEquals("9876543210", content.values["mobile_number"])
  }

  @Test
  fun `no local editable submission surfaces NotEditable`() = runTest {
    motherDraftRepository.editable = null

    val viewModel = createViewModel()

    assertTrue(viewModel.uiState.first() is FieldEditUiState.NotEditable)
  }

  @Test
  fun `a draft synced before submission-id capture surfaces NotEditable`() = runTest {
    motherDraftRepository.editable = EditableSubmissionInfo(
      localBeneficiaryId = "local-1",
      formVersionId = "version-1",
      remoteSubmissionId = null,
      answers = FormAnswers(),
    )
    formsRepository.version = FormVersion(
      id = "version-1",
      formDefinitionId = "def-1",
      versionNo = "v1",
      schemaJson = listOf(field("mobile_number")),
      validationJson = emptyList(),
      effectiveFrom = "2026-01-01",
      effectiveTo = null,
      status = "PUBLISHED",
    )

    val viewModel = createViewModel()

    assertTrue(viewModel.uiState.first() is FieldEditUiState.NotEditable)
  }

  @Test
  fun `save only sends fields that actually changed and applies them to the local cache on success`() = runTest {
    motherDraftRepository.editable = EditableSubmissionInfo(
      localBeneficiaryId = "local-1",
      formVersionId = "version-1",
      remoteSubmissionId = "server-sub-1",
      answers = FormAnswers(singleValues = mapOf("mobile_number" to "9876543210", "who_owns_the_phone" to "self")),
    )
    formsRepository.version = FormVersion(
      id = "version-1",
      formDefinitionId = "def-1",
      versionNo = "v1",
      schemaJson = listOf(field("mobile_number"), field("who_owns_the_phone")),
      validationJson = emptyList(),
      effectiveFrom = "2026-01-01",
      effectiveTo = null,
      status = "PUBLISHED",
    )
    val viewModel = createViewModel()

    viewModel.onValueChange("mobile_number", "9999999999")
    viewModel.save()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("server-sub-1", fieldEditsRepository.lastSubmissionId)
    assertEquals(mapOf("mobile_number" to "9999999999"), fieldEditsRepository.lastEdits)
    assertEquals(
      listOf("local-1" to mapOf("mobile_number" to "9999999999")),
      motherDraftRepository.appliedEdits,
    )
  }

  @Test
  fun `Para etc stay hidden while Gravida is 1, exactly like the enrollment form`() = runTest {
    // Mirrors the enrollment form's own Gravida-gated obstetric-history block (see
    // FormHiddenFieldReset's KDoc) — the edit screen must honor the same visibleWhen the
    // backend schema declares, not show every allowlisted field unconditionally.
    val gravidaGated = org.armman.sakhi.data.forms.FormVisibleWhen(
      field = "gravida_total_number_of_pregnancies",
      value = "2",
      operator = "gte",
    )
    motherDraftRepository.editable = EditableSubmissionInfo(
      localBeneficiaryId = "local-1",
      formVersionId = "version-1",
      remoteSubmissionId = "server-sub-1",
      answers = FormAnswers(singleValues = mapOf("gravida_total_number_of_pregnancies" to "1")),
    )
    formsRepository.version = FormVersion(
      id = "version-1",
      formDefinitionId = "def-1",
      versionNo = "v1",
      schemaJson = listOf(
        field("gravida_total_number_of_pregnancies"),
        field("para_number_of_births_after_24_weeks", visibleWhen = gravidaGated),
        field("living_children", visibleWhen = gravidaGated),
      ),
      validationJson = emptyList(),
      effectiveFrom = "2026-01-01",
      effectiveTo = null,
      status = "PUBLISHED",
    )

    val viewModel = createViewModel()

    val loaded = viewModel.uiState.first() as FieldEditUiState.Content
    // Still tracked (for save's diff / a future value) but not shown while Gravida is 1.
    assertEquals(
      listOf("gravida_total_number_of_pregnancies", "para_number_of_births_after_24_weeks", "living_children"),
      loaded.fields.map { it.schema.questionCode },
    )
    assertEquals(listOf("gravida_total_number_of_pregnancies"), loaded.visibleFields.map { it.schema.questionCode })

    // Raising Gravida to 2 reveals them.
    viewModel.onValueChange("gravida_total_number_of_pregnancies", "2")
    val revealed = viewModel.uiState.first() as FieldEditUiState.Content
    assertEquals(
      listOf("gravida_total_number_of_pregnancies", "para_number_of_births_after_24_weeks", "living_children"),
      revealed.visibleFields.map { it.schema.questionCode },
    )
  }

  @Test
  fun `dropping Gravida back to 1 resets and re-hides Para etc, and the reset is sent on save`() = runTest {
    val gravidaGated = org.armman.sakhi.data.forms.FormVisibleWhen(
      field = "gravida_total_number_of_pregnancies",
      value = "2",
      operator = "gte",
    )
    motherDraftRepository.editable = EditableSubmissionInfo(
      localBeneficiaryId = "local-1",
      formVersionId = "version-1",
      remoteSubmissionId = "server-sub-1",
      answers = FormAnswers(
        singleValues = mapOf(
          "gravida_total_number_of_pregnancies" to "2",
          "para_number_of_births_after_24_weeks" to "1",
        ),
      ),
    )
    formsRepository.version = FormVersion(
      id = "version-1",
      formDefinitionId = "def-1",
      versionNo = "v1",
      schemaJson = listOf(
        field("gravida_total_number_of_pregnancies"),
        field("para_number_of_births_after_24_weeks", visibleWhen = gravidaGated),
      ),
      validationJson = emptyList(),
      effectiveFrom = "2026-01-01",
      effectiveTo = null,
      status = "PUBLISHED",
    )
    val viewModel = createViewModel()

    viewModel.onValueChange("gravida_total_number_of_pregnancies", "1")
    val state = viewModel.uiState.first() as FieldEditUiState.Content
    assertEquals(listOf("gravida_total_number_of_pregnancies"), state.visibleFields.map { it.schema.questionCode })
    // Para is an obstetric-count field — FormHiddenFieldReset zeroes it, not blanks it.
    assertEquals("0", state.values["para_number_of_births_after_24_weeks"])

    viewModel.save()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      mapOf("gravida_total_number_of_pregnancies" to "1", "para_number_of_births_after_24_weeks" to "0"),
      fieldEditsRepository.lastEdits,
    )
  }

  @Test
  fun `save surfaces the backend's UnknownFieldCodes message verbatim`() = runTest {
    motherDraftRepository.editable = EditableSubmissionInfo(
      localBeneficiaryId = "local-1",
      formVersionId = "version-1",
      remoteSubmissionId = "server-sub-1",
      answers = FormAnswers(singleValues = mapOf("mobile_number" to "9876543210")),
    )
    formsRepository.version = FormVersion(
      id = "version-1",
      formDefinitionId = "def-1",
      versionNo = "v1",
      schemaJson = listOf(field("mobile_number")),
      validationJson = emptyList(),
      effectiveFrom = "2026-01-01",
      effectiveTo = null,
      status = "PUBLISHED",
    )
    fieldEditsRepository.result = FieldEditResult.UnknownFieldCodes("Unknown fieldCode(s): not_a_real_field.")
    val viewModel = createViewModel()

    viewModel.onValueChange("mobile_number", "9999999999")
    viewModel.save()
    dispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.uiState.first() as FieldEditUiState.Content
    assertEquals("Unknown fieldCode(s): not_a_real_field.", content.errorMessage)
    assertTrue(motherDraftRepository.appliedEdits.isEmpty())
  }
}
