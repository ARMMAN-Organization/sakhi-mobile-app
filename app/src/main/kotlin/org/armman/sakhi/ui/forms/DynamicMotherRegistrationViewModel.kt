package org.armman.sakhi.ui.forms

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.armman.sakhi.data.forms.AGE_FROM_DOB_QUESTION_CODES
import org.armman.sakhi.data.forms.BeneficiaryFieldErrorMapper
import org.armman.sakhi.data.forms.BeneficiaryNameRule
import org.armman.sakhi.data.forms.COMPUTED_AGE_FROM_DOB
import org.armman.sakhi.data.forms.COMPUTED_TRIMESTER
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormComputedFieldEvaluator
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormCrossFieldValidator
import org.armman.sakhi.data.forms.FormDateRuleset
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormHiddenFieldReset
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormVisibilityEvaluator
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.MobileNumberRule
import org.armman.sakhi.data.forms.NonRenderableQuestionCodes
import org.armman.sakhi.data.forms.RegistrationDatePrefill
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.forms.TRIMESTER_QUESTION_CODE
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.TdDoseQuestionCodes
import org.armman.sakhi.data.forms.newLocalSubmissionUuid
import org.armman.sakhi.data.enrollment.DuplicateOutcome
import org.armman.sakhi.data.lookup.LookupRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/** The one dynamic form CR-018 covers so far. */
private const val FORM_CODE = "MOTHER_REGISTRATION"

/** Shared with the other temporary "SakhiSync" diagnostics added this session
 * (see FormVisibilityEvaluator.kt). */
private const val TAG = "SakhiSync"

/** Summary-tab-only display format for `date` fields — see [DynamicMotherRegistrationViewModel
 * .formatSummaryDate]'s doc for why this doesn't touch the stored (ISO) value. */
private val SUMMARY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

/** Consent gate: the `did_we_receive_consent` radio answered "no" is a hard stop per the SRS /
 * form spec ("If No → stop form") — the backend also 422s on it. Answering "no" aborts the
 * registration immediately (toast + back to Home) via [DynamicMotherRegistrationViewModel.consentRefused].
 * Kept as a local copy (matching the child flow's own constants) so the two enrollment ViewModels
 * stay decoupled. */
private const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"
private const val CONSENT_NO = "no"

/** Tab label for any visible field whose schema `section` is missing (older cached schema
 * versions predating the `section` key, or a future field the backend forgets to tag) — a
 * deliberate catch-all so a field never silently disappears from the form, it just lands on an
 * extra tab at the end instead. */
const val FALLBACK_SECTION = "Additional Information"

/**
 * Submission is a distinct state from [DynamicFormUiState.isLoading] (which covers loading the
 * schema, not submitting answers) so the UI can show a submit-specific spinner/error without
 * confusing it with the initial form fetch.
 *
 * [Success] means either the backend confirmed the submission (while online, via an immediate
 * [org.armman.sakhi.data.forms.DynamicFormSyncExecutor.runOne] attempt) or the draft was saved
 * locally and queued for background sync because the device is offline — both are safe points to
 * navigate away. [Failed] now covers the REAL backend validation/conflict error too (not just a
 * local save failure like disk full), since [DynamicFormDraftRepository.submitDraft] waits for
 * that result while online instead of navigating away before it's known.
 */
sealed interface SubmissionState {
  data object Idle : SubmissionState
  data object Saving : SubmissionState
  data object Success : SubmissionState
  data class Failed(val message: String) : SubmissionState

  /**
   * The backend rejected this enrolment as a duplicate of an open case (SRS FR-S-2.4: "registration
   * cannot proceed"). A distinct state rather than a [Failed] with a message, because the copy is a
   * fixed sentence the screen reads from string resources — so it exists in Marathi, unlike anything
   * quoted back from the backend.
   */
  data object DuplicateBlocked : SubmissionState
}

/**
 * One-shot signal to move the Sakhi to the first field a `400 VALIDATION_ERROR` flagged: switch to
 * its [section] tab and scroll its list to [questionCode]. [token] makes it fire again even when
 * the same field fails a second time (a plain equal value wouldn't retrigger the screen's effect);
 * the screen clears it via [DynamicMotherRegistrationViewModel.onErrorScrollHandled] once consumed.
 */
