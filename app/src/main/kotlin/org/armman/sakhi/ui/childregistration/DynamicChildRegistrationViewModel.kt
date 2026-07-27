package org.armman.sakhi.ui.childregistration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSubmitResult
import org.armman.sakhi.data.childregistration.ChildNonRenderableQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormComputedFieldEvaluator
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormCrossFieldValidator
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormVisibilityEvaluator
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.newLocalSubmissionUuid
import org.armman.sakhi.data.lookup.LookupRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/** The dynamic form CR-020 covers. */
private const val FORM_CODE = "CHILD_REGISTRATION"

/** `who_are_you_registering_in_the_program` radio + its two paths, and the conditional
 * `mother_beneficiary_id` field they gate (CR-020). */
private const val WHO_ARE_YOU_REGISTERING = "who_are_you_registering_in_the_program"
private const val PATH_REGISTERED_MOTHER = "child_of_a_registered_pregnant_woman"
private const val PATH_DIRECT = "child_directly_mother_not_registered_in_the_program"
private const val MOTHER_BENEFICIARY_ID = "mother_beneficiary_id"

/** Infant DOB + consent question codes used for client-side eligibility/consent gating. */
private const val DATE_OF_BIRTH_OF_INFANT = "date_of_birth_of_infant"
private const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"
private const val CONSENT_NO = "no"

/** Eligibility windows (age in DAYS from infant DOB to registration date, inclusive upper bound). */
private const val MAX_AGE_DAYS_DIRECT = 365L
private const val MAX_AGE_DAYS_REGISTERED_MOTHER = 183L

/** Tab label for any visible field whose schema `section` is missing — a catch-all so a field
 * never silently disappears (child-local copy, mirrors the mother flow's FALLBACK_SECTION). */
const val FALLBACK_SECTION = "Additional Information"

/**
 * Submission is a distinct state from [ChildFormUiState.isLoading] (schema fetch) so the UI can
 * show a submit-specific spinner/error without confusing it with the initial form fetch. Child-local
 * copy — the flow is deliberately not coupled to the mother ViewModel file.
 */
sealed interface SubmissionState {
  data object Idle : SubmissionState
  data object Saving : SubmissionState
  data object Success : SubmissionState
  data class Failed(val kind: ChildSubmitFailureKind, val backendMessage: String?) : SubmissionState
}

/** Which kind of backend failure a [SubmissionState.Failed] represents — the screen maps this to a
 * localized message (a possible-duplicate prompt vs a generic submit failure). */
enum class ChildSubmitFailureKind { DUPLICATE, GENERIC }

/** Client-side, pre-submit validation problems the screen renders inline (each maps to a string
 * resource). Distinct from [SubmissionState.Failed], which is the *backend's* verdict. */
enum class ChildValidationError {
  /** Infant DOB is after the registration date. */
  DOB_FUTURE,

  /** Direct path: infant older than 12 months (365 days). */
  INELIGIBLE_DIRECT,

  /** Registered-mother path: infant older than 6 months (183 days). */
  INELIGIBLE_MOTHER,

  /** `did_we_receive_consent` answered "no" — registration cannot continue. */
  CONSENT_REFUSED,
}

/** One label/value line in the Summary tab's review (child-local copy). */
data class SummaryRow(val label: String, val value: String)

/** One section card in the Summary tab — [title] is the schema section, [rows] its answered fields
 * (empty sections are dropped by [DynamicChildRegistrationViewModel.buildSummary]). */
data class SummarySection(val title: String, val rows: List<SummaryRow>)

data class ChildFormUiState(
  val isLoading: Boolean = false,
  /** Non-null only if the form could not be loaded at all (no live fetch, nothing cached). */
  val loadError: String? = null,
  val version: FormVersion? = null,
  val answers: FormAnswers = FormAnswers(),
  val submissionState: SubmissionState = SubmissionState.Idle,
  /** `media` fields whose playback has been confirmed complete. */
  val mediaCompleted: Set<String> = emptySet(),
  /** `image` fields' captured app-private file URI, keyed by question_code. */
  val capturedImages: Map<String, String> = emptyMap(),
  /** Client-side validation error to render inline; null when the form is currently valid. */
  val validationError: ChildValidationError? = null,
)

