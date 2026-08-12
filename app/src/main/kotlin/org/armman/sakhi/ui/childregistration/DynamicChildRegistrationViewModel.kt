package org.armman.sakhi.ui.childregistration

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
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSubmitResult
import org.armman.sakhi.data.childregistration.ChildNonRenderableQuestionCodes
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormComputedFieldEvaluator
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormCrossFieldValidator
import org.armman.sakhi.data.forms.FormDateRuleset
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormHiddenFieldReset
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormVisibilityEvaluator
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.GeographyFieldOptionsResolver
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.RegistrationDatePrefill
import org.armman.sakhi.data.forms.VaccinationAtBirthQuestionCodes
import org.armman.sakhi.data.forms.newLocalSubmissionUuid
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.motherlink.LinkedMother
import org.armman.sakhi.data.motherlink.MotherLinkRepository
import org.armman.sakhi.data.motherlink.MotherPrefill
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/** The dynamic form CR-020 covers. */
private const val FORM_CODE = "CHILD_REGISTRATION"

/** `who_are_you_registering_in_the_program` radio + its two paths, and the infant DOB the
 * eligibility windows are measured from. Aliased from [ChildRegistrationQuestionCodes] — the shared
 * declaration [FormDateRuleset] also reads, so the picker's bounds and the gate below can never be
 * keyed off different strings. */
private const val WHO_ARE_YOU_REGISTERING = ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING
private const val PATH_REGISTERED_MOTHER = ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER
private const val PATH_DIRECT = ChildRegistrationQuestionCodes.PATH_DIRECT
private const val DATE_OF_BIRTH_OF_INFANT = ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT

/** The conditional field the path radio gates (CR-020). */
private const val MOTHER_BENEFICIARY_ID = "mother_beneficiary_id"

/** Consent question codes. Answering "no" aborts the registration immediately (toast + back to
 * Home) via [DynamicChildRegistrationViewModel.consentRefused] — it is not an inline error. */
private const val DID_WE_RECEIVE_CONSENT = "did_we_receive_consent"
private const val CONSENT_NO = "no"

/**
 * Eligibility windows (age in DAYS from infant DOB to registration date, inclusive upper bound),
 * per SRS FR-S-2.3.
 *
 * Aliased from [FormDateRuleset], which uses the SAME values to bound the date picker. Detection
 * (here) and prevention (there) must agree: a ceiling raised in one place only would either let the
 * Sakhi pick a date the gate then silently refuses, or gate a date the picker offered as valid.
 */