data class ErrorScrollTarget(val section: String, val questionCode: String, val token: Long)

/** One label/value line in the Summary tab's review. */
data class SummaryRow(val label: String, val value: String)

/** One section card in the Summary tab — [title] is the schema section, [rows] its answered
 * fields (empty sections are dropped by [DynamicMotherRegistrationViewModel.buildSummary]). */
data class SummarySection(val title: String, val rows: List<SummaryRow>)

data class DynamicFormUiState(
  val isLoading: Boolean = false,
  /** Non-null only if the form could not be loaded at all (no live fetch, nothing cached
   * either) — the enrollment genuinely can't proceed without some schema to render. */
  val loadError: String? = null,
  val version: FormVersion? = null,
  val answers: FormAnswers = FormAnswers(),
  val submissionState: SubmissionState = SubmissionState.Idle,
  /** `media` fields (video/audio) whose playback has been confirmed complete — tracked
   * separately from [answers] since there's no typed value to store, just a completion signal. */
  val mediaCompleted: Set<String> = emptySet(),
  /** `image` fields' captured app-private file URI, keyed by question_code — null/absent means
   * not yet captured. Separate from [answers] for the same reason as [mediaCompleted]. */
  val capturedImages: Map<String, String> = emptyMap(),
  /** Per-field submit errors from a backend `400 VALIDATION_ERROR`, keyed by `question_code` and
   * rendered inline under the field. Populated on submit failure, cleared for a field as soon as
   * the Sakhi edits it, and cleared entirely on the next submit attempt. Empty when the failure
   * wasn't field-attributable (e.g. `422`), which keeps it a page-level banner only. */
  val fieldErrors: Map<String, String> = emptyMap(),
  /** Non-null right after a field-attributable submit failure — the screen consumes it once to
   * jump to the first flagged field, then calls [DynamicMotherRegistrationViewModel.onErrorScrollHandled]. */
  val errorScroll: ErrorScrollTarget? = null,
  /**
   * Non-null when the backend found a *completed* earlier pregnancy for this woman and is asking
   * whether this is a new one (SRS FR-S-2.5). The screen shows a confirmation dialog; confirming
   * calls [DynamicMotherRegistrationViewModel.onConfirmNewPregnancy], dismissing calls
   * [DynamicMotherRegistrationViewModel.onDismissDuplicatePrompt] and leaves the form untouched.
   */
  val duplicatePrompt: DuplicateOutcome.NewPregnancyPrompt? = null,
)

/**
 * Drives the CR-018 dynamic Mother Registration form: fetches the active schema
 * ([FormsRepository], auto-refreshing on every load — the "app should automatically fetch the
 * updated form" behavior), evaluates [FormVisibilityEvaluator]/[FormCrossFieldValidator]/
 * [FormNumericRangeValidator] against the current answers, and re-derives
 * [FormFieldSchema.computedFrom] fields as their inputs change.
 *
 * [beneficiaryId] is generated once per draft and reused for the lifetime of this ViewModel
 * instance (survives recompositions, not process death — same "generated once, stable across
 * retries" pattern as the static enrollment flow's `PersonalInfoState.beneficiaryId`, see CR-017).
 */
