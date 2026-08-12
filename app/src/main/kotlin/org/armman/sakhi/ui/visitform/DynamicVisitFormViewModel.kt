package org.armman.sakhi.ui.visitform

import android.util.Log
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
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormHiddenFieldReset
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormVisibilityEvaluator
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.visitform.CriticalCondition
import org.armman.sakhi.data.visitform.InfantVisitFormComputedFieldEvaluator
import org.armman.sakhi.data.visitform.InfantVisitRiskAssessment
import org.armman.sakhi.data.visitform.InfantVisitRiskFinding
import org.armman.sakhi.data.visitform.VisitCriticalConditionEvaluator
import org.armman.sakhi.data.visitform.VisitFormComputedFieldEvaluator
import org.armman.sakhi.data.visitform.VisitFormQuestionCodes
import org.armman.sakhi.data.visitform.VisitFormOuterTab
import org.armman.sakhi.data.visitform.VisitFormRiskAssessment
import org.armman.sakhi.data.visitform.VisitFormRiskFinding
import org.armman.sakhi.data.visitform.VisitFormRepository
import org.armman.sakhi.data.visitform.VisitFormDraftRepository
import org.armman.sakhi.data.visitform.VisitFormSubmitResult
import java.time.LocalDate
import javax.inject.Inject

/** ANC_VISIT (mother) / INFANT_VISIT (infant) form codes the active-version endpoint recognises. */
// internal, not private: DynamicVisitFormScreen.kt needs FORM_CODE_MOTHER to gate the Summary
// tab (INFANT_VISIT has no risk model yet - see that check's own comment).
internal const val FORM_CODE_MOTHER = "ANC_VISIT"
internal const val FORM_CODE_INFANT = "INFANT_VISIT"

/** Temporary diagnostic tag for the "couldn't load this visit's data" report (CR-026
 * debugging) — load() had no logging on any of its three failure branches, so it was
 * impossible to tell which one was actually firing from a bug report alone. */
private const val TAG = "SakhiSync"

/** Tab label for a visible field whose schema `section` is missing — mirrors
 * [org.armman.sakhi.ui.forms.FALLBACK_SECTION]; kept as a local copy so this ViewModel doesn't
 * depend on the registration flow's package for one constant. */
private const val FALLBACK_SECTION = "Additional Information"

data class DynamicVisitFormUiState(
  val isLoading: Boolean = true,
  /** True only if the schema and/or the beneficiary/visit context could not be loaded at all —
   * the screen renders the localized `visit_form_error_load` copy for this, same string the
   * retired hand-coded flow used. */
  val hasError: Boolean = false,
  val formCode: String? = null,
  val version: FormVersion? = null,
  val answers: FormAnswers = FormAnswers(),
  /** `media`/`image` fields — same split as
   * [org.armman.sakhi.ui.forms.DynamicFormUiState], see that class's doc. */
  val mediaCompleted: Set<String> = emptySet(),
  val capturedImages: Map<String, String> = emptyMap(),
  /** FR-S-4.4 scaffold, now wired for the mother (ANC_VISIT) flow only — see
   * [DynamicVisitFormViewModel.recheckCriticalCondition]. Always null for INFANT_VISIT: no
   * equivalent danger-sign combination rule has been confirmed with ARMMAN for the infant flow. */
  val criticalCondition: CriticalCondition? = null,
  /** Spec row 12: height is "open only in first visit" — true once [VisitContext.heightCm] shows
   * a prior visit already captured it, so [DynamicVisitFormViewModel.prefillFromVisitContext] both
   * prefilled and locked it. Always false for INFANT_VISIT (no such field). */
  val heightLockedFromContext: Boolean = false,
  /** CR-016c Summary banner chips — carried forward from [VisitContext.comorbidities], see that
   * field's doc. Always empty for INFANT_VISIT (no Summary content built for infants yet). */
  val comorbidities: List<String> = emptyList(),
  /** Carried forward from [org.armman.sakhi.data.visitform.VisitContext.registrationWeightKg] —
   * the baseline [VisitFormComputedFieldEvaluator]'s gestational-weight-gain calculation needs.
   * Always null for INFANT_VISIT (no such field/context for infants). */
  val registrationWeightKg: Double? = null,
  /** True while [DynamicVisitFormViewModel.onFinish]'s submit call is in flight (mother/ANC_VISIT
   * only — see that function's doc). Drives the Submit button's loading state and guards against
   * a double-tap firing two submissions. */
  val isSubmitting: Boolean = false,
  /** Standalone hand-built fields for the Referral outer tab (bharath, 2026-08-08) - NOT part of
   * the ANC_VISIT schema's own "Referrals" section (that's a different set of questions, already
   * rendered as a Visit Data sub-tab). Fetch+render pass scope: captured locally only, Submit
   * fires the same "coming soon" event the rest of the form uses - no backend call yet. */
  val referralDate: LocalDate? = null,
  val referralFacility: String? = null,
  val referralType: String? = null,
)