/**
 * Drives the CR-020 dynamic Children Register form — standalone twin of the CR-018 mother
 * ViewModel. Fetches the active CHILD_REGISTRATION schema ([FormsRepository]), evaluates
 * visibility/cross-field/numeric-range rules against the current answers, re-derives
 * [FormFieldSchema.computedFrom] fields (incl. `CHILD_AGE_MONTHS`), and adds two child-specific
 * gates: the conditional `mother_beneficiary_id` field and the age-based eligibility windows.
 *
 * [beneficiaryId] is generated once per draft and reused for this ViewModel instance's lifetime
 * (survives recompositions, not process death) — the "generated once, stable across retries"
 * pattern (`localCaseUuid`, CR-017).
 */
@HiltViewModel
class DynamicChildRegistrationViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val lookupRepository: LookupRepository,
  private val geographyFieldOptionsResolver: GeographyFieldOptionsResolver,
  private val draftRepository: ChildFormDraftRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ChildFormUiState())
  val uiState: StateFlow<ChildFormUiState> = _uiState.asStateFlow()

  val beneficiaryId: String = UUID.randomUUID().toString()
  private val localSubmissionUuid: String = newLocalSubmissionUuid()
  val registrationDate: LocalDate = LocalDate.now()

  init {
    load()
  }

  /** Re-fetches the active version. Safe to call again later (e.g. pull-to-refresh) — a newer
   * `versionNo` published by ARMMAN simply replaces the rendered schema, per SRS FR-S-4.5. */
  fun load() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, loadError = null) }
      val version = formsRepository.getActiveVersion(FORM_CODE)
      if (version == null) {
        _uiState.update {
          it.copy(
            isLoading = false,
            loadError = "Couldn't load the registration form. Check your connection and try again.",
          )
        }
        return@launch
      }
      _uiState.update { it.copy(isLoading = false, version = version) }
      recomputeDerivedFields()
    }
  }

  fun setAnswer(questionCode: String, value: String?) {
    _uiState.update { it.copy(answers = it.answers.withSingleValue(questionCode, value)) }
    recomputeDerivedFields()
  }

  fun setMultiAnswer(questionCode: String, values: List<String>) {
    _uiState.update { it.copy(answers = it.answers.withMultiValue(questionCode, values)) }
    revalidate()
  }

  /** Called once the Sakhi has watched/listened to a `media` field's content in full. Mirrors the
   * completion into [FormAnswers] as `"true"` so the field is persisted and included in the
   * `formData` submission blob (the backend requires these and 422s if absent). */
  fun markMediaComplete(questionCode: String) {
    _uiState.update {
      it.copy(
        mediaCompleted = it.mediaCompleted + questionCode,
        answers = it.answers.withSingleValue(questionCode, "true"),
      )
    }
    revalidate()
  }

  /** Called after a live-camera capture for an `image` field. Passing null clears a previous
   * capture (retake). The URI is mirrored into [FormAnswers] (removed on retake) so it's persisted
   * and sent in `formData`. */
  fun setCapturedImage(questionCode: String, uri: String?) {
    _uiState.update {
      it.copy(
        capturedImages = if (uri == null) it.capturedImages - questionCode else it.capturedImages + (questionCode to uri),
        answers = it.answers.withSingleValue(questionCode, uri),
      )
    }
    revalidate()
  }

  private fun recomputeDerivedFields() {
    val version = _uiState.value.version ?: return
    var answers = _uiState.value.answers
    // Generic computedFrom recompute loop — now also handles CHILD_AGE_MONTHS via the shared
    // evaluator. No mother-only stopgaps (age_years/AGE_FROM_DOB) — they don't apply to this form.
    version.schemaJson.forEach { field ->
      val computedFrom = field.computedFrom ?: return@forEach
      val value = FormComputedFieldEvaluator.compute(computedFrom, answers, registrationDate)
      answers = answers.withSingleValue(field.questionCode, value)
    }
    _uiState.update { it.copy(answers = answers) }
    revalidate()
  }

  /** Recomputes [ChildFormUiState.validationError] from the current answers (eligibility + consent
   * gates). Kept on state so the screen can render it inline and [isReadyToSubmit] can gate on it. */
  private fun revalidate() {
    val answers = _uiState.value.answers
    _uiState.update { it.copy(validationError = computeValidationError(answers)) }
  }

  private fun computeValidationError(answers: FormAnswers): ChildValidationError? {
    if (answers.valueOf(DID_WE_RECEIVE_CONSENT) == CONSENT_NO) return ChildValidationError.CONSENT_REFUSED

    val dobRaw = answers.valueOf(DATE_OF_BIRTH_OF_INFANT) ?: return null
    val dob = runCatching { LocalDate.parse(dobRaw) }.getOrNull() ?: return null
    val ageDays = ChronoUnit.DAYS.between(dob, registrationDate)
    if (ageDays < 0) return ChildValidationError.DOB_FUTURE

    return when (answers.valueOf(WHO_ARE_YOU_REGISTERING)) {
      PATH_DIRECT ->
        if (ageDays > MAX_AGE_DAYS_DIRECT) ChildValidationError.INELIGIBLE_DIRECT else null
      PATH_REGISTERED_MOTHER ->
        if (ageDays > MAX_AGE_DAYS_REGISTERED_MOTHER) ChildValidationError.INELIGIBLE_MOTHER else null
      // Path not chosen yet — nothing to check against; the DOB itself is still validated for future.
      else -> null
    }
  }

  /** Fields currently shown, in schema order: honors [FormVisibilityEvaluator], excludes
   * [ChildNonRenderableQuestionCodes.ALL], and additionally hides `mother_beneficiary_id` on the
   * direct path (mother not registered → no id to link). Hidden here means never required-gated. */
  fun visibleFields(): List<FormFieldSchema> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    val path = state.answers.valueOf(WHO_ARE_YOU_REGISTERING)
    return version.schemaJson.filter { field ->
      FormVisibilityEvaluator.isVisible(field, state.answers) &&
        field.questionCode !in ChildNonRenderableQuestionCodes.ALL &&
        !(field.questionCode == MOTHER_BENEFICIARY_ID && path == PATH_DIRECT)
    }
  }

  /** This field's tab label — falls back to [FALLBACK_SECTION] if the schema didn't tag one. */
  fun sectionOf(field: FormFieldSchema): String = field.section ?: FALLBACK_SECTION

  /** Distinct tab labels across currently-visible fields, in the order each first appears. */
  fun sections(): List<String> = visibleFields().map(::sectionOf).distinct()

  /** Visible fields belonging to one tab, in schema order. */
  fun fieldsInSection(section: String): List<FormFieldSchema> =
    visibleFields().filter { sectionOf(it) == section }

  /** Options for a select/radio/multiselect field, in priority order: inline `options`, then the
   * geography/project special case, then a `lookup_category_code` fetch. */
  suspend fun optionsFor(field: FormFieldSchema): List<FormFieldOption> {
    field.options?.let { return it.sortedBy(FormFieldOption::sortOrder) }
    if (field.questionCode in GeographyQuestionCodes.ALL) {
      return geographyFieldOptionsResolver.optionsFor(field.questionCode, _uiState.value.answers)
    }
    val categoryCode = field.lookupCategoryCode ?: return emptyList()
    return lookupRepository.getValues(categoryCode)
      .mapIndexed { index, value -> FormFieldOption(label = value.valueLabel, sortOrder = index, valueCode = value.valueCode) }
  }

  /** Cross-field rules currently violated (empty = fine, or not yet evaluable). */
  fun crossFieldViolations(): List<FormCrossFieldRule> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    return FormCrossFieldValidator.violatedRules(version.validationJson, state.answers)
  }

  /** Whether every required field in [fields] is answered and every `number` field with a
   * `numericRange` satisfies it. Shared by [isReadyToSubmit] and [isSectionReady]. */
  private fun fieldsAnsweredAndInRange(fields: List<FormFieldSchema>): Boolean {
    val state = _uiState.value
    val allRequiredAnswered = fields.all { field ->
      // A computedFrom field is never Sakhi-entered — required-gating it would permanently block
      // submission whenever its formula isn't confirmed yet (e.g. unique_id) with nothing the Sakhi
      // could do. Skip it here; a missing derived value is a backend/data gap to chase separately.
      if (!field.required || field.computedFrom != null) return@all true
      when (field.inputType) {
        FormFieldInputType.MULTISELECT, FormFieldInputType.MULTISELECT_DATE ->
          state.answers.multiValueOf(field.questionCode).isNotEmpty()
        FormFieldInputType.MEDIA -> field.questionCode in state.mediaCompleted
        FormFieldInputType.IMAGE -> field.questionCode in state.capturedImages
        else -> !state.answers.valueOf(field.questionCode).isNullOrBlank()
      }
    }

    val allRangesValid = fields
      .filter { it.inputType == FormFieldInputType.NUMBER }
      .all { field ->
        val entered = state.answers.valueOf(field.questionCode) ?: return@all true
        FormNumericRangeValidator.isWithinRange(field.numericRange, entered)
      }

    return allRequiredAnswered && allRangesValid
  }

  /** Per-tab gate for the "next tab" button: every visible required field in [section] answered and
   * in range. Cross-field/eligibility rules are only enforced at final submit via [isReadyToSubmit]. */
  fun isSectionReady(section: String): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(fieldsInSection(section))
  }

  /** Whether every currently-visible required field has an answer, every `number` field satisfies
   * its range, no cross-field rule is violated, and there is no client-side eligibility/consent
   * error ([ChildFormUiState.validationError]). */
  fun isReadyToSubmit(): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(visibleFields()) &&
      crossFieldViolations().isEmpty() &&
      _uiState.value.validationError == null
  }

  /**
   * Resolved review data for the Summary tab: every answered, currently-visible field grouped by
   * section, coded values mapped to display labels. [mediaCompletedLabel]/[imageCapturedLabel] are
   * passed in so localized text stays in Compose while resolution stays testable here. Empty
   * sections are dropped.
   */
  suspend fun buildSummary(mediaCompletedLabel: String, imageCapturedLabel: String): List<SummarySection> {
    if (_uiState.value.version == null) return emptyList()
    return sections().map { section ->
      SummarySection(
        title = section,
        rows = fieldsInSection(section).mapNotNull { summaryRowFor(it, mediaCompletedLabel, imageCapturedLabel) },
      )
    }.filter { it.rows.isNotEmpty() }
  }

  private suspend fun summaryRowFor(
    field: FormFieldSchema,
    mediaCompletedLabel: String,
    imageCapturedLabel: String,
  ): SummaryRow? {
    val state = _uiState.value
    val value: String? = when (field.inputType) {
      FormFieldInputType.MULTISELECT, FormFieldInputType.MULTISELECT_DATE -> {
        val codes = state.answers.multiValueOf(field.questionCode)
        if (codes.isEmpty()) {
          null
        } else {
          val options = optionsFor(field)
          codes.joinToString(", ") { code -> options.firstOrNull { it.valueCode == code }?.label ?: code }
        }
      }
      FormFieldInputType.MEDIA -> mediaCompletedLabel.takeIf { field.questionCode in state.mediaCompleted }
      FormFieldInputType.IMAGE -> imageCapturedLabel.takeIf { field.questionCode in state.capturedImages }
      FormFieldInputType.SELECT, FormFieldInputType.RADIO -> {
        val code = state.answers.valueOf(field.questionCode)
        if (code.isNullOrBlank()) null else optionsFor(field).firstOrNull { it.valueCode == code }?.label ?: code
      }
      // text / text_geo / number / date / computed read-only — the stored value is display-ready.
      else -> state.answers.valueOf(field.questionCode)
    }
    return value?.takeIf { it.isNotBlank() }?.let { SummaryRow(label = field.label, value = it) }
  }

  /**
   * Saves the draft locally then — only while online — waits for the real backend result before
   * reporting success, so a validation/conflict error is caught here instead of after the screen
   * has already navigated away. Offline: saves locally, queues background sync, reports success.
   * No-op if [isReadyToSubmit] is false or a save is already in flight.
   */
  fun submit() {
    val state = _uiState.value
    val version = state.version ?: return
    if (state.submissionState is SubmissionState.Saving) return
    if (!isReadyToSubmit()) return

    _uiState.update { it.copy(submissionState = SubmissionState.Saving) }
    viewModelScope.launch {
      val result = draftRepository.submitDraft(
        localBeneficiaryId = beneficiaryId,
        formCode = FORM_CODE,
        formVersionId = version.id,
        localSubmissionUuid = localSubmissionUuid,
        answers = state.answers,
        registrationDate = registrationDate,
      )
      _uiState.update {
        it.copy(
          submissionState = when (result) {
            is ChildFormSubmitResult.Synced, is ChildFormSubmitResult.QueuedOffline ->
              SubmissionState.Success
            is ChildFormSubmitResult.DuplicateConflict ->
              SubmissionState.Failed(ChildSubmitFailureKind.DUPLICATE, result.message)
            is ChildFormSubmitResult.Failed ->
              SubmissionState.Failed(ChildSubmitFailureKind.GENERIC, result.message)
          },
        )
      }
    }
  }
}