@HiltViewModel
class DynamicMotherRegistrationViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val lookupRepository: LookupRepository,
  private val geographyFieldOptionsResolver: GeographyFieldOptionsResolver,
  private val draftRepository: DynamicFormDraftRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(DynamicFormUiState())
  val uiState: StateFlow<DynamicFormUiState> = _uiState.asStateFlow()

  /**
   * One-shot signal that the Sakhi answered "no" to `did_we_receive_consent`. The screen reacts by
   * toasting and leaving enrollment — refusal is a hard stop, so there is nothing left to render
   * and no reason to keep the half-entered form alive.
   *
   * Deliberately a [MutableSharedFlow] and not part of [DynamicFormUiState]: this is an event, not
   * state. A state flag would re-fire the navigation on every recomposition/config change, and a
   * second refusal (after re-entering the form) would emit the same value and be swallowed.
   * `extraBufferCapacity = 1` lets [tryEmit] succeed from the non-suspending [setAnswer].
   */
  private val _consentRefused = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val consentRefused: SharedFlow<Unit> = _consentRefused.asSharedFlow()

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
            loadError = "Couldn't load the enrollment form. Check your connection and try again.",
          )
        }
        return@launch
      }
      _uiState.update { it.copy(isLoading = false, version = version) }
      prefillRegistrationDate()
      prefillAutoSelectedGeography()
      recomputeDerivedFields()
    }
  }

  /**
   * Pre-fills the registration date with today, per form spec row 13 ("Automatically popup todays
   * date"), so the Sakhi never types it.
   *
   * Delegates to [RegistrationDatePrefill] — shared with the child flow, and matching every published
   * spelling of the code. The private version this replaced was keyed on the typo spelling alone and
   * silently stopped prefilling when MOTHER_REGISTRATION v3 renamed the question to
   * `registration_date`. See that object's KDoc for the skip/fallback rules.
   */
  private fun prefillRegistrationDate() {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      it.copy(answers = RegistrationDatePrefill.apply(fields, it.answers, registrationDate))
    }
  }

  /**
   * Pre-fills every geography field (and `project_name`) that resolves to exactly one backend unit
   * with that unit's id, so the value is present in the submission even though the field renders
   * read-only (see [org.armman.sakhi.ui.forms.DynamicFormField]) and the Sakhi never taps it. Skips
   * a field that already has an answer so a resumed draft's earlier pick isn't clobbered, and skips
   * levels the backend ships with several units (those stay an interactive dropdown). Options here
   * come from [FormVersion.geography]/the profile only — no network — so this is safe to run inline
   * on load.
   */
  private suspend fun prefillAutoSelectedGeography() {
    val version = _uiState.value.version ?: return
    val geography = version.geography.orEmpty()
    var answers = _uiState.value.answers
    version.schemaJson
      .filter { it.questionCode in GeographyQuestionCodes.ALL }
      .forEach { field ->
        if (!answers.valueOf(field.questionCode).isNullOrBlank()) return@forEach
        val only = geographyFieldOptionsResolver
          .optionsFromVersionGeography(field.questionCode, geography)
          .singleOrNull() ?: return@forEach
        answers = answers.withSingleValue(field.questionCode, only.valueCode)
      }
    _uiState.update { it.copy(answers = answers) }
  }

  fun setAnswer(questionCode: String, value: String?) {
    // Temporary diagnostic for the "Gravida alert / Last-Pregnancy block doesn't appear when 2 is
    // entered directly, only after 1 is entered then changed to 2" report. Logs the exact value
    // this function receives for every Gravida keystroke, so a repro shows whether a direct "2"
    // ever reaches the ViewModel at all (vs. a TextField/IME timing issue that never calls this).
    if (questionCode == FormObstetricRuleset.GRAVIDA) {
      Log.d(TAG, "setAnswer(GRAVIDA): received value='$value'")
    }
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withSingleValue(questionCode, value)
      // A field this answer just hid (e.g. dropping Gravida to 1 hiding Living children/Still
      // births/…) must not leave its old value sitting in FormAnswers — see
      // FormHiddenFieldReset's doc for the bug that causes.
      val newlyHidden = FormHiddenFieldReset.newlyHiddenQuestionCodes(fields, previousAnswers, updatedAnswers)
      it.copy(
        answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers),
        fieldErrors = it.fieldErrors - questionCode - newlyHidden,
      )
    }
    if (questionCode == FormObstetricRuleset.GRAVIDA) {
      val postUpdateAnswers = _uiState.value.answers
      val gravidaGatedVisible = fields
        .filter { it.visibleWhen?.field == FormObstetricRuleset.GRAVIDA }
        .map { it.questionCode to FormVisibilityEvaluator.isVisible(it, postUpdateAnswers) }
      Log.d(
        TAG,
        "setAnswer(GRAVIDA): stored answers.gravida='${postUpdateAnswers.valueOf(FormObstetricRuleset.GRAVIDA)}' " +
          "gravida-gated fields visibility=$gravidaGatedVisible",
      )
    }
    recomputeDerivedFields()
    // Refusing consent ends the registration outright (SRS / form spec: "If No → stop form"), so it
    // is signalled here — the single place a "no" can enter the answers. Geography/computed
    // prefills and the mother-record inheritance never write "no", only "yes".
    if (questionCode == DID_WE_RECEIVE_CONSENT && value == CONSENT_NO) {
      _consentRefused.tryEmit(Unit)
    }
  }

  fun setMultiAnswer(questionCode: String, values: List<String>) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withMultiValue(questionCode, values)
      val newlyHidden = FormHiddenFieldReset.newlyHiddenQuestionCodes(fields, previousAnswers, updatedAnswers)
      it.copy(
        answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers),
        fieldErrors = it.fieldErrors - questionCode - newlyHidden,
      )
    }
    // Same reason as [setAnswer]: FormHiddenFieldReset above may have cleared a field that a
    // computed field derives from, so the derived value has to be recomputed or it keeps showing a
    // result calculated from an answer the Sakhi can no longer see.
    recomputeDerivedFields()
  }

  /** Called once the Sakhi has watched/listened to a `media` field's content in full (the
   * screen/player is responsible for actually detecting playback completion; this just records
   * it). Mirrors the completion into [FormAnswers] as `"true"` so the field is persisted with the
   * draft and included in the `formData` submission blob — the backend requires these media
   * fields and 422s if they're absent. [mediaCompleted] is kept as the UI's source of truth for
   * rendering/gating. */
  fun markMediaComplete(questionCode: String) {
    _uiState.update {
      it.copy(
        mediaCompleted = it.mediaCompleted + questionCode,
        answers = it.answers.withSingleValue(questionCode, "true"),
        fieldErrors = it.fieldErrors - questionCode,
      )
    }
  }

  /** Called after a live-camera capture for an `image` field. Passing null clears a previous
   * capture (retake). The captured URI is also mirrored into [FormAnswers] (removed on retake) so
   * the photo reference is persisted with the draft and sent in `formData` — same reason as
   * [markMediaComplete]. */
  fun setCapturedImage(questionCode: String, uri: String?) {
    _uiState.update {
      it.copy(
        capturedImages = if (uri == null) it.capturedImages - questionCode else it.capturedImages + (questionCode to uri),
        answers = it.answers.withSingleValue(questionCode, uri),
        fieldErrors = it.fieldErrors - questionCode,
      )
    }
  }

  private fun recomputeDerivedFields() {
    val version = _uiState.value.version ?: return
    var answers = _uiState.value.answers
    version.schemaJson.forEach { field ->
      val computedFrom = field.computedFrom ?: return@forEach
      val value = FormComputedFieldEvaluator.compute(computedFrom, answers, registrationDate)
      // AGE_FROM_DOB (per CR-037) has a legitimate Sakhi-typed fallback when date_of_birth is
      // blank — spec row 20's "either DOB or age". A null derivation there means "DOB isn't
      // answered", NOT "clear whatever age was typed"; withSingleValue(code, null) would otherwise
      // delete a manually-entered age every time ANY other field on the form changes. Every other
      // computedFrom field has no manual fallback, so clearing on a null derivation (e.g. after LMP
      // is cleared, EDD should clear too) stays correct for them.
      if (value == null && computedFrom == COMPUTED_AGE_FROM_DOB) return@forEach
      answers = answers.withSingleValue(field.questionCode, value)
    }
    // Stopgap: for a schema version that hasn't yet picked up CR-037's `computedFrom:
    // "AGE_FROM_DOB"` declaration (see AGE_FROM_DOB_QUESTION_CODES' doc), the generic loop above
    // never touches the age field. Same null-guard as above, same reason.
    val ageField = version.schemaJson
      .firstOrNull { it.questionCode in AGE_FROM_DOB_QUESTION_CODES && it.computedFrom == null }
    if (ageField != null) {
      val value = FormComputedFieldEvaluator.compute(COMPUTED_AGE_FROM_DOB, answers, registrationDate)
      if (value != null) {
        answers = answers.withSingleValue(ageField.questionCode, value)
      }
    }
    // Stopgap: same reasoning as the age field above — the live schema declares no `computedFrom`
    // for `trimester_of_preganancy` (see TRIMESTER_QUESTION_CODE's doc). Compute it from LMP/
    // registration date instead of leaving it as the free-text box the Sakhi was typing raw digits
    // into. Forward-compatible with the day the backend adds a real `computedFrom` declaration
    // (then `trimesterField.computedFrom` is non-null and this is skipped).
    val trimesterField = version.schemaJson
      .firstOrNull { it.questionCode == TRIMESTER_QUESTION_CODE && it.computedFrom == null }
    if (trimesterField != null) {
      val value = FormComputedFieldEvaluator.compute(COMPUTED_TRIMESTER, answers, registrationDate)
      answers = answers.withSingleValue(trimesterField.questionCode, value)
    }
    _uiState.update { it.copy(answers = answers) }
  }

  /** Fields currently shown, in schema order: honors [FormVisibilityEvaluator] and excludes
   * [NonRenderableQuestionCodes.ALL] (fields that must never be an input regardless of
   * visibility — e.g. `beneficiary_id`, which doesn't exist yet at this point in the flow). */
  fun visibleFields(): List<FormFieldSchema> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    return version.schemaJson.filter {
      FormVisibilityEvaluator.isVisible(it, state.answers) && it.questionCode !in NonRenderableQuestionCodes.ALL
    }
  }

  /** This field's tab label — falls back to [FALLBACK_SECTION] if the schema didn't tag one. */
  fun sectionOf(field: FormFieldSchema): String = field.section ?: FALLBACK_SECTION

  /** Distinct tab labels across currently-visible fields, in the order each first appears in the
   * schema — matches the old static stepper's Consent → Personal Info → Health History order
   * without hardcoding it, since that ordering now comes from the backend's field order. */
  fun sections(): List<String> = visibleFields().map(::sectionOf).distinct()

  /** Visible fields belonging to one tab, in schema order. */
  fun fieldsInSection(section: String): List<FormFieldSchema> =
    visibleFields().filter { sectionOf(it) == section }

  /**
   * Every field in the active schema, regardless of current visibility. Use this (not
   * [visibleFields]) for anything that has to describe a field that might currently be hidden —
   * e.g. resolving a cross-field rule's label — since a hidden field is still a real question the
   * Sakhi answered earlier or will answer again, not one that stopped existing.
   */
  fun allFields(): List<FormFieldSchema> = _uiState.value.version?.schemaJson.orEmpty()

  /** Options for a select/radio/multiselect field, in priority order: inline `options` from the
   * schema itself, then the geography/project special case (CR-018 product decision), then a
   * `lookup_category_code` fetch. A field matching none of these renders with zero options —
   * that's a real gap to notice (schema declared a choice field with no way to populate it),
   * not something to silently paper over. */
  suspend fun optionsFor(field: FormFieldSchema): List<FormFieldOption> {
    field.options?.let { return it.sortedBy(FormFieldOption::sortOrder) }
    if (field.questionCode in GeographyQuestionCodes.ALL) {
      // Geography answers must be the backend's own geographyUnitIds (shipped in the active
      // version's `geography`), never a hardcoded cascade — see optionsFromVersionGeography.
      return geographyFieldOptionsResolver.optionsFromVersionGeography(
        field.questionCode,
        _uiState.value.version?.geography.orEmpty(),
      )
    }
    val categoryCode = field.lookupCategoryCode ?: return emptyList()
    return lookupRepository.getValues(categoryCode)
      .mapIndexed { index, value -> FormFieldOption(label = value.valueLabel, sortOrder = index, valueCode = value.valueCode) }
  }

  /** Cross-field rules currently violated (empty = fine, or not yet evaluable — see
   * [FormCrossFieldValidator]). */
  fun crossFieldViolations(): List<FormCrossFieldRule> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    return FormCrossFieldValidator.violatedRules(version.validationJson, state.answers)
  }

  /** Whether every required field in [fields] is answered and every `number` field with a
   * `numericRange` satisfies it. Shared by [isReadyToSubmit] (all visible fields) and
   * [isSectionReady] (one tab's fields) so both gate on identical per-field rules. */
  private fun fieldsAnsweredAndInRange(fields: List<FormFieldSchema>): Boolean {
    val state = _uiState.value
    val allRequiredAnswered = fields.all { field ->
      // A computedFrom field is never Sakhi-entered — required-gating it would permanently block
      // submission whenever its formula isn't confirmed yet (e.g. unique_id, see
      // FormComputedFieldEvaluator) with nothing the Sakhi could do about it. Its value is either
      // there because the derivation succeeded, or it isn't and that's a backend/data gap to
      // chase separately, not a reason to block every registration.
      //
      // The Td-dose date fields are the one place `field.required` alone isn't the whole story —
      // schema says `required: false` for all 3 (correct: each is only mandatory once its own
      // checkbox is checked), so TdDoseQuestionCodes adds them back in here. See that object's doc.
      val effectivelyRequired = field.required ||
        field.questionCode in TdDoseQuestionCodes.CONDITIONALLY_REQUIRED_DATE_QUESTION_CODES
      if (!effectivelyRequired || field.computedFrom != null) return@all true
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

    // `mobile_number` must be a complete 10-digit number when present (blank is handled by the
    // required-field gate above). See MobileNumberRule.
    val mobileValid = fields.all { field ->
      if (field.questionCode != MobileNumberRule.QUESTION_CODE) return@all true
      val entered = state.answers.valueOf(field.questionCode)
      entered.isNullOrBlank() || MobileNumberRule.isComplete(entered)
    }

    // Beneficiary name fields take letters and spaces only (form spec S.No 19). The renderer
    // already filters the input, so this gate exists for values that bypassed it — a draft saved
    // before this rule shipped, or an answer restored from the backend. Blank is handled by the
    // required-field gate above. See BeneficiaryNameRule.
    val namesValid = fields.all { field ->
      if (!BeneficiaryNameRule.appliesTo(field.questionCode)) return@all true
      BeneficiaryNameRule.isValid(state.answers.valueOf(field.questionCode))
    }

    // Spec date rules (DOB age 10-50, LMP 31..239 days before registration, registration date not
    // future). Blank/unparseable is not a date-range failure — the required-field gate above owns
    // blank. See FormDateRuleset.
    // Bug fix: the non-renderable date fields (registration_date and its typo twin — see
    // NonRenderableQuestionCodes) are stripped out of `fields` by visibleFields(), so a future
    // value carried in by an older draft or a backend-restored answer was never gated at all.
    // RegistrationDatePrefill normally writes today's date, which is why this never surfaced in
    // the happy path — but "normally" is not a validation guarantee, and this is the only gate
    // that would catch it. Union rather than replacement: the visible fields still gate exactly
    // as before.
    val hiddenDateFields = allFields().filter {
      it.questionCode in NonRenderableQuestionCodes.ALL && it !in fields
    }
    val datesValid =
      FormDateRuleset.allDatesValid(fields + hiddenDateFields, state.answers, registrationDate)

    // Obstetric-history consistency (Gravida = Para + abortions + 1, Para/abortions <= Gravida, dead
    // children <= live births). Gated per-tab as well as at submit so the Sakhi is stopped on the
    // Health History tab, where the fields are, rather than at the end. See FormObstetricRuleset.
    val obstetricValid = FormObstetricRuleset.allValid(fields, state.answers)

    return allRequiredAnswered && allRangesValid && mobileValid && namesValid &&
      datesValid && obstetricValid
  }

  /** Per-tab gate for the "next tab" button: every visible required field in [section] answered
   * and in range. Cross-field rules (which can span tabs) are only enforced at final submit via
   * [isReadyToSubmit], so an early tab isn't blocked by a rule whose other fields live later. */
  fun isSectionReady(section: String): Boolean {
    if (_uiState.value.version == null) return false
    // No consent gate here: a refusal now leaves the screen entirely (see [consentRefused]), so
    // there is no state in which this would be evaluated with consent == "no".
    return fieldsAnsweredAndInRange(fieldsInSection(section))
  }

  /** Whether every currently-visible required field has an answer, every `number` field with a
   * `numericRange` satisfies it and no cross-field rule is violated. Consent is not re-checked here
   * — a refusal exits the flow before Submit is reachable, and
   * [org.armman.sakhi.data.forms.DynamicFormSubmissionMapper] still refuses to build a payload
   * without a "yes" as a last line of defence. */
  fun isReadyToSubmit(): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(visibleFields()) && crossFieldViolations().isEmpty()
  }

  /**
   * Resolved review data for the Summary tab: every answered, currently-visible field grouped by
   * section, with coded values (select/radio/multiselect) mapped to display labels via the same
   * [optionsFor] the inputs use. [mediaCompletedLabel]/[imageCapturedLabel] are passed in so the
   * localized status text stays in Compose while the resolution logic stays here (and testable).
   * Sections with no answered fields are dropped so empty cards never show.
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
      // Reported bug: the Summary tab was printing the stored ISO answer (e.g. "2026-08-07")
      // verbatim instead of the dd-MM-yyyy the review card should read. Only this display string
      // changes — [FormAnswers] still holds the ISO value everywhere else (editing, computed
      // fields, cross-field/date-rule checks, the submission payload).
      FormFieldInputType.DATE -> state.answers.valueOf(field.questionCode)?.let(::formatSummaryDate)
      // text / text_geo / number / computed read-only — the stored value is already display-ready.
      else -> state.answers.valueOf(field.questionCode)
    }
    return value?.takeIf { it.isNotBlank() }?.let { SummaryRow(label = field.label, value = it) }
  }

  /**
   * Reformats an ISO (`yyyy-MM-dd`) date answer to `dd-MM-yyyy` for the Summary tab only. Falls
   * back to the raw stored value if it isn't a parseable ISO date, so a malformed answer still
   * shows something on the review card rather than blanking the row or crashing it.
   */
  private fun formatSummaryDate(raw: String): String =
    runCatching { LocalDate.parse(raw).format(SUMMARY_DATE_FORMATTER) }.getOrDefault(raw)

  /**
   * Saves the draft locally (via [DynamicFormDraftRepository], Room + encrypted store), then —
   * only while online — waits for the real backend result before reporting success, so a
   * validation/conflict error is caught here instead of after the screen has already navigated
   * away. While offline, behaves as before: saves locally, queues background sync, reports
   * success immediately (nothing more can be known yet). No-op if [isReadyToSubmit] is false or a
   * save is already in flight; callers should gate the submit button on [isReadyToSubmit]
   * themselves, this is a defensive second check.
   */
  fun submit() {
    val state = _uiState.value
    val version = state.version ?: return
    if (state.submissionState is SubmissionState.Saving) return
    if (!isReadyToSubmit()) return

    // Clear any prior field errors up front so a fresh attempt starts clean (and a stale inline
    // error can't linger next to a field the Sakhi already fixed).
    _uiState.update {
      it.copy(
        submissionState = SubmissionState.Saving,
        fieldErrors = emptyMap(),
        errorScroll = null,
        duplicatePrompt = null,
      )
    }
    viewModelScope.launch {
      val result = draftRepository.submitDraft(
        localBeneficiaryId = beneficiaryId,
        formCode = FORM_CODE,
        formVersionId = version.id,
        localSubmissionUuid = localSubmissionUuid,
        answers = state.answers,
        registrationDate = registrationDate,
      )
      _uiState.update { current ->
        when (result) {
          is DynamicFormSubmitResult.Synced, is DynamicFormSubmitResult.QueuedOffline ->
            current.copy(submissionState = SubmissionState.Success, fieldErrors = emptyMap(), errorScroll = null)

          is DynamicFormSubmitResult.DuplicateConflict -> applyDuplicateOutcome(current, result.outcome)

          is DynamicFormSubmitResult.Failed -> applyFieldErrors(current, result)
        }
      }
    }
  }

  /**
   * The Sakhi confirmed that this really is a new pregnancy for a woman whose earlier pregnancy is
   * complete (SRS FR-S-2.5). Resubmits with the acknowledgement and a link back to that earlier case.
   *
   * No-op unless a prompt is actually on screen, so a stray call can never acknowledge a duplicate
   * the Sakhi was never asked about.
   */
  fun onConfirmNewPregnancy() {
    val prompt = _uiState.value.duplicatePrompt ?: return
    if (_uiState.value.submissionState is SubmissionState.Saving) return
    _uiState.update {
      it.copy(submissionState = SubmissionState.Saving, duplicatePrompt = null, fieldErrors = emptyMap())
    }
    viewModelScope.launch {
      val result = draftRepository.confirmNewPregnancy(
        localBeneficiaryId = beneficiaryId,
        existingBeneficiaryId = prompt.existingBeneficiaryId,
      )
      _uiState.update { current ->
        when (result) {
          is DynamicFormSubmitResult.Synced, is DynamicFormSubmitResult.QueuedOffline ->
            current.copy(submissionState = SubmissionState.Success, fieldErrors = emptyMap(), errorScroll = null)

          is DynamicFormSubmitResult.DuplicateConflict -> applyDuplicateOutcome(current, result.outcome)

          is DynamicFormSubmitResult.Failed -> applyFieldErrors(current, result)
        }
      }
    }
  }

  /** The Sakhi answered "no" to the new-pregnancy prompt (or dismissed it). The draft is left exactly
   * as it is — still saved locally, still un-uploaded — so she can correct a mistyped name or LMP and
   * submit again. */
  fun onDismissDuplicatePrompt() {
    _uiState.update { it.copy(duplicatePrompt = null, submissionState = SubmissionState.Idle) }
    // Also clear the copy persisted on the draft, or Home would keep asking the same question she
    // just answered "no" to.
    viewModelScope.launch { draftRepository.dismissNewPregnancyPrompt(beneficiaryId) }
  }

  /**
   * A `409` splits two ways (SRS FR-S-2.4/2.5): a hard duplicate is a dead end the Sakhi cannot
   * override, while a completed earlier pregnancy becomes a question she can answer. The prompt
   * returns [SubmissionState] to Idle so Submit is usable again if she declines.
   */
  private fun applyDuplicateOutcome(
    current: DynamicFormUiState,
    outcome: DuplicateOutcome,
  ): DynamicFormUiState = when (outcome) {
    DuplicateOutcome.HardDuplicate -> current.copy(
      submissionState = SubmissionState.DuplicateBlocked,
      fieldErrors = emptyMap(),
      errorScroll = null,
      duplicatePrompt = null,
    )

    is DuplicateOutcome.NewPregnancyPrompt -> current.copy(
      submissionState = SubmissionState.Idle,
      fieldErrors = emptyMap(),
      errorScroll = null,
      duplicatePrompt = outcome,
    )
  }

  /** Maps a [DynamicFormSubmitResult.Failed]'s DTO-path `fieldErrors` to `question_code`s and, when
   * any map to a real field, shows a generic banner plus per-field inline errors and jumps to the
   * first flagged field. With none attributable (a `422`, or a `400` on a field this schema version
   * doesn't declare), it stays a banner-only error carrying the backend's sentence — cleaned by
   * [SubmitErrorCopy], never the raw response body. */
  private fun applyFieldErrors(
    current: DynamicFormUiState,
    result: DynamicFormSubmitResult.Failed,
  ): DynamicFormUiState {
    val mapped = BeneficiaryFieldErrorMapper.toQuestionCodeErrors(result.fieldErrors, knownQuestionCodes())
      // The backend words these in DTO terms ("lmpDate cannot be in the future"); under a field
      // label that reads like a leaked internal name, so give it the same clean-up as the banner.
      .mapValues { (_, message) -> SubmitErrorCopy.humanize(message) ?: message }
    if (mapped.isEmpty()) {
      return current.copy(
        submissionState = SubmissionState.Failed(
          SubmitErrorCopy.humanize(result.message) ?: SubmitErrorCopy.GENERIC,
        ),
        fieldErrors = emptyMap(),
        errorScroll = null,
      )
    }
    return current.copy(
      submissionState = SubmissionState.Failed("Please fix the highlighted fields and submit again."),
      fieldErrors = mapped,
      errorScroll = firstErroredFieldTarget(mapped.keys),
    )
  }

  /** Every `question_code` the active schema declares — the set [BeneficiaryFieldErrorMapper] needs
   * to decide whether a DTO path maps onto a real field (and to pick the name fallback). */
  private fun knownQuestionCodes(): Set<String> =
    _uiState.value.version?.schemaJson?.map { it.questionCode }?.toSet().orEmpty()

  /** The first currently-visible field (schema order) carrying one of [erroredCodes], as an
   * [ErrorScrollTarget]; null if none of them is visible (e.g. all hidden by `visibleWhen`), in
   * which case there's nothing to scroll to and the inline errors simply can't render. */
  private fun firstErroredFieldTarget(erroredCodes: Set<String>): ErrorScrollTarget? {
    val field = visibleFields().firstOrNull { it.questionCode in erroredCodes } ?: return null
    return ErrorScrollTarget(
      section = sectionOf(field),
      questionCode = field.questionCode,
      token = System.nanoTime(),
    )
  }

  /** Consumes [DynamicFormUiState.errorScroll] once the screen has navigated/scrolled to the field,
   * so the one-shot doesn't re-fire on the next recomposition. */
  fun onErrorScrollHandled() {
    _uiState.update { it.copy(errorScroll = null) }
  }
}
