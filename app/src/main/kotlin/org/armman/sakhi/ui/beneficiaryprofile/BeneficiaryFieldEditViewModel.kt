package org.armman.sakhi.ui.beneficiaryprofile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.EditableFieldCodes
import org.armman.sakhi.data.forms.FieldEditResult
import org.armman.sakhi.data.forms.FieldEditsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormHiddenFieldReset
import org.armman.sakhi.data.forms.FormVisibilityEvaluator
import org.armman.sakhi.data.forms.FormsRepository
import javax.inject.Inject

internal const val FIELD_EDIT_NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
internal const val FIELD_EDIT_NAV_ARG_TYPE = "type"

private const val MOTHER_REGISTRATION_FORM_CODE = "MOTHER_REGISTRATION"
private const val CHILD_REGISTRATION_FORM_CODE = "CHILD_REGISTRATION"

/** One editable field, resolved against the active [org.armman.sakhi.data.forms.FormVersion]
 * schema rather than hardcoded here — label/type/options come straight from the same backend
 * schema the registration form itself renders from, so a future republish (a relabeled question, a
 * changed option list) is picked up automatically instead of drifting out of sync with a
 * client-side copy. */
data class EditableField(
  val schema: FormFieldSchema,
)

sealed interface FieldEditUiState {
  data object Loading : FieldEditUiState

  /** No local record to edit against — either this device never registered this beneficiary (a
   * different device/app install did), or it did but the draft predates CR-Registration-Edit's
   * submission-id capture. [reason] is shown as-is; there is deliberately no retry action, since
   * nothing here is a transient failure. */
  data class NotEditable(val reason: String) : FieldEditUiState

  data class Content(
    val formTitle: String,
    val fields: List<EditableField>,
    /** [fields] currently shown, honoring [FormVisibilityEvaluator]/schema `visibleWhen` against
     * the live [values] — e.g. Para/Living children/Abortions/Still births/Dead children stay
     * hidden while Gravida is 1, exactly like the enrollment form. [fields] itself always keeps the
     * full allowlist so a value a Sakhi entered before hiding a field is still tracked for [save]'s
     * diff (see [FormHiddenFieldReset]). */
    val visibleFields: List<EditableField>,
    val values: Map<String, String>,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
  ) : FieldEditUiState
}

sealed interface FieldEditEvent {
  data object SaveSucceeded : FieldEditEvent
}

/**
 * Backs the Beneficiary Profile's Edit stub (CR-Registration-Edit Phase 1) — field correction for
 * an already-submitted MOTHER_REGISTRATION/CHILD_REGISTRATION form via `PATCH
 * /form-submissions/:id/answers`. Scoped to exactly these two form codes; every other form on the
 * backend's editable-fields contract has no screen yet (see [EditableFieldCodes]'s own doc).
 */