private const val MAX_AGE_DAYS_DIRECT = FormDateRuleset.CHILD_AGE_CEILING_DAYS_INDEPENDENT
private const val MAX_AGE_DAYS_REGISTERED_MOTHER =
  FormDateRuleset.CHILD_AGE_CEILING_DAYS_MOTHER_LINKED

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
  /** Registered mothers selectable in the picker (CR-031). Empty either because the Sakhi has none
   * or because the fetch failed — [motherLoadFailed] distinguishes the two. */
  val motherOptions: List<LinkedMother> = emptyList(),
  /** True while the mother list is being fetched. */
  val isLoadingMothers: Boolean = false,
  /** True when nothing could be fetched **and** nothing was ever cached, so the Sakhi must come
   * online once before she can link a mother. Distinct from an empty list, which legitimately means
   * "no registered mothers yet". */
  val motherLoadFailed: Boolean = false,
  /** The linked mother's beneficiary UUID, or null on the direct path / before selection. */
  val selectedMotherId: String? = null,
  /** Question codes currently holding a value copied from the mother's record, so the UI can show
   * the "From mother's record" hint. A code leaves this set the moment the Sakhi edits it. */
  val motherPrefilledCodes: Set<String> = emptySet(),
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
  private val motherLinkRepository: MotherLinkRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ChildFormUiState())
  val uiState: StateFlow<ChildFormUiState> = _uiState.asStateFlow()

  /**
   * One-shot signal that the Sakhi answered "no" to `did_we_receive_consent`. The screen reacts by
   * toasting and leaving enrollment — refusal is a hard stop, so there is nothing left to render
   * and no reason to keep the half-entered form alive.
   *
   * An event rather than [ChildFormUiState] state, for the same reasons as the mother flow: state
   * would re-fire the navigation on recomposition/config change, and a second refusal would emit an
   * equal value and be swallowed. `extraBufferCapacity = 1` lets [tryEmit] succeed from the
   * non-suspending [setAnswer].
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
            loadError = "Couldn't load the registration form. Check your connection and try again.",
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
   * Its absence here was the reported defect: CHILD_REGISTRATION v2 declares `registrtion_date` as
   * `required: true`, so the field rendered empty and the "Infant Details" gate stayed blocked until
   * the Sakhi picked today's date by hand. Shared with the mother flow via [RegistrationDatePrefill]
   * rather than cloned — see that object's KDoc for the skip/fallback rules.
   */
  private fun prefillRegistrationDate() {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      it.copy(answers = RegistrationDatePrefill.apply(fields, it.answers, registrationDate))
    }
  }

  /**
   * Pre-fills every geography field (and `project_name`) that resolves to exactly one backend unit
   * with that unit's id, so the value is present in the submission even where the field renders
   * read-only and the Sakhi never taps it.
   *
   * Mirror of the mother flow's `prefillAutoSelectedGeography` (CR-018). Its absence here was the
   * CR-020 bug where Pada / PHC / Sub Centre rendered as empty dropdowns and the "Infant Details"
   * button stayed permanently disabled: nothing ever wrote those answers, so the required-field
   * gate could never be satisfied.
   *
   * Skips a field that already has an answer so a resumed draft's earlier pick isn't clobbered, and
   * skips levels the backend ships with several units (those stay an interactive dropdown). Options
   * come from [FormVersion.geography]/the profile only — no network — so this is safe inline on load.
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
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    val previousPath = _uiState.value.answers.valueOf(WHO_ARE_YOU_REGISTERING)
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withSingleValue(questionCode, value)
      // A field this answer just hid (e.g. unchecking a vaccine hides its own date field) must not
      // leave its old value sitting in FormAnswers — see FormHiddenFieldReset's doc for the bug
      // that causes. Mirrors the mother flow's setAnswer.
      it.copy(
        answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers),
        // The Sakhi has taken ownership of this field: drop the "From mother's record" hint and,
        // just as importantly, exempt the value from being cleared by a later path switch.
        motherPrefilledCodes = it.motherPrefilledCodes - questionCode,
      )
    }
    if (questionCode == WHO_ARE_YOU_REGISTERING && value != previousPath) {
      onRegistrationPathChanged(value)
    }
    recomputeDerivedFields()
    // Refusing consent ends the registration outright (SRS / form spec: "If No → stop form"), so it
    // is signalled here — the single place a "no" can enter the answers. The mother-record
    // inheritance (MotherPrefill) only ever writes "yes", never "no".
    if (questionCode == DID_WE_RECEIVE_CONSENT && value == CONSENT_NO) {
      _consentRefused.tryEmit(Unit)
    }
  }

  /**
   * Reacts to the row-1 path radio changing.
   *
   * Leaving the registered-mother path discards the link and every *untouched* inherited value —
   * they describe a mother this registration is no longer attached to. Values the Sakhi edited
   * herself have already left `motherPrefilledCodes` and are kept, because re-typing an address just
   * because she changed her mind about the path would be its own bug.
   *
   * Entering the path fetches the picker list lazily, so a direct-path registration makes no network
   * call at all.
   */
  private fun onRegistrationPathChanged(newPath: String?) {
    when (newPath) {
      PATH_REGISTERED_MOTHER -> loadMothers()
      else -> _uiState.update {
        it.copy(
          answers = MotherPrefill.clear(it.answers, it.motherPrefilledCodes),
          motherPrefilledCodes = emptySet(),
          selectedMotherId = null,
          motherLoadFailed = false,
        )
      }
    }
  }

  /** Fetches the selectable mothers. Re-entrant-safe; also the retry entry point for the screen's
   * offline empty state. */
  fun loadMothers() {
    if (_uiState.value.isLoadingMothers) return
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingMothers = true, motherLoadFailed = false) }
      val mothers = motherLinkRepository.getRegisteredMothers()
      _uiState.update {
        it.copy(
          isLoadingMothers = false,
          motherOptions = mothers.orEmpty(),
          // null (never fetched, nothing cached) is a different state from an empty list, and the
          // screen words them differently — "connect once" vs "register the mother first".
          motherLoadFailed = mothers == null,
        )
      }
    }
  }

  /**
   * Links [mother] to this registration and copies her record onto the draft (CR-031).
   *
   * The consent lookup is a second call that is allowed to fail: offline, the geography/name/DOB
   * prefill still lands and only consent inheritance is skipped. Re-selecting overwrites values the
   * Sakhi edited since the previous selection — an explicit re-pick is authoritative.
   */
  fun selectMother(mother: LinkedMother) {
    val version = _uiState.value.version ?: return
    viewModelScope.launch {
      // Both allowed to fail independently (offline, 404): the id/name/DOB/geography prefill above
      // must still land even if one or both of these come back null.
      val consent = motherLinkRepository.getMotherConsent(mother.id)
      val socioDemographics = motherLinkRepository.getMotherSocioDemographics(mother.id)
      val result = MotherPrefill.apply(
        answers = _uiState.value.answers,
        mother = mother,
        consent = consent,
        geography = version.geography.orEmpty(),
        socioDemographics = socioDemographics,
        formSchema = version.schemaJson,
        // So mother_age (see MotherPrefillQuestionCodes.MOTHER_AGE) is derived against the same
        // "today" as every other computed field on this form, not MotherPrefill's own now()
        // default.
        registrationDate = registrationDate,
      )
      _uiState.update {
        it.copy(
          answers = result.answers,
          selectedMotherId = mother.id,
          motherPrefilledCodes = result.prefilledCodes,
        )
      }
      recomputeDerivedFields()
    }
  }

  /**
   * Display text for the mother-link field: the selected mother's name, never her UUID.
   *
   * Falls back to the raw id when the selection isn't in the current list — a resumed draft, or a
   * mother closed since — so the Sakhi sees that *something* is linked and can re-pick, rather than
   * an empty field that looks like an unanswered required question.
   */
  fun selectedMotherLabel(): String? {
    val state = _uiState.value
    val id = state.selectedMotherId ?: state.answers.valueOf(MOTHER_BENEFICIARY_ID) ?: return null
    return state.motherOptions.firstOrNull { it.id == id }?.fullName?.takeIf { it.isNotBlank() } ?: id
  }

  /** Whether [questionCode] currently holds a value copied from the linked mother's record. */
  fun isPrefilledFromMother(questionCode: String): Boolean =
    questionCode in _uiState.value.motherPrefilledCodes

  /** Whether the mother-link picker replaces the generic renderer for this field. True only for
   * `mother_beneficiary_id` on the registered-mother path. */
  fun isMotherLinkField(field: FormFieldSchema): Boolean =
    field.questionCode == MOTHER_BENEFICIARY_ID &&
      _uiState.value.answers.valueOf(WHO_ARE_YOU_REGISTERING) == PATH_REGISTERED_MOTHER

  fun setMultiAnswer(questionCode: String, values: List<String>) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withMultiValue(questionCode, values)
      // Unchecking a vaccine (or picking "None") hides its date field — clear that stale value
      // the same way setAnswer does, so it never rides along in the submission payload.
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
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

  /** Recomputes [ChildFormUiState.validationError] from the current answers (age-eligibility
   * gates). Kept on state so the screen can render it inline and [isReadyToSubmit] can gate on it.
   * Consent is NOT one of these — a refusal exits the flow rather than showing an inline error. */
  private fun revalidate() {
    val answers = _uiState.value.answers
    _uiState.update { it.copy(validationError = computeValidationError(answers)) }
  }

  private fun computeValidationError(answers: FormAnswers): ChildValidationError? {
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
        !hiddenByDirectPathFallback(field, path)
    }
  }

  /**
   * App-side fallback that hides `mother_beneficiary_id` on the direct path (mother not registered →
   * no id to link).
   *
   * **This rule belongs in the schema, not here.** The backend's validator
   * (`form-validation.ts`) only skips a required field when the schema declares `visibleWhen` for
   * it; a rule that exists solely in the app is invisible to the backend, which then rejects the
   * submission with `422 — Missing required field: mother_beneficiary_id`. That is a live defect
   * pending a schema change to add:
   *
   * ```
   * "visibleWhen": { "field": "who_are_you_registering_in_the_program",
   *                  "operator": "eq",
   *                  "value": "child_of_a_registered_pregnant_woman" }
   * ```
   *
   * Written to RETIRE ITSELF: once the schema carries that `visibleWhen`, the generic
   * [FormVisibilityEvaluator] above already hides the field, this fallback stops applying, and both
   * sides derive the rule from one declaration. No further app release is needed to pick the fix up
   * — and this whole function can then be deleted.
   */
  private fun hiddenByDirectPathFallback(field: FormFieldSchema, path: String?): Boolean =
    field.questionCode == MOTHER_BENEFICIARY_ID &&
      field.visibleWhen == null &&
      path == PATH_DIRECT

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
      // Geography answers must be the backend's own geographyUnitIds, shipped in the active
      // version's `geography` — never the static GeographyRepository cascade. Using the hardcoded
      // cascade is what produced `pii.phcId does not refer to a known geography unit` (HTTP 422) on
      // the mother flow (CR-018), and here it also returned NO options at all for Pada/PHC/Sub
      // Centre (each requires an already-answered village), which left those required fields
      // unfillable and the section's Next button permanently disabled.
      return geographyFieldOptionsResolver.optionsFromVersionGeography(
        field.questionCode,
        _uiState.value.version?.geography.orEmpty(),
      )
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

  /**
   * Whether every required field in [fields] is answered, every `number` field with a
   * `numericRange` satisfies it, and every `date` field with a [FormDateRuleset] rule holds an
   * acceptable value. Shared by [isReadyToSubmit] and [isSectionReady].
   *
   * The date check was missing here while the mother flow had it, so a rule-breaking date (e.g. a
   * mother DOB outside 10-50) rendered an inline error under the field but still let Next/Submit
   * through. A date field the ruleset has no rule for is unconstrained, so this is inert for the
   * child form's other dates.
   */
  private fun fieldsAnsweredAndInRange(fields: List<FormFieldSchema>): Boolean {
    val state = _uiState.value
    val allRequiredAnswered = fields.all { field ->
      // A computedFrom field is never Sakhi-entered — required-gating it would permanently block
      // submission whenever its formula isn't confirmed yet (e.g. unique_id) with nothing the Sakhi
      // could do. Skip it here; a missing derived value is a backend/data gap to chase separately.
      //
      // The vaccination-at-birth date fields are the one place `field.required` alone isn't the
      // whole story — schema says `required: false` for all 4 (correct: each is only mandatory
      // once its own checkbox is checked), so VaccinationAtBirthQuestionCodes adds them back in
      // here. See that object's doc (mirrors TdDoseQuestionCodes on the mother flow).
      val effectivelyRequired = field.required ||
        field.questionCode in VaccinationAtBirthQuestionCodes.CONDITIONALLY_REQUIRED_DATE_QUESTION_CODES
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

    val allDatesValid =
      FormDateRuleset.allDatesValid(fields, state.answers, registrationDate)

    return allRequiredAnswered && allRangesValid && allDatesValid
  }

  /** Per-tab gate for the "next tab" button: every visible required field in [section] answered and
   * in range. Cross-field/eligibility rules are only enforced at final submit via [isReadyToSubmit]. */
  fun isSectionReady(section: String): Boolean {
    if (_uiState.value.version == null) return false
    // No consent gate here: a refusal now leaves the screen entirely (see [consentRefused]), so
    // there is no state in which this would be evaluated with consent == "no".
    return fieldsAnsweredAndInRange(fieldsInSection(section))
  }

  /** Whether every currently-visible required field has an answer, every `number` field satisfies
   * its range, no cross-field rule is violated, and there is no client-side eligibility error
   * ([ChildFormUiState.validationError]). Consent is not re-checked — a refusal exits the flow
   * before Submit is reachable, and
   * [org.armman.sakhi.data.childregistration.ChildRegistrationSubmissionMapper] still refuses to
   * build a payload without a "yes" as a last line of defence. */
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
              SubmissionState.Failed(ChildSubmitFailureKind.DUPLICATE, null)
            is ChildFormSubmitResult.Failed ->
              // result.message is already the Sakhi-facing sentence — ChildFormSyncExecutor cleans it
              // via userFacingMessage() (ChildRegistrationSubmissionException.userMessage), so the raw
              // HTTP/JSON body never reaches here. No further sanitization at this layer.
              SubmissionState.Failed(ChildSubmitFailureKind.GENERIC, result.message)
          },
        )
      }
    }
  }
}
