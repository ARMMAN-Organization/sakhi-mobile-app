package org.armman.sakhi.ui.adhocform

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
import org.armman.sakhi.data.adhocform.AdHocFormDraftRepository
import org.armman.sakhi.data.adhocform.AdHocFormSubmitResult
import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.forms.FormAnswers
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
import org.armman.sakhi.data.lookup.LookupRepository
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class AdHocFormUiState(
  val isLoading: Boolean = true,
  /** Non-null only if the schema could not be loaded at all — the form genuinely can't render
   * without one. */
  val hasError: Boolean = false,
  val version: FormVersion? = null,
  val answers: FormAnswers = FormAnswers(),
  val capturedImages: Map<String, String> = emptyMap(),
  /** question_code -> absolute on-disk file path for every captured `image` field — separate
   * from [capturedImages] (the `content://` display URI) since the data layer
   * ([org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]) needs a real path it can
   * open without a UI `Context`, for Referral Follow-up's evidence-upload side effect. Set
   * alongside [capturedImages] by [AdHocFormScreen]'s capture flow, which is the only place that
   * knows both the URI and the path it was built from. */
  val capturedImagePaths: Map<String, String> = emptyMap(),
  val isSubmitting: Boolean = false,
  /** The beneficiary's own enrollment date, fetched alongside the form schema — see
   * [AdHocFormViewModel.prefillTodayDateFields] and [FormDateRuleset.DATE_OF_EVENT_QUESTION_CODE].
   * Null while still loading, and null after loading if the beneficiary's registration date isn't
   * resolvable (matches [org.armman.sakhi.data.beneficiary.Beneficiary.registrationDate]'s own
   * nullability). */
  val beneficiaryRegistrationDate: LocalDate? = null,
)

sealed interface AdHocFormEvent {
  data object ExitForm : AdHocFormEvent
  data object Submitted : AdHocFormEvent
  data object QueuedOffline : AdHocFormEvent
  data class SubmitFailed(val message: String) : AdHocFormEvent
}

/**
 * Drives any of the five schema-driven ad-hoc forms (`REFERRAL_VISIT`, `REFERRAL_FOLLOWUP_VISIT`,
 * `ANC_CLOSURE_VISIT`, `CHILD_CLOSURE_VISIT`, `BENEFICIARY_REOPEN_VISIT`) opened directly from a
 * beneficiary's profile — [formCode] is a nav arg, not hardcoded, since one ViewModel/screen serves
 * all five (mirrors [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s own
 * "formCode is a real parameter" shape).
 *
 * [localFormInstanceUuid] is generated fresh on every ViewModel creation (i.e. every time the
 * Sakhi navigates to this screen for this beneficiary+formCode) rather than an existing draft
 * being looked up and resumed. This is the simplest correct choice for this pass: a beneficiary
 * can have several independent drafts of the SAME form code over time (e.g. two Referral
 * Follow-ups months apart), so "the most recent draft for this beneficiary+formCode" is not
 * unambiguously "the one to resume" without a picker UI that doesn't exist yet. The tradeoff: if
 * the Sakhi opens this screen, fills part of the form, and leaves without submitting, then reopens
 * it, she gets a blank form and a second orphaned PENDING draft row rather than her half-finished
 * answers back — a real gap, flagged rather than silently accepted. Resuming a genuine in-progress
 * draft (surviving process death, not just recomposition) would need a "pick up where you left off"
 * entry point, deliberately left to a later pass.
 */