@HiltViewModel
class BeneficiaryFieldEditViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val motherDraftRepository: DynamicFormDraftRepository,
  private val childDraftRepository: ChildFormDraftRepository,
  private val fieldEditsRepository: FieldEditsRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val beneficiaryId: String = savedStateHandle[FIELD_EDIT_NAV_ARG_BENEFICIARY_ID] ?: ""
  private val beneficiaryType: BeneficiaryType = runCatching {
    BeneficiaryType.valueOf(savedStateHandle[FIELD_EDIT_NAV_ARG_TYPE] ?: "")
  }.getOrDefault(BeneficiaryType.MOTHER)

  private val formCode =
    if (beneficiaryType == BeneficiaryType.MOTHER) MOTHER_REGISTRATION_FORM_CODE else CHILD_REGISTRATION_FORM_CODE

  private val _uiState = MutableStateFlow<FieldEditUiState>(FieldEditUiState.Loading)
  val uiState: StateFlow<FieldEditUiState> = _uiState.asStateFlow()

  private val events = Channel<FieldEditEvent>(Channel.BUFFERED)
  val eventFlow: Flow<FieldEditEvent> = events.receiveAsFlow()

  /** The submission id + local draft key resolved on [load] — needed by [save], kept out of
   * [FieldEditUiState] since the UI never renders either. */
  private var submissionId: String? = null
  private var localBeneficiaryId: String? = null

  /** The values as loaded, before any edit — [save] diffs against this so only fields the Sakhi
   * actually touched (directly, or via a [FormHiddenFieldReset] cascade) are sent, keeping the
   * all-or-nothing PATCH call minimal and its local-cache write
   * ([DynamicFormDraftRepository.applyFieldEdits]) accurate. */
  private var originalValues: Map<String, String> = emptyMap()

  /** Every field on the active [formCode] schema, not just the editable allowlist —
   * [FormHiddenFieldReset]/[FormVisibilityEvaluator] need the full chain (a `visibleWhen` can
   * gate on a field outside the allowlist) to decide correctly which allowlisted fields are
   * currently visible. */
  private var allSchemaFields: List<FormFieldSchema> = emptyList()

  /** The full submission's answers, kept in sync with every edit (including
   * [FormHiddenFieldReset] resets) so visibility and the next edit's reset both evaluate against
   * up-to-date state — mirrors [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel]'s
   * own pattern for the same reason. */
  private var answers: FormAnswers = FormAnswers()

  init {
    load()
  }

  private fun load() {
    viewModelScope.launch {
      _uiState.update { FieldEditUiState.Loading }

      val editable = if (beneficiaryType == BeneficiaryType.MOTHER) {
        motherDraftRepository.getEditableSubmission(beneficiaryId)
      } else {
        childDraftRepository.getEditableSubmission(beneficiaryId)
      }

      if (editable == null || editable.remoteSubmissionId == null) {
        _uiState.update {
          FieldEditUiState.NotEditable(
            "This record hasn't finished uploading from this device yet, so it can't be edited here.",
          )
        }
        return@launch
      }

      val allowlist = EditableFieldCodes.forFormCode(formCode)
      val formVersion = formsRepository.getActiveVersion(formCode)
      val fields = formVersion?.schemaJson
        ?.filter { it.questionCode in allowlist }
        // Stable, predictable order for the Sakhi — the allowlist's own order, not whatever order
        // the schema happens to declare fields in.
        ?.sortedBy { allowlist.indexOf(it.questionCode) }
        .orEmpty()

      if (fields.isEmpty()) {
        _uiState.update {
          FieldEditUiState.NotEditable("Couldn't load this form's fields. Please connect to the internet and try again.")
        }
        return@launch
      }

      submissionId = editable.remoteSubmissionId
      localBeneficiaryId = editable.localBeneficiaryId
      // formVersion is guaranteed non-null here: `fields` (derived from formVersion?.schemaJson)
      // would be empty otherwise, and that case already returned above — but the compiler can't
      // follow that through the safe-call chain, so re-fetch with an explicit null check instead
      // of relying on smart-cast.
      val nonNullFormVersion = requireNotNull(formVersion) { "unreachable: fields is non-empty" }
      allSchemaFields = nonNullFormVersion.schemaJson
      answers = editable.answers
      val values = fields.associate { it.questionCode to (editable.answers.valueOf(it.questionCode) ?: "") }
      originalValues = values
      val editableFields = fields.map { EditableField(it) }

      _uiState.update {
        FieldEditUiState.Content(
          formTitle = if (beneficiaryType == BeneficiaryType.MOTHER) "Mother details" else "Child details",
          fields = editableFields,
          visibleFields = visibleFields(editableFields),
          values = values,
        )
      }
    }
  }

  /** [fields] currently visible per [FormVisibilityEvaluator] against [answers] — same evaluator,
   * same "hidden until its governing field is answered" fail-safe, the enrollment form's
   * `visibleFields()` computed properties use (see e.g.
   * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.visibleFields]). */
  private fun visibleFields(fields: List<EditableField>): List<EditableField> =
    fields.filter { FormVisibilityEvaluator.isVisible(it.schema, answers) }

  fun onValueChange(fieldCode: String, value: String) {
    val state = _uiState.value
    if (state !is FieldEditUiState.Content) return

    // Mirrors the enrollment form's own edit path: apply the keystroke, then let
    // FormHiddenFieldReset settle any field that just went out of view (e.g. Gravida 2 -> 1
    // hides and zeroes Para/Living children/Abortions/Still births/Dead children) so a stale
    // value never lingers unseen in the PATCH payload.
    val previousAnswers = answers
    val updatedAnswers = previousAnswers.withSingleValue(fieldCode, value)
    val settledAnswers = FormHiddenFieldReset.apply(allSchemaFields, previousAnswers, updatedAnswers)
    answers = settledAnswers

    val values = state.fields.associate {
      it.schema.questionCode to (settledAnswers.valueOf(it.schema.questionCode) ?: "")
    }
    _uiState.update {
      state.copy(values = values, visibleFields = visibleFields(state.fields), errorMessage = null)
    }
  }

  fun save() {
    val state = _uiState.value
    if (state !is FieldEditUiState.Content || state.isSaving) return
    val submissionId = submissionId ?: return
    val localBeneficiaryId = localBeneficiaryId ?: return

    val edits = state.values.filter { (code, value) -> originalValues[code] != value }
    if (edits.isEmpty()) {
      viewModelScope.launch { events.send(FieldEditEvent.SaveSucceeded) }
      return
    }

    _uiState.update { state.copy(isSaving = true, errorMessage = null) }
    viewModelScope.launch {
      when (val result = fieldEditsRepository.submitEdits(submissionId, edits)) {
        is FieldEditResult.Success -> {
          if (beneficiaryType == BeneficiaryType.MOTHER) {
            motherDraftRepository.applyFieldEdits(localBeneficiaryId, edits)
          } else {
            childDraftRepository.applyFieldEdits(localBeneficiaryId, edits)
          }
          originalValues = state.values
          _uiState.update { (it as? FieldEditUiState.Content)?.copy(isSaving = false) ?: it }
          events.send(FieldEditEvent.SaveSucceeded)
        }

        is FieldEditResult.UnknownFieldCodes ->
          _uiState.update { (it as? FieldEditUiState.Content)?.copy(isSaving = false, errorMessage = result.message) ?: it }

        is FieldEditResult.NotEditable ->
          _uiState.update { (it as? FieldEditUiState.Content)?.copy(isSaving = false, errorMessage = result.message) ?: it }

        is FieldEditResult.Failed ->
          _uiState.update {
            (it as? FieldEditUiState.Content)?.copy(
              isSaving = false,
              errorMessage = result.message ?: "Couldn't save. Please try again.",
            ) ?: it
          }
      }
    }
  }
}