/** One-shot events the screen reacts to (navigation/toast), mirroring the retired hand-coded
 * ViewModel's [org.armman.sakhi.ui.visitform.VisitFormEvent] one-for-one. */
sealed interface DynamicVisitFormEvent {
  /** Confirmed exit, or a critical-condition dismissal (FR-S-4.4 Option B) — leave the flow. No
   * partial-save exists for this fetch+render pass, same as before. */
  data object ExitForm : DynamicVisitFormEvent

  /** INFANT_VISIT has no submission contract yet (mother/ANC_VISIT does, see
   * [DynamicVisitFormViewModel.onFinish]) — its last-section action button still surfaces this
   * instead of silently doing nothing. Also still used by the standalone Referral tab's own
   * submit stub (CR-028, out of scope for this pass). */
  data object ComingSoon : DynamicVisitFormEvent

  /** The visit form submitted successfully — the screen shows a confirmation and exits. */
  data object Submitted : DynamicVisitFormEvent

  /** CR-026b: submitted while offline (or the immediate online attempt failed) — saved locally
   * and queued for the next manual Data Upload. Distinct from [Submitted] so the Sakhi isn't told
   * this fully reached the server when it hasn't yet; distinct from [SubmitFailed] so she isn't
   * alarmed into thinking her answers were lost, since they weren't. Still exits the form — same
   * "safe to navigate" contract [org.armman.sakhi.data.forms.DynamicFormSubmitResult.QueuedOffline]
   * already has for Mother Registration. */
  data object QueuedOffline : DynamicVisitFormEvent

  /** Submission failed; [message] is the one sentence to show (see
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionException.userMessage]). The Sakhi stays
   * on the form — nothing is lost, her answers are still in [DynamicVisitFormUiState.answers].
   * The draft is queued in the background regardless (CR-026b) — this event alone doesn't tell
   * her that; it stays the same immediate-failure copy as before CR-026b existed. */
  data class SubmitFailed(val message: String) : DynamicVisitFormEvent
}

/**
 * Drives the schema-driven ANC_VISIT/INFANT_VISIT Visit Form — the dynamic replacement for the
 * retired hand-coded [org.armman.sakhi.ui.visitform.VisitFormViewModel]/`VisitDataState` stepper.
 * Fetches the active schema for whichever form the beneficiary's own type calls for
 * ([FormsRepository], same auto-refresh-on-load behaviour as the registration flow) and renders it
 * through the same generic [org.armman.sakhi.ui.forms.DynamicFormField] the Mother/Child
 * registration screens use.
 *
 * Scope for this pass is deliberately fetch + render only (bharath, 2026-08-07): no submission,
 * no offline draft, no cross-field/numeric-range gating — that lands with a real `POST /visits` +
 * `POST /forms/{code}/submissions` contract later (mirrors [org.armman.sakhi.data.visitform
 * .VisitFormRepository]'s own `saveVisit`-not-called-yet note). What IS preserved from the retired
 * flow is FR-S-4.4's critical-condition safety check (mother only — see
 * [VisitCriticalConditionEvaluator]) and the carried-forward [org.armman.sakhi.data.visitform
 * .VisitContext] prefill of `rch_number`/`lmp` (mother only — the context shape has no infant
 * fields to prefill from).
 */