@HiltViewModel
class AdHocFormViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val lookupRepository: LookupRepository,
  private val adHocFormDraftRepository: AdHocFormDraftRepository,
  private val formAuditRepository: FormAuditRepository,
  private val beneficiaryProfileRepository: BeneficiaryProfileRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  val beneficiaryId: String = savedStateHandle[NAV_ARG_BENEFICIARY_ID] ?: ""
  val formCode: String = savedStateHandle[NAV_ARG_FORM_CODE] ?: ""

  /** Sakhi-facing header title for [formCode] — CR-Referral-01 (2026-09-01): the header used to
   * show the raw formCode string (e.g. "REFERRAL_FOLLOWUP_VISIT") verbatim. Falls back to the raw
   * code itself for any future ad-hoc formCode not yet in [AD_HOC_FORM_TITLES] rather than showing
   * nothing. */
  val formTitle: String get() = AD_HOC_FORM_TITLES[formCode] ?: formCode

  /**
   * CR-Closure-03: true only for the PP5-completion forced mother-closure prompt (see
   * [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen]'s `routeAfterSubmit` and
   * [org.armman.sakhi.ui.navigation.AppNavHost]'s `onSubmittedTriggersClosure` wiring). Read by
   * [org.armman.sakhi.ui.adhocform.AdHocFormScreen] to suppress its own back-arrow/system-back
   * exit -- the SRS requires this form be completed before exit in that one flow, unlike every
   * other ad-hoc form's ordinary voluntary-open-from-profile path, which stays freely dismissible.
   */
  val forced: Boolean = savedStateHandle[NAV_ARG_FORCED] ?: false

  /** REFERRAL_FOLLOWUP_VISIT only — the parent referral this submission drives a status
   * transition on (see [AdHocFormDraftEntity.referralId]'s doc). Blank for every other ad-hoc
   * form, normalized to null before it reaches the coordinator/draft repository. */
  private val referralId: String? = (savedStateHandle[NAV_ARG_REFERRAL_ID] ?: "").takeIf { it.isNotBlank() }

  /** Which visit (e.g. "ANC 3") the Sakhi picked in the "which visit is this referral for?"
   * dialog on the beneficiary profile screen, before this ViewModel was even created — blank for
   * every ad-hoc form except Referral. Prefills `visit_name` (spec row 2: "Autopopulate visit name
   * from which visit referral is flagged") on load. */
  private val visitName: String = savedStateHandle[NAV_ARG_VISIT_NAME] ?: ""

  /** Fresh per ViewModel instance — see the class doc's tradeoff note. Stable across recompositions
   * and across retries within the same ViewModel lifetime (a resubmission after a failure reuses
   * the same draft row rather than creating a new one). */
  val localFormInstanceUuid: String = UUID.randomUUID().toString()

  private val _uiState = MutableStateFlow(AdHocFormUiState())
  val uiState: StateFlow<AdHocFormUiState> = _uiState.asStateFlow()

  private val _events = Channel<AdHocFormEvent>(Channel.BUFFERED)
  val events: Flow<AdHocFormEvent> = _events.receiveAsFlow()

  init {
    load()
  }

  /** (Re)loads the active schema for [formCode]. Safe to call again after a load error. */
  fun load() {
    _uiState.update { AdHocFormUiState(isLoading = true) }
    viewModelScope.launch {
      if (beneficiaryId.isBlank() || formCode.isBlank()) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val version = formsRepository.getActiveVersion(formCode)
      if (version == null) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      _uiState.update { it.copy(isLoading = false, version = version) }
      // Mirrors CR-035's exact rule (see DynamicVisitFormViewModel.load): only on genuine success
      // (version confirmed non-null), and every open counts, not just the first.
      formAuditRepository.recordOpened(localFormInstanceUuid, formCode)
      loadBeneficiaryRegistrationDate()
      prefillTodayDateFields()
      prefillVisitName()
      prefillAutoNumberedVisitName()
    }
  }

  /** Best-effort: a beneficiary lookup failure (offline, id not resolvable) must not block the
   * already-loaded form — [FormDateRuleset.DATE_OF_EVENT_QUESTION_CODE]'s lower bound simply stays
   * unset in that case, same as any other missing-data gap in that file. */
  private suspend fun loadBeneficiaryRegistrationDate() {
    val profile = runCatching { beneficiaryProfileRepository.getBeneficiary(beneficiaryId) }.getOrNull()
    _uiState.update { it.copy(beneficiaryRegistrationDate = profile?.registrationDate) }
  }

  /** Every question code across the five ad-hoc forms whose spec explicitly wants today's date
   * on load: ANC/Infant Closure's "Closure visit date" (spec row 1) and Referral's "Referral visit
   * form filled date" (spec row 1) — see [FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE] /
   * [FormDateRuleset.REFERRAL_FORM_FILLED_DATE_QUESTION_CODE] docs for the exact spec wording each
   * one is matching. */
  private val TODAY_PREFILL_QUESTION_CODES = setOf(
    FormDateRuleset.CLOSURE_VISIT_DATE_QUESTION_CODE,
    FormDateRuleset.REFERRAL_FORM_FILLED_DATE_QUESTION_CODE,
    FormDateRuleset.FOLLOWUP_FORM_FILLED_DATE_QUESTION_CODE,
  )

  /** Auto-fills whichever of [TODAY_PREFILL_QUESTION_CODES] the loaded schema actually carries
   * with today's date — mirrors [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel
   * .prefillDefaultVisitDate]'s exact reasoning. Only sets a field if it's blank (a restored
   * draft/backend answer is never overwritten); most of the five ad-hoc forms carry none of these
   * codes at all, in which case this is a no-op. */
  private fun prefillTodayDateFields() {
    _uiState.update { state ->
      val fieldsPresent = state.version?.schemaJson.orEmpty().map { it.questionCode }.toSet()
      var answers = state.answers
      for (code in TODAY_PREFILL_QUESTION_CODES) {
        if (code in fieldsPresent && answers.valueOf(code).isNullOrBlank()) {
          answers = answers.withSingleValue(code, LocalDate.now().toString())
        }
      }
      state.copy(answers = answers)
    }
  }

  /** Auto-fills `visit_name` with the visit the Sakhi picked before opening this form — see
   * [visitName]'s doc. No-op when blank (every ad-hoc form except Referral, or a Referral opened
   * without going through the picker), when the loaded schema doesn't carry the field, or when it
   * already has an answer. */
  private fun prefillVisitName() {
    if (visitName.isBlank()) return
    _uiState.update { state ->
      val hasField = state.version?.schemaJson.orEmpty().any { it.questionCode == VISIT_NAME_QUESTION_CODE }
      if (hasField && state.answers.valueOf(VISIT_NAME_QUESTION_CODE).isNullOrBlank()) {
        state.copy(answers = state.answers.withSingleValue(VISIT_NAME_QUESTION_CODE, visitName))
      } else {
        state
      }
    }
  }

  /** Which question code gets auto-numbered for each ad-hoc form, and the prefix to number it
   * with: Referral's "Referral visit name" (spec row 3: "RV1, RV2 etc, Autocalculated") and
   * Referral Follow-up's "Referral followup visit name" (spec row 3: "RFU1, RFU2 etc,
   * Autocalculate"). Only one entry can ever match a given loaded schema — each [formCode] carries
   * at most one of these two question codes — so iterating the whole map is safe. */
  private val AUTO_NUMBERED_VISIT_NAME_PREFIXES = mapOf(
    REFERRAL_VISIT_NAME_QUESTION_CODE to "RV",
    REFERRAL_FOLLOWUP_VISIT_NAME_QUESTION_CODE to "RFU",
  )

  /** Counts this beneficiary's past [formCode] ad-hoc-form submissions on this device (via
   * [AdHocFormDraftRepository.countByFormCode]) and labels this one one past that, using whichever
   * of [AUTO_NUMBERED_VISIT_NAME_PREFIXES] the loaded schema actually carries. No-op when the
   * schema carries neither code, or the one it does carry already has an answer (a restored draft
   * is never relabeled). */
  private suspend fun prefillAutoNumberedVisitName() {
    val fieldsPresent = _uiState.value.version?.schemaJson.orEmpty().map { it.questionCode }.toSet()
    val (code, prefix) = AUTO_NUMBERED_VISIT_NAME_PREFIXES.entries
      .firstOrNull { (code, _) -> code in fieldsPresent } ?: return
    if (!_uiState.value.answers.valueOf(code).isNullOrBlank()) return
    val count = adHocFormDraftRepository.countByFormCode(beneficiaryId, formCode)
    _uiState.update { state ->
      state.copy(answers = state.answers.withSingleValue(code, "$prefix${count + 1}"))
    }
  }

  fun setAnswer(questionCode: String, value: String?) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withSingleValue(questionCode, value)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
  }

  fun setMultiAnswer(questionCode: String, values: List<String>) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withMultiValue(questionCode, values)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
  }

  fun setCapturedImage(questionCode: String, uri: String?) {
    _uiState.update {
      it.copy(
        capturedImages = if (uri == null) it.capturedImages - questionCode else it.capturedImages + (questionCode to uri),
        answers = it.answers.withSingleValue(questionCode, uri),
      )
    }
  }

  /** See [AdHocFormUiState.capturedImagePaths]'s doc. Called by [AdHocFormScreen] right alongside
   * [setCapturedImage], from the same capture callback that already knows both values. */
  fun setCapturedImagePath(questionCode: String, filePath: String?) {
    _uiState.update {
      it.copy(
        capturedImagePaths = if (filePath == null) {
          it.capturedImagePaths - questionCode
        } else {
          it.capturedImagePaths + (questionCode to filePath)
        },
      )
    }
  }

  /** Fields currently shown, in schema order — honors [FormVisibilityEvaluator]. */
  fun visibleFields(): List<FormFieldSchema> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    return version.schemaJson.filter { FormVisibilityEvaluator.isVisible(it, state.answers) }
  }

  // --- Section tabs + Summary review (CR-Referral-01, 2026-09-01) — mirrors
  // DynamicChildRegistrationViewModel's/DeliverySessionViewModel's own sectionOf/sections/
  // fieldsInSection/isSectionReady/buildSummary exactly; ported here so all five ad-hoc forms get
  // the same "one tab per schema section + a final Summary review tab" shell every other
  // schema-driven form in the app already has, instead of one long flat list. No field/question/
  // validation/submission changes — purely how the same [visibleFields] are grouped and reviewed.

  /** This field's tab label — falls back to [FALLBACK_SECTION] if the schema didn't tag one. */
  fun sectionOf(field: FormFieldSchema): String = field.section ?: FALLBACK_SECTION

  /** Distinct tab labels across currently-visible fields, in the order each first appears. */
  fun sections(): List<String> = visibleFields().map(::sectionOf).distinct()

  /** Visible fields belonging to one tab, in schema order. */
  fun fieldsInSection(section: String): List<FormFieldSchema> =
    visibleFields().filter { sectionOf(it) == section }

  /** Whether every required field in one tab is answered and every `number` field with a
   * `numericRange` satisfies it — the per-tab twin of [isReadyToSubmit], gating that tab's
   * "Next" button the same way every other tabbed dynamic form in the app already does. */
  fun isSectionReady(section: String): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(fieldsInSection(section))
  }

  /** Resolved review data for the Summary tab — every answered, currently-visible field grouped by
   * schema section into its own card, in the order each section first appears, coded values mapped
   * to display labels. Mirrors [org.armman.sakhi.ui.childregistration
   * .DynamicChildRegistrationViewModel.buildSummary] — suspend (unlike
   * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.buildFieldSummary]'s non-suspend
   * version) since [optionsFor] here can hit [LookupRepository] for a `lookup_category_code` field.
   * No `media` branch — see [AdHocFormScreen]'s class doc for why that input type isn't supported
   * on any of these five forms' schemas. */
  suspend fun buildSummary(imageCapturedLabel: String): List<SummarySection> {
    if (_uiState.value.version == null) return emptyList()
    return sections().map { section ->
      SummarySection(
        title = section,
        rows = fieldsInSection(section).mapNotNull { summaryRowFor(it, imageCapturedLabel) },
      )
    }.filter { it.rows.isNotEmpty() }
  }

  private suspend fun summaryRowFor(field: FormFieldSchema, imageCapturedLabel: String): SummaryRow? {
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

  /** Options for a select/radio/multiselect field: inline schema `options` first, then a
   * `lookup_category_code` fetch. No geography special case here — none of the five ad-hoc forms
   * declares a geography question as of this schema version (unlike Mother/Child Registration). */
  suspend fun optionsFor(field: FormFieldSchema): List<FormFieldOption> {
    field.options?.let { return it.sortedBy(FormFieldOption::sortOrder) }
    val categoryCode = field.lookupCategoryCode ?: return emptyList()
    return lookupRepository.getValues(categoryCode)
      .mapIndexed { index, value -> FormFieldOption(label = value.valueLabel, sortOrder = index, valueCode = value.valueCode) }
  }

  /** Cross-field rules currently violated — empty means fine, or not yet evaluable. Covers
   * `ANY_OF_REQUIRED` (Referral Follow-up's real rule) and `REQUIRED_IF_SELECTED` (both Closure
   * forms' death-detail fields) via [FormCrossFieldValidator] — the generic
   * `violatedRules(...)` call below needed no changes to pick up the new rule. */
  fun crossFieldViolations(): List<FormCrossFieldRule> {
    val version = _uiState.value.version ?: return emptyList()
    return FormCrossFieldValidator.violatedRules(version.validationJson, _uiState.value.answers)
  }

  /** Whether every visible required field is answered, every `number` field with a `numericRange`
   * satisfies it, and no cross-field rule is violated — mirrors
   * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.isReadyToSubmit] exactly. */
  fun isReadyToSubmit(): Boolean {
    val state = _uiState.value
    if (state.version == null) return false
    return fieldsAnsweredAndInRange(visibleFields()) && crossFieldViolations().isEmpty()
  }

  private fun fieldsAnsweredAndInRange(fields: List<FormFieldSchema>): Boolean {
    val state = _uiState.value
    val allRequiredAnswered = fields.all { field ->
      if (!field.required || field.computedFrom != null) return@all true
      when (field.inputType) {
        FormFieldInputType.MULTISELECT, FormFieldInputType.MULTISELECT_DATE ->
          state.answers.multiValueOf(field.questionCode).isNotEmpty()
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

  fun exitForm() {
    _events.trySend(AdHocFormEvent.ExitForm)
  }

  /** No-op (and doesn't dispatch an event) if [isReadyToSubmit] is false or a submit is already in
   * flight — mirrors every other dynamic form's submit-button gating contract. */
  fun onSubmit() {
    val state = _uiState.value
    if (state.isSubmitting || !isReadyToSubmit()) return
    val version = state.version ?: return

    _uiState.update { it.copy(isSubmitting = true) }
    viewModelScope.launch {
      val result = adHocFormDraftRepository.submitDraft(
        localFormInstanceUuid = localFormInstanceUuid,
        localBeneficiaryId = beneficiaryId,
        formCode = formCode,
        formVersionId = version.id,
        answers = state.answers,
        referralId = referralId,
        capturedImagePaths = state.capturedImagePaths,
      )
      _uiState.update { it.copy(isSubmitting = false) }
      when (result) {
        is AdHocFormSubmitResult.Synced -> _events.trySend(AdHocFormEvent.Submitted)
        is AdHocFormSubmitResult.QueuedOffline -> _events.trySend(AdHocFormEvent.QueuedOffline)
        is AdHocFormSubmitResult.Failed -> _events.trySend(AdHocFormEvent.SubmitFailed(result.message))
      }
    }
  }

  private companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_FORM_CODE = "formCode"
    const val NAV_ARG_VISIT_NAME = "visitName"
    const val NAV_ARG_FORCED = "forced"
    const val NAV_ARG_REFERRAL_ID = "referralId"

    /** Referral form's spec row 2 — see [AdHocFormViewModel.prefillVisitName]. */
    const val VISIT_NAME_QUESTION_CODE = "visit_name"

    /** Referral form's spec row 3 — see [AdHocFormViewModel.prefillAutoNumberedVisitName]. */
    const val REFERRAL_VISIT_NAME_QUESTION_CODE = "referral_visit_name"

    /** Referral Follow-up's spec row 3 — see [AdHocFormViewModel.prefillAutoNumberedVisitName]. */
    const val REFERRAL_FOLLOWUP_VISIT_NAME_QUESTION_CODE = "referral_followup_visit_name"

    /** See [AdHocFormViewModel.formTitle]'s doc. Covers all five ad-hoc formCodes this ViewModel
     * ever loads (see this class's own doc) — kept here rather than a shared constants object
     * since, like [AD_HOC_FORM_CODE_REFERRAL] et al. in `BeneficiaryProfileScreen`, this is
     * currently the only place any of them needs a Sakhi-facing display title. */
    val AD_HOC_FORM_TITLES = mapOf(
      "REFERRAL_VISIT" to "Referral",
      "REFERRAL_FOLLOWUP_VISIT" to "Referral Follow Up",
      "ANC_CLOSURE_VISIT" to "ANC Closure",
      "CHILD_CLOSURE_VISIT" to "Child Closure",
      "BENEFICIARY_REOPEN_VISIT" to "Reopen Beneficiary",
    )
  }
}