@HiltViewModel
class DynamicVisitFormViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val visitFormRepository: VisitFormRepository,
  private val beneficiaryProfileRepository: BeneficiaryProfileRepository,
  private val visitFormDraftRepository: VisitFormDraftRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  val beneficiaryId: String = savedStateHandle[NAV_ARG_BENEFICIARY_ID] ?: ""
  val visitId: String = savedStateHandle[NAV_ARG_VISIT_ID] ?: ""
  val visitLabel: String = savedStateHandle[NAV_ARG_LABEL] ?: ""

  /** Reference date for computed fields (EDD/gestational age/infant age-in-months) — the visit-form
   * analogue of the registration flow's `registrationDate`. */
  val visitDate: LocalDate = LocalDate.now()

  private val _uiState = MutableStateFlow(DynamicVisitFormUiState())
  val uiState: StateFlow<DynamicVisitFormUiState> = _uiState.asStateFlow()

  private val _events = Channel<DynamicVisitFormEvent>(Channel.BUFFERED)
  val events: Flow<DynamicVisitFormEvent> = _events.receiveAsFlow()

  init {
    load()
  }

  /** (Re)loads the beneficiary's type, the matching active schema, and — for a mother — her
   * carried-forward visit context. Safe to call again after a load error. */
  fun load() {
    _uiState.update { DynamicVisitFormUiState(isLoading = true) }
    viewModelScope.launch {
      if (beneficiaryId.isBlank() || visitId.isBlank()) {
        Log.w(TAG, "DynamicVisitFormViewModel.load(): blank id — beneficiaryId='$beneficiaryId', visitId='$visitId'")
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val formCode = try {
        when (beneficiaryProfileRepository.getBeneficiary(beneficiaryId).type) {
          BeneficiaryType.MOTHER -> FORM_CODE_MOTHER
          BeneficiaryType.INFANT -> FORM_CODE_INFANT
        }
      } catch (e: NoSuchElementException) {
        Log.w(TAG, "DynamicVisitFormViewModel.load($beneficiaryId): getBeneficiary threw NoSuchElementException")
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val version = formsRepository.getActiveVersion(formCode)
      if (version == null) {
        Log.w(TAG, "DynamicVisitFormViewModel.load($beneficiaryId): getActiveVersion($formCode) returned null")
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      // Temporary diagnostic for the "sonography No doesn't hide LMP Date/gestational age/EDD"
      // report — dumps EVERY field's questionCode/label/visibleWhen from the LIVE schema (not a
      // guessed subset — an earlier, narrower version of this log matched nothing because the
      // guessed question codes were wrong), so we can tell a missing/misconfigured backend
      // condition apart from a client evaluator bug.
      Log.d(TAG, "DynamicVisitFormViewModel.load($formCode): schemaJson has ${version.schemaJson.size} fields")
      version.schemaJson.forEach { field ->
        Log.d(
          TAG,
          "DynamicVisitFormViewModel.load field: questionCode='${field.questionCode}' " +
            "label='${field.label}' visibleWhen=${field.visibleWhen}",
        )
      }
      _uiState.update { it.copy(isLoading = false, formCode = formCode, version = version) }
      prefillDefaultVisitDate()
      if (formCode == FORM_CODE_MOTHER) prefillFromVisitContext()
      recomputeDerivedFields()
      recheckCriticalCondition()
    }
  }

  /** Auto-fills "Date of visit" with today's date on load, for either beneficiary type — the
   * Sakhi is almost always filling this in live, during the visit (bharath, 2026-08-07). Only
   * sets it if blank, so a value restored from a future draft/backend answer isn't overwritten. */
  private fun prefillDefaultVisitDate() {
    _uiState.update { state ->
      if (state.answers.valueOf(VisitFormQuestionCodes.DATE_OF_VISIT).isNullOrBlank()) {
        state.copy(answers = state.answers.withSingleValue(VisitFormQuestionCodes.DATE_OF_VISIT, visitDate.toString()))
      } else {
        state
      }
    }
  }

  /** Prefills `rch_number`/`lmp` from the carried-forward [org.armman.sakhi.data.visitform
   * .VisitContext] (registration + prior visit), same fields the retired hand-coded flow
   * auto-populated. Best-effort: a context fetch failure here doesn't block the (already-loaded)
   * form — the Sakhi can still fill both fields in manually. */
  private suspend fun prefillFromVisitContext() {
    val context = try {
      visitFormRepository.getVisitContext(beneficiaryId, visitId)
    } catch (e: NoSuchElementException) {
      return
    }
    _uiState.update { state ->
      var answers = state.answers
      if (answers.valueOf(RCH_NUMBER_QUESTION_CODE).isNullOrBlank() && context.rchNumber.isNotBlank()) {
        answers = answers.withSingleValue(RCH_NUMBER_QUESTION_CODE, context.rchNumber)
      }
      if (answers.valueOf(VisitFormQuestionCodes.LMP).isNullOrBlank()) {
        answers = answers.withSingleValue(VisitFormQuestionCodes.LMP, context.lmp.toString())
      }
      // Spec row 2: "Autopopulated based on the respective visit name" (ANC1/ANC2/... or
      // HRV1/HRV2/...) — carried forward from VisitContext, same as RCH number/LMP above.
      if (answers.valueOf(VISIT_TYPE_QUESTION_CODE).isNullOrBlank() && context.visitTypeLabel.isNotBlank()) {
        answers = answers.withSingleValue(VISIT_TYPE_QUESTION_CODE, context.visitTypeLabel)
      }
      // Spec row 12: "Open only in first visit and auto populate in the rest" — a non-null
      // heightCm means a PRIOR visit already captured it (see StaticVisitFormRepository's
      // v1-has-no-height-yet convention), so this visit both prefills and locks it.
      val heightFromContext = context.heightCm
      if (heightFromContext != null && answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM).isNullOrBlank()) {
        answers = answers.withSingleValue(VisitFormQuestionCodes.HEIGHT_CM, heightFromContext.toString())
      }
      state.copy(
        answers = answers,
        heightLockedFromContext = heightFromContext != null,
        comorbidities = context.comorbidities,
        registrationWeightKg = context.registrationWeightKg,
      )
    }
  }

  // --- Referral tab (standalone form, not schema-driven - see DynamicVisitFormUiState's doc) ---

  fun setReferralDate(date: LocalDate?) {
    _uiState.update { it.copy(referralDate = date) }
  }

  fun setReferralFacility(facility: String?) {
    _uiState.update { it.copy(referralFacility = facility) }
  }

  fun setReferralType(type: String?) {
    _uiState.update { it.copy(referralType = type) }
  }

  /** Summary tab's Tests card rows - see [VisitFormRiskAssessment]'s scope note (BP/Hb/Weight
   * only, this pass). Always empty for INFANT_VISIT. */
  fun testsFindings(): List<VisitFormRiskFinding> =
    if (_uiState.value.formCode == FORM_CODE_MOTHER) {
      VisitFormRiskAssessment.buildTestsFindings(_uiState.value.answers)
    } else {
      emptyList()
    }

  /** Worst-of [testsFindings] and any recorded comorbidity - comorbidities alone don't have a
   * graded tier, so their mere presence counts as at least [org.armman.sakhi.data.beneficiary
   * .RiskLevel.MODERATE] rather than the top-of-list HIGH, unless a vital reading is itself HIGH. */
  fun overallRiskLevel(): RiskLevel {
    val findingLevels = testsFindings().map { it.riskLevel }
    val comorbidityLevel = if (_uiState.value.comorbidities.isNotEmpty()) {
      listOf(RiskLevel.MODERATE)
    } else {
      emptyList()
    }
    return VisitFormRiskAssessment.overall(findingLevels + comorbidityLevel)
  }

  /** Summary tab's known-risk chips for INFANT_VISIT (2026-08-08) - see
   * [InfantVisitRiskAssessment]'s doc. Always empty for ANC_VISIT (mother keeps her own
   * [testsFindings]/[overallRiskLevel] path above). */
  fun infantKnownRisks(): List<InfantVisitRiskFinding> =
    if (_uiState.value.formCode == FORM_CODE_INFANT) {
      InfantVisitRiskAssessment.buildKnownRisks(_uiState.value.answers)
    } else {
      emptyList()
    }

  /** Worst-of [infantKnownRisks] - LOW when the list is empty (no risk flagged this visit). */
  fun infantOverallRiskLevel(): RiskLevel = InfantVisitRiskAssessment.overall(infantKnownRisks().map { it.riskLevel })

  fun setAnswer(questionCode: String, value: String?) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withSingleValue(questionCode, value)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
    recomputeDerivedFields()
    recheckCriticalCondition()
  }

  fun setMultiAnswer(questionCode: String, values: List<String>) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withMultiValue(questionCode, values)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
    recomputeDerivedFields()
    recheckCriticalCondition()
  }

  /** See [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.markMediaComplete] — same
   * "mirror completion into FormAnswers as a real value" reasoning. */
  fun markMediaComplete(questionCode: String) {
    _uiState.update {
      it.copy(
        mediaCompleted = it.mediaCompleted + questionCode,
        answers = it.answers.withSingleValue(questionCode, "true"),
      )
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

  private fun recomputeDerivedFields() {
    val state = _uiState.value
    val version = state.version ?: return
    val formCode = state.formCode ?: return
    var answers = state.answers
    version.schemaJson.forEach { field ->
      val computedFrom = field.computedFrom ?: return@forEach
      val value = when (formCode) {
        FORM_CODE_MOTHER -> VisitFormComputedFieldEvaluator.compute(
          computedFrom,
          answers,
          visitDate,
          state.registrationWeightKg,
        )
        FORM_CODE_INFANT -> InfantVisitFormComputedFieldEvaluator.compute(computedFrom, answers, visitDate)
        else -> null
      }
      answers = answers.withSingleValue(field.questionCode, value)
    }
    _uiState.update { it.copy(answers = answers) }
  }

  /** FR-S-4.4 — re-evaluated after every answer change while the active form is ANC_VISIT (mother);
   * always a no-op for INFANT_VISIT (see [DynamicVisitFormUiState.criticalCondition]'s doc). Once a
   * condition is flagged it stays flagged until [dismissCritical] — a later answer edit that no
   * longer meets the threshold does not silently clear an urgent banner the Sakhi hasn't acted on
   * yet, mirroring the retired flow's one-way `reportCriticalCondition`. */
  private fun recheckCriticalCondition() {
    val state = _uiState.value
    if (state.formCode != FORM_CODE_MOTHER || state.criticalCondition != null) return
    val condition = VisitCriticalConditionEvaluator.evaluate(state.answers) ?: return
    _uiState.update { it.copy(criticalCondition = condition) }
  }

  /** FR-S-4.4 Option B: closing the banner discards the in-memory draft and exits — no partial
   * save, same as the retired hand-coded flow. There is nothing else to discard here (no draft
   * repository this pass), so this is just the state reset + the exit event. */
  fun dismissCritical() {
    _uiState.update { it.copy(criticalCondition = null) }
    _events.trySend(DynamicVisitFormEvent.ExitForm)
  }

  /** Confirmed exit via the header/back-button confirmation dialog — same no-partial-save contract. */
  fun exitForm() {
    _events.trySend(DynamicVisitFormEvent.ExitForm)
  }

  /**
   * Real submit for the mother (ANC_VISIT) flow: `POST /visits` then
   * `POST /forms/ANC_VISIT/submissions`, via [VisitFormDraftRepository.submitDraft] (CR-026b) —
   * saves locally first, then attempts the real submission immediately while online (identical
   * outcome to the pre-CR-026b direct coordinator call), or queues it for the next manual Data
   * Upload while offline. INFANT_VISIT has no submission contract yet, so its last-section action
   * still fires [DynamicVisitFormEvent.ComingSoon].
   */
  fun onFinish() {
    val state = _uiState.value
    if (state.formCode != FORM_CODE_MOTHER) {
      _events.trySend(DynamicVisitFormEvent.ComingSoon)
      return
    }
    if (state.isSubmitting) return
    val version = state.version ?: return

    _uiState.update { it.copy(isSubmitting = true) }
    viewModelScope.launch {
      val result = visitFormDraftRepository.submitDraft(
        localScheduleUuid = visitId,
        formCode = state.formCode.orEmpty(),
        formVersionId = version.id,
        answers = _uiState.value.answers,
        visitDate = visitDate,
      )
      _uiState.update { it.copy(isSubmitting = false) }
      when (result) {
        is VisitFormSubmitResult.Synced -> _events.trySend(DynamicVisitFormEvent.Submitted)
        is VisitFormSubmitResult.QueuedOffline -> _events.trySend(DynamicVisitFormEvent.QueuedOffline)
        is VisitFormSubmitResult.Failed ->
          _events.trySend(DynamicVisitFormEvent.SubmitFailed(result.message))
      }
    }
  }

  /** Fields currently shown, in schema order — honors [FormVisibilityEvaluator]. Unlike the
   * registration flow, no [org.armman.sakhi.data.forms.NonRenderableQuestionCodes] exclusion is
   * applied: neither ANC_VISIT nor INFANT_VISIT declares any of those codes as of this schema
   * version. */
  fun visibleFields(): List<FormFieldSchema> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    val visible = version.schemaJson.filter { FormVisibilityEvaluator.isVisible(it, state.answers) }
    val endedAt = visible.indexOfFirst { it.questionCode == IF_NO_MENTION_REASONS_QUESTION_CODE }
    // Spec row 3: "If no [to 'met the beneficiary'], then goto 4 [the reasons dropdown] and end
    // form" — same rule, same question codes, for both ANC_VISIT and INFANT_VISIT (bharath,
    // 2026-08-08). Everything after the reasons dropdown disappears, which in turn empties every
    // other section — sections()/subSections() below already treat an empty section as "nothing
    // mapped there", so this alone collapses the Visit Data sub-tabs to just this one and leaves
    // Summary/Health Info/Referral blank, with no separate "form ended" flag needed.
    return if (formEndedEarly(state.answers) && endedAt >= 0) visible.subList(0, endedAt + 1) else visible
  }

  /** True once the Sakhi has recorded she couldn't meet the beneficiary (Q3 = "No") — see
   * [visibleFields]'s truncation. */
  private fun formEndedEarly(answers: FormAnswers): Boolean =
    answers.valueOf(HAVE_YOU_BEEN_ABLE_TO_MEET_QUESTION_CODE) == VALUE_NO

  fun sectionOf(field: FormFieldSchema): String = field.section ?: FALLBACK_SECTION

  fun sections(): List<String> = visibleFields().map(::sectionOf).distinct()

  fun fieldsInSection(section: String): List<FormFieldSchema> =
    visibleFields().filter { sectionOf(it) == section }

  /** Whether every required field in [fields] is answered and every `number` field with a
   * `numericRange` satisfies it — the visit-form twin of
   * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.fieldsAnsweredAndInRange].
   * Shared by [isReadyToSubmit] (all visible fields) and [isSectionReady] (one sub-tab's fields)
   * so both gate on identical per-field rules. No cross-field/date/obstetric rule sets apply here
   * — those are registration-specific; nothing analogous has been requested for ANC_VISIT/
   * INFANT_VISIT as of this schema version. */
  private fun fieldsAnsweredAndInRange(fields: List<FormFieldSchema>): Boolean {
    val state = _uiState.value
    val allRequiredAnswered = fields.all { field ->
      // A computedFrom field is never Sakhi-entered — required-gating it would permanently block
      // progress whenever its formula isn't confirmed yet, with nothing the Sakhi could do about
      // it. Same rationale as the registration form's identical guard.
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

  /** Per-sub-tab gate for the "Next" button: every visible required field in [section] answered
   * and in range. Mirrors the registration form's [org.armman.sakhi.ui.forms
   * .DynamicMotherRegistrationViewModel.isSectionReady]. */
  fun isSectionReady(section: String): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(fieldsInSection(section))
  }

  /** Whether every currently-visible required field has an answer and every `number` field with a
   * `numericRange` satisfies it — gates the final Submit button. */
  fun isReadyToSubmit(): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(visibleFields())
  }

  /** Every schema section — for either ANC_VISIT or INFANT_VISIT — buckets under
   * [VisitFormOuterTab.VISIT_DATA] as a pill sub-tab this pass (bharath, 2026-08-07: confirmed
   * explicitly, not just a coincidental name match). SUMMARY/HEALTH_INFO/REFERRAL always render a
   * placeholder for both beneficiary types — see [VisitFormOuterTab]'s doc. */
  private fun outerTabFor(section: String): VisitFormOuterTab = VisitFormOuterTab.VISIT_DATA

  /** Sections mapped onto [outerTab], in schema order — rendered as pill sub-tabs beneath it.
   * Only ever non-empty for [VisitFormOuterTab.VISIT_DATA] this pass — see [outerTabFor]. */
  fun subSections(outerTab: VisitFormOuterTab): List<String> = sections().filter { outerTabFor(it) == outerTab }

  /** Options for a select/radio/multiselect field. Simpler than
   * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.optionsFor]: as of this schema
   * version, neither ANC_VISIT nor INFANT_VISIT declares a `lookup_category_code` or a geography
   * field, so every choice field's options come from its own inline `options` array. Revisit if a
   * future republish adds either. */
  fun optionsFor(field: FormFieldSchema): List<FormFieldOption> =
    field.options?.sortedBy(FormFieldOption::sortOrder).orEmpty()

  private companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_VISIT_ID = "visitId"
    const val NAV_ARG_LABEL = "label"
    const val RCH_NUMBER_QUESTION_CODE = "rch_number"
    const val VISIT_TYPE_QUESTION_CODE = "visit_type"
    const val HAVE_YOU_BEEN_ABLE_TO_MEET_QUESTION_CODE = "have_you_been_able_to_meet_the_beneficiary_for_the_visit"
    const val IF_NO_MENTION_REASONS_QUESTION_CODE = "if_no_mention_reasons"
    const val VALUE_NO = "no"
  }
}
