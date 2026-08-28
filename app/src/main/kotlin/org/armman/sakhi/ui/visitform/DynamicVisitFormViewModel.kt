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
import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliveryToNeonatalPrefill
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormCrossFieldValidator
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormHiddenFieldReset
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.FormVisibilityEvaluator
import org.armman.sakhi.data.forms.FormsRepository
import org.armman.sakhi.data.forms.VisitCodeFormResolver
import org.armman.sakhi.data.schedule.VisitScheduleRepository
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
import org.armman.sakhi.data.visitform.AncRiskAnswerMapper
import org.armman.sakhi.data.visitform.AncRiskRegistrationResolver
import org.armman.sakhi.data.visitform.InfantRiskAnswerMapper
import org.armman.sakhi.data.visitform.RiskConditionFieldMap
import org.armman.sakhi.data.rules.GoRulesRiskAdapter
import org.armman.sakhi.data.rules.RiskConditionIds
import org.armman.sakhi.data.rules.RiskGrade
import org.armman.sakhi.data.referral.FacilityType
import org.armman.sakhi.data.referral.ReferralCapture
import org.armman.sakhi.data.referral.ReferralType
import org.armman.sakhi.data.rules.RiskGradingResult
import java.time.LocalDate
import javax.inject.Inject

/** ANC_VISIT (mother) / INFANT_VISIT (infant) form codes the active-version endpoint recognises. */
// internal, not private: DynamicVisitFormScreen.kt needs FORM_CODE_MOTHER to gate the Summary
// tab (INFANT_VISIT has no risk model yet - see that check's own comment).
internal const val FORM_CODE_MOTHER = "ANC_VISIT"
internal const val FORM_CODE_INFANT = "INFANT_VISIT"

/** Bug fix (2026-08-22): the backend's live `/forms/visit-code-form-map` now resolves
 * `VisitCodeType.INC`/`INC_HR` to `"INC_VISIT"` and `CCV`/`CCV_HR` to `"CCV_VISIT"` as genuinely
 * distinct form codes (see [org.armman.sakhi.data.forms.VisitCodeFormResolver]) rather than the
 * old `"INFANT_VISIT"` alias. Their schema content is still a direct copy of INFANT_VISIT's
 * (CR-033, `docs/test-cases/visit-form.md`), so every INFANT_VISIT-only behaviour below
 * (child-registration prefill, computed-field evaluation, risk assessment, submission,
 * Summary tab) needs to fire for all three codes until real INC/CCV-specific schemas ship. */
internal val FORM_CODES_INFANT_FAMILY = setOf(FORM_CODE_INFANT, "INC_VISIT", "CCV_VISIT")

/** CR-042: the delivery-session PP1/NN1/NN2 form codes — see [DynamicVisitFormViewModel.onFinish]
 * for their real submission contract. Internal, not private, for the same reason as
 * [FORM_CODE_MOTHER]/[FORM_CODE_INFANT] (test/screen visibility). */
internal const val FORM_CODE_POSTPARTUM = "POSTPARTUM_VISIT"
internal const val FORM_CODE_NEONATAL = "NEONATAL_VISIT"

/** Form codes [DynamicVisitFormViewModel.onFinish] actually submits — every schema-driven visit
 * form has a real submission contract now that [FORM_CODE_INFANT] has joined the other three. */
private val SUBMITTABLE_FORM_CODES = setOf(FORM_CODE_MOTHER, FORM_CODE_POSTPARTUM, FORM_CODE_NEONATAL) + FORM_CODES_INFANT_FAMILY

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
  /** True while [DynamicVisitFormViewModel.onFinish]'s submit call is in flight (ANC_VISIT/
   * POSTPARTUM_VISIT/NEONATAL_VISIT only — see that function's doc). Drives the Submit button's
   * loading state and guards against a double-tap firing two submissions. */
  val isSubmitting: Boolean = false,
  /** CR-Referral-01 Pass 4 (2026-08-27, per PRD's "Visit completed — Risk assessment — Referral
   * decision" tree): true once [onFinish] computes the final on-device [RiskGradingResult] and
   * finds at least one condition with `isReferralTrigger == true` — [DynamicVisitFormScreen]
   * swaps the whole screen to the referral capture step instead of the normal tab body when this
   * is true. Deliberately driven by the ON-DEVICE result, not the server's own
   * `POST /risk-assessments` response (which only exists after the visit has actually reached the
   * server) — that's what lets this step appear identically whether she's online or offline at
   * submit time, per SRS FR-S-4.1's offline-first mandate. Reset to false only by
   * [cancelReferralCapture]; a second [onFinish] call while this is already true skips the trigger
   * check and proceeds straight to the real submission. */
  val showReferralCaptureStep: Boolean = false,
  /** Standalone hand-built fields for the referral capture step (bharath, 2026-08-08; moved out of
   * a persistent "Referral" tab into a conditional post-visit step in Pass 4, 2026-08-27 — see
   * [showReferralCaptureStep]'s doc for why) - NOT part of the ANC_VISIT schema's own "Referrals"
   * section (that's a different set of questions, already rendered as a Visit Data sub-tab).
   * Bundled into a [org.armman.sakhi.data.referral.ReferralCapture] by [referralCaptureOrNull] and
   * passed to [VisitFormDraftRepository.submitDraft] on the same real Submit as the rest of the
   * form. Still only ever results in an actual referral once the server's risk-assessment response
   * *also* confirms a trigger (the authoritative check — see
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.maybeCreateReferral]'s doc for
   * why the on-device result that gates this step isn't treated as good enough on its own). */
  val referralDate: LocalDate? = null,
  /** Free-text facility name — CR-Referral-01 replaced the old hardcoded-placeholder-dropdown
   * capture with this + [referralFacilityType] (the backend's actual `POST /referrals` shape has
   * no facility directory to select from; confirmed 2026-08-27). */
  val referralFacilityName: String? = null,
  val referralFacilityType: FacilityType? = null,
  val referralType: ReferralType? = null,
  /** Offline high-risk rule evaluation (CR — real-time field highlighting), evaluated live as
   * relevant fields are filled — see [DynamicVisitFormViewModel.recheckGoRulesRisk]'s doc. Null
   * until the first successful evaluation (no cached rule pack yet, or nothing relevant answered
   * yet); NOT cleared back to null on a later failed re-evaluation, same one-way-forward
   * philosophy as [criticalCondition] — a Sakhi who has already seen a highlight shouldn't see it
   * silently vanish because a transient re-evaluation had no cached pack that instant. */
  val goRulesRiskResult: RiskGradingResult? = null,
  /** `question_code` -> the worst (highest [RiskConditionFinding.gradeRank]) [RiskGrade] to
   * visually highlight right now, derived from [goRulesRiskResult] via [RiskConditionFieldMap] —
   * every condition graded MILD or worse whose code has a confirmed field mapping. A field mapped
   * from more than one condition (e.g. both HYPERTENSION and HYPOTENSION point at the systolic BP
   * field) shows whichever grade is clinically worse, never overwritten by whichever condition
   * happens to iterate last. See [RiskConditionFieldMap]'s doc for which conditions are
   * deliberately unmapped (and therefore never appear here even if graded high-risk). Consumed by
   * [org.armman.sakhi.ui.forms.DynamicFormField]'s `riskGrade` param for the field-level
   * highlight + [org.armman.sakhi.ui.components.RiskBadge] chip (Option B, 2026-08-24 design
   * decision — see the published mockup discussion; not yet reflected in the Figma source). */
  val highlightedFieldGrades: Map<String, RiskGrade> = emptyMap(),
)

/** One-shot events the screen reacts to (navigation/toast), mirroring the retired hand-coded
 * ViewModel's [org.armman.sakhi.ui.visitform.VisitFormEvent] one-for-one. */
sealed interface DynamicVisitFormEvent {
  /** Confirmed exit, or a critical-condition dismissal (FR-S-4.4 Option B) — leave the flow. No
   * partial-save exists for this fetch+render pass, same as before. */
  data object ExitForm : DynamicVisitFormEvent

  /** Only reachable now for a form code outside [SUBMITTABLE_FORM_CODES] — see [onFinish]'s
   * guard. All four visit form codes (ANC_VISIT/POSTPARTUM_VISIT/NEONATAL_VISIT/INFANT_VISIT)
   * share the same real Submit button and submission contract as of CR-Referral-01. The referral
   * capture step (Pass 4, 2026-08-27) is no longer a tab of its own — it's a conditional
   * screen [onFinish] itself triggers, so it has no separate ComingSoon path either. */
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
 * Drives the schema-driven ANC_VISIT/INFANT_VISIT/POSTPARTUM_VISIT/NEONATAL_VISIT Visit Form — the
 * dynamic replacement for the retired hand-coded [org.armman.sakhi.ui.visitform.VisitFormViewModel]/
 * `VisitDataState` stepper, and (since CR-042) also the screen [org.armman.sakhi.ui.delivery
 * .DeliverySessionScreen]/[org.armman.sakhi.ui.delivery.DeliveryChildRegistrationScreen] hand off
 * into for a delivery session's PP1 and same-session NN visit — those are already-generated
 * [org.armman.sakhi.data.schedule.VisitScheduleEntity] rows by the time either screen navigates
 * here, so nothing about opening them differs from opening any other scheduled visit. Fetches the
 * active schema for whichever form the schedule row's [org.armman.sakhi.data.schedule.VisitCodeType]
 * (or, failing that, the beneficiary's own type) calls for ([FormsRepository], same
 * auto-refresh-on-load behaviour as the registration flow) and renders it through the same generic
 * [org.armman.sakhi.ui.forms.DynamicFormField] the Mother/Child registration screens use.
 *
 * All four form codes now have a real `POST /visits` + `POST /forms/{code}/submissions`
 * submission contract — see [onFinish]. [FORM_CODE_MOTHER] had it first; [FORM_CODE_POSTPARTUM]/
 * [FORM_CODE_NEONATAL] gained it via CR-042, and [FORM_CODE_INFANT] gained it after starting as a
 * fetch + render only pass (bharath, 2026-08-07). What IS preserved from the retired flow, for the
 * mother only, is FR-S-4.4's critical-condition safety check (see
 * [VisitCriticalConditionEvaluator]) and the carried-forward [org.armman.sakhi.data.visitform
 * .VisitContext] prefill of `rch_number`/`lmp` (mother only — the context shape has no infant
 * fields to prefill from). [FORM_CODE_NEONATAL] gets its own, unrelated prefill — see
 * [prefillFromDeliveryVisit].
 */
@HiltViewModel
class DynamicVisitFormViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val visitFormRepository: VisitFormRepository,
  private val beneficiaryProfileRepository: BeneficiaryProfileRepository,
  private val visitFormDraftRepository: VisitFormDraftRepository,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val visitCodeFormResolver: VisitCodeFormResolver,
  private val formAuditRepository: FormAuditRepository,
  private val deliverySessionRepository: DeliverySessionRepository,
  private val deliveryFormDraftRepository: DeliveryFormDraftRepository,
  private val goRulesRiskAdapter: GoRulesRiskAdapter,
  private val ancRiskRegistrationResolver: AncRiskRegistrationResolver,
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
      val beneficiaryType = try {
        beneficiaryProfileRepository.getBeneficiary(beneficiaryId).type
      } catch (e: NoSuchElementException) {
        Log.w(TAG, "DynamicVisitFormViewModel.load($beneficiaryId): getBeneficiary threw NoSuchElementException")
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      // CR-033/CR-034: prefer the schedule row's VisitCodeType (covers PP/NN/INC/CCV/HR, not just
      // ANC/Infant) — see VisitCodeFormResolver's own doc for what's still a backend placeholder
      // for INC/CCV/HR specifically. Falls back to the old beneficiary-type-only switch only if
      // the schedule row itself can't be found, which should not happen in practice (every visit
      // form is opened from a schedule entry) but must not crash load() if it somehow is.
      val schedule = visitScheduleRepository.getByLocalScheduleUuid(visitId)
      val formCode = schedule?.let { visitCodeFormResolver.resolve(it.visitType) }
        ?: when (beneficiaryType) {
          BeneficiaryType.MOTHER -> FORM_CODE_MOTHER
          BeneficiaryType.INFANT -> FORM_CODE_INFANT
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
            "label='${field.label}' computedFrom='${field.computedFrom}' visibleWhen=${field.visibleWhen}",
        )
      }
      _uiState.update { it.copy(isLoading = false, formCode = formCode, version = version) }
      // CR-035: logged only once the form has genuinely loaded (version confirmed non-null) —
      // NOT on any of the earlier blank-id/beneficiary-not-found/version-null failure branches
      // above, each of which returns before reaching here. Every open is logged, not just the
      // first — an accurate trail is the point.
      formAuditRepository.recordOpened(visitId, formCode)
      prefillDefaultVisitDate()
      if (formCode == FORM_CODE_MOTHER) prefillFromVisitContext()
      if (formCode in FORM_CODES_INFANT_FAMILY) {
        prefillFromChildRegistration()
        prefillVisitTypeLabel()
      }
      if (formCode == FORM_CODE_NEONATAL) prefillFromDeliveryVisit()
      // Bug fix (2026-08-21): PP1 declares no height field of its own (confirmed against the live
      // postpartum-visit.json schema) but its "Current BMI" computedFrom field needs one — see
      // prefillHeightForPostpartum()'s own doc for why this is a narrow height-only prefill rather
      // than reusing prefillFromVisitContext() wholesale.
      if (formCode == FORM_CODE_POSTPARTUM) prefillHeightForPostpartum()
      recomputeDerivedFields()
      recheckCriticalCondition()
    }
  }

  /** Auto-fills the visit-date field with today's date on load, for every visit-form family
   * (ANC_VISIT/INFANT_VISIT via [VisitFormQuestionCodes.DATE_OF_VISIT], POSTPARTUM_VISIT/
   * NEONATAL_VISIT via [VisitFormQuestionCodes.ACTUAL_VISIT_DATE] per the spec's "Actual visit
   * date... Should automatically select today's date" row) — the Sakhi is almost always filling
   * this in live, during the visit (bharath, 2026-08-07). Skips a code already answered under
   * EITHER spelling, so a value restored from a draft/backend answer isn't overwritten, and only
   * ever writes codes the ACTIVE schema actually declares — mirrors
   * [org.armman.sakhi.data.forms.RegistrationDatePrefill.apply]'s exact fail-safe pattern, which
   * is what makes this safe to widen to both spellings without knowing which one any given form
   * type uses. */
  private fun prefillDefaultVisitDate() {
    _uiState.update { state ->
      if (VisitFormQuestionCodes.VISIT_DATE_QUESTION_CODES.any { !state.answers.valueOf(it).isNullOrBlank() }) {
        return@update state
      }
      val value = visitDate.toString()
      val targetCodes = state.version?.schemaJson.orEmpty()
        .map { it.questionCode }
        .filter { it in VisitFormQuestionCodes.VISIT_DATE_QUESTION_CODES }
        .distinct()
        .ifEmpty { listOf(VisitFormQuestionCodes.DATE_OF_VISIT) }
      state.copy(answers = targetCodes.fold(state.answers) { acc, code -> acc.withSingleValue(code, value) })
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
      // HRV1/HRV2/...). Bug fix (2026-08-21): this used to read context.visitTypeLabel, but
      // VisitFormRepository is still StaticVisitFormRepository (a stub) whose visitTypeLabel is
      // hardcoded to "ANC1" for any beneficiary/visit not in its small fixture table — so a real
      // ANC2 (or later) visit silently showed "ANC1" here. [visitLabel] (this ViewModel's own nav
      // arg, sourced end-to-end from the schedule row's real visitCode — see
      // ProfileVisitMapper.label / AppNavHost's visit.label — and already proven correct, since
      // it's what renders the screen's own "ANC2 Form" title) is the real value; use it instead
      // once VisitFormRepository has a genuine backend implementation, this can be revisited to
      // prefer context.visitTypeLabel if that ever needs to differ from the schedule's own label.
      if (answers.valueOf(VISIT_TYPE_QUESTION_CODE).isNullOrBlank() && visitLabel.isNotBlank()) {
        answers = answers.withSingleValue(VISIT_TYPE_QUESTION_CODE, visitLabel)
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

  /**
   * Bug fix (found via manual QA on spec row 28 "Current BMI", 2026-08-21): POSTPARTUM_VISIT's own
   * schema declares no height field at all — a woman's height doesn't change postpartum, so PP1
   * relies on whatever ANC already captured, the same [org.armman.sakhi.data.visitform
   * .VisitContext.heightCm] carried-forward value [prefillFromVisitContext] uses for ANC visits
   * after the first. This is a DELIBERATELY narrower copy of that function rather than a call to
   * it directly: [prefillFromVisitContext] also seeds `rch_number`/`lmp`/the visit-type label,
   * none of which exist on PP1's schema, and pushing them into PP1's answers risked either being
   * silently dropped or, worse, rejected by backend payload validation for fields PP1 never
   * declared. Best-effort, same degrade-gracefully contract as [prefillFromVisitContext]: a
   * context fetch failure here doesn't block the (already-loaded) PP1 form.
   */
  private suspend fun prefillHeightForPostpartum() {
    val context = try {
      visitFormRepository.getVisitContext(beneficiaryId, visitId)
    } catch (e: NoSuchElementException) {
      return
    }
    val heightFromContext = context.heightCm ?: return
    _uiState.update { state ->
      if (state.answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM).isNullOrBlank()) {
        state.copy(
          answers = state.answers.withSingleValue(VisitFormQuestionCodes.HEIGHT_CM, heightFromContext.toString()),
          heightLockedFromContext = true,
        )
      } else {
        state
      }
    }
  }

  /**
   * CR-042: `birth_weight_kg`/`term_of_delivery` on `NEONATAL_VISIT` (NN1 or NN2 — this fires for
   * either, see [DeliveryToNeonatalPrefill]'s own doc) are labelled "(from the Delivery form...)"
   * on the live schema — the Sakhi already gave both once on `DELIVERY_VISIT`, so this seeds them
   * from that submission's own answers rather than asking her to retype them.
   *
   * Deliberately resolves the session via [DeliverySessionRepository.getMostRecentForBeneficiary],
   * not [DeliverySessionRepository.getActiveForBeneficiary] — a same-session NN1 opens while the
   * session is still active, but a tracker NN2 can open long after the session already reached
   * [org.armman.sakhi.data.delivery.DeliverySessionStep.DONE], and it needs this prefill just as
   * much.
   *
   * Best-effort throughout, same degrade-gracefully contract [prefillFromVisitContext] has: no
   * delivery session ever recorded for this beneficiary, no [org.armman.sakhi.data.delivery
   * .DeliverySessionEntity.deliverySubmissionLocalUuid] on it, or no draft payload found for that
   * uuid (shouldn't happen — see [DeliveryFormDraftRepository.getAnswers]'s own doc) all just mean
   * both fields stay blank and Sakhi-fillable rather than blocking the form.
   */
  private suspend fun prefillFromDeliveryVisit() {
    val session = deliverySessionRepository.getMostRecentForBeneficiary(beneficiaryId) ?: return
    val deliverySubmissionLocalUuid = session.deliverySubmissionLocalUuid ?: return
    val deliveryAnswers = deliveryFormDraftRepository.getAnswers(deliverySubmissionLocalUuid) ?: return
    val prefill = DeliveryToNeonatalPrefill.singleValueAnswersFor(deliveryAnswers)
    if (prefill.isEmpty()) return
    _uiState.update { state ->
      var answers = state.answers
      prefill.forEach { (questionCode, value) ->
        if (answers.valueOf(questionCode).isNullOrBlank()) {
          answers = answers.withSingleValue(questionCode, value)
        }
      }
      state.copy(answers = answers)
    }
  }

  /**
   * Bug fix (2026-08-22): INFANT_VISIT/INC_VISIT/CCV_VISIT's own "visit_type" field (Tests
   * section, same spec row 2 concept ANC/HRV forms already get via [prefillFromVisitContext])
   * was never populated for the infant family — that prefill only ever ran for [FORM_CODE_MOTHER].
   * A narrow, standalone prefill rather than reusing [prefillFromVisitContext] wholesale, same
   * reasoning as [prefillHeightForPostpartum]: that function also seeds `rch_number`/`lmp`, which
   * don't exist on the infant schema. [visitLabel] is the same nav-arg source
   * [prefillFromVisitContext] already uses (see its own doc for why, over
   * [org.armman.sakhi.data.visitform.VisitContext.visitTypeLabel]).
   */
  private fun prefillVisitTypeLabel() {
    _uiState.update { state ->
      if (state.answers.valueOf(VISIT_TYPE_QUESTION_CODE).isNullOrBlank() && visitLabel.isNotBlank()) {
        state.copy(answers = state.answers.withSingleValue(VISIT_TYPE_QUESTION_CODE, visitLabel))
      } else {
        state
      }
    }
  }

  /**
   * Prefills INFANT_VISIT's ("Tests" section, INC1/INC2/CCV — [FORM_CODE_INFANT]) identity fields
   * from the child's own CHILD_REGISTRATION submission: `date_of_birth` (so
   * [InfantVisitFormComputedFieldEvaluator] can derive `age_in_months` from it),
   * `name_of_the_child`, `sex_of_the_infant`, `birth_weight_in_kg`,
   * `length_of_the_baby_at_the_time_of_birth_in_cm`, and `premature_child` (derived from
   * CHILD_REGISTRATION's `term_of_delivery` — pre_term -> preterm_lt_37_weeks, full_term/post_term
   * -> full_term_gte_37_weeks, anything else/unrecognised -> dont_know).
   *
   * `rch_number` is deliberately left unmapped: CHILD_REGISTRATION's live schema has no literal
   * "rch_number" field (confirmed against the real `GET /forms/CHILD_REGISTRATION/active-version`
   * payload), and product/user has confirmed (2026-08-20) it stays a manual Sakhi entry for now —
   * no source to wire it from.
   *
   * Best-effort, same degrade-gracefully contract as [prefillFromVisitContext]/
   * [prefillFromDeliveryVisit]: no local CHILD_REGISTRATION draft/submission for this beneficiary
   * (e.g. a remote-only enrolment with nothing synced to this device) just leaves every field
   * blank and Sakhi-fillable rather than blocking the form. Only ever fills a currently-blank
   * answer — never overwrites something already on the draft (a resumed in-progress visit, or a
   * value the Sakhi already typed).
   */
  private suspend fun prefillFromChildRegistration() {
    val regAnswers = beneficiaryProfileRepository.getChildRegistrationAnswers(beneficiaryId)
    // Temporary diagnostic for the "Age in months not auto-filled on INC1" report — confirms
    // whether the local CHILD_REGISTRATION lookup found anything at all for this beneficiaryId
    // before we even try to read date_of_birth_of_infant out of it.
    Log.d(
      TAG,
      "DynamicVisitFormViewModel.prefillFromChildRegistration($beneficiaryId): " +
        "getChildRegistrationAnswers returned ${if (regAnswers == null) "null" else "answers"}",
    )
    if (regAnswers == null) return
    val prefill = mutableMapOf<String, String>()

    regAnswers.valueOf(ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT)?.let {
      prefill[DATE_OF_BIRTH_QUESTION_CODE] = it
    }
    regAnswers.valueOf(ChildRegistrationQuestionCodes.NAME_OF_THE_CHILD)?.let {
      prefill[NAME_OF_CHILD_QUESTION_CODE] = it
    }
    regAnswers.valueOf(ChildRegistrationQuestionCodes.SEX_OF_CHILD)?.let { sex ->
      SEX_VALUE_MAP[sex]?.let { prefill[SEX_OF_INFANT_QUESTION_CODE] = it }
    }
    regAnswers.valueOf(ChildRegistrationQuestionCodes.CHILD_WEIGHT_AT_BIRTH_KG)?.let {
      prefill[BIRTH_WEIGHT_KG_QUESTION_CODE] = it
    }
    regAnswers.valueOf(ChildRegistrationQuestionCodes.CHILD_LENGTH_AT_BIRTH_CM)?.let {
      prefill[BIRTH_LENGTH_CM_QUESTION_CODE] = it
    }
    regAnswers.valueOf(DeliveryQuestionCodes.TERM_OF_DELIVERY)?.let { term ->
      prefill[PREMATURE_CHILD_QUESTION_CODE] = PREMATURE_VALUE_MAP[term] ?: VALUE_DONT_KNOW
    }

    // Temporary diagnostic — exactly which of the 6 possible fields resolved to a usable value
    // this time, most importantly date_of_birth (the one age_in_months depends on).
    Log.d(TAG, "DynamicVisitFormViewModel.prefillFromChildRegistration($beneficiaryId): prefill map = $prefill")
    if (prefill.isEmpty()) return
    _uiState.update { state ->
      var answers = state.answers
      prefill.forEach { (questionCode, value) ->
        if (answers.valueOf(questionCode).isNullOrBlank()) {
          answers = answers.withSingleValue(questionCode, value)
        }
      }
      state.copy(answers = answers)
    }
  }

  // --- Referral tab (standalone form, not schema-driven - see DynamicVisitFormUiState's doc) ---

  fun setReferralDate(date: LocalDate?) {
    _uiState.update { it.copy(referralDate = date) }
  }

  fun setReferralFacilityName(name: String?) {
    _uiState.update { it.copy(referralFacilityName = name) }
  }

  fun setReferralFacilityType(type: FacilityType?) {
    _uiState.update { it.copy(referralFacilityType = type) }
  }

  fun setReferralType(type: ReferralType?) {
    _uiState.update { it.copy(referralType = type) }
  }

  /** CR-Referral-01 Pass 4: back out of the referral capture step to keep editing the visit form
   * — does NOT clear whatever fields she'd already filled in, so returning to this step later
   * (via another Submit tap) picks up where she left off. */
  fun cancelReferralCapture() {
    _uiState.update { it.copy(showReferralCaptureStep = false) }
  }

  /** CR-Referral-01 Pass 4: "no referral needed" — her judgement call per the PRD's decision
   * tree ("Sakhi uses her best judgement"), even though the on-device evaluation flagged a
   * trigger. Clears any partially-filled fields so [referralCaptureOrNull] returns null, then
   * finishes the same real submit [onFinish] already performs (showReferralCaptureStep is already
   * true at this point, so that second call skips straight past the trigger check). */
  fun skipReferralCapture() {
    _uiState.update {
      it.copy(
        referralDate = null,
        referralFacilityName = null,
        referralFacilityType = null,
        referralType = null,
      )
    }
    onFinish()
  }

  /** CR-Referral-01: bundles the Referral tab's captured fields for [onFinish] to pass down to
   * [VisitFormDraftRepository.submitDraft] — null unless the Sakhi has selected both a referral
   * type and a facility, since a create-referral call with a missing required field would just
   * be rejected server-side; incomplete capture is treated as "she hasn't filled this in yet",
   * not submitted partially. */
  private fun referralCaptureOrNull(): ReferralCapture? {
    val state = _uiState.value
    val type = state.referralType ?: return null
    val facilityName = state.referralFacilityName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val facilityType = state.referralFacilityType ?: return null
    // referralDate is required by POST /referrals (backend-confirmed 2026-08-27) — an
    // incompletely-filled Referral tab (this field still null) means no referral is captured at
    // all, same as any other missing required field above.
    val referralDate = state.referralDate ?: return null
    return ReferralCapture(
      referralType = type,
      facilityName = facilityName,
      facilityType = facilityType,
      referralDate = referralDate,
    )
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
    if (_uiState.value.formCode in FORM_CODES_INFANT_FAMILY) {
      InfantVisitRiskAssessment.buildKnownRisks(_uiState.value.answers)
    } else {
      emptyList()
    }

  /** Worst-of [infantKnownRisks] - LOW when the list is empty (no risk flagged this visit). */
  fun infantOverallRiskLevel(): RiskLevel = InfantVisitRiskAssessment.overall(infantKnownRisks().map { it.riskLevel })

  /**
   * Resolved review data for the Summary tab's plain "filled fields" review — POSTPARTUM_VISIT/
   * NEONATAL_VISIT, and any other form code with no bespoke Summary tab of its own (ANC_VISIT/
   * INFANT_VISIT keep their risk-banner Summary tabs above instead). Every answered,
   * currently-visible field grouped by schema section into its own card, in the order each section
   * first appears, coded values mapped to display labels. Mirrors [org.armman.sakhi.ui.delivery
   * .DeliverySessionViewModel.buildSummary] — same shape, same empty-sections-dropped rule — but
   * also folds in MEDIA fields (this form has some; DELIVERY_VISIT doesn't, per that function's own
   * doc), using [mediaCompletedLabel] the same way [imageCapturedLabel] covers IMAGE fields. Not
   * suspend, unlike that Delivery twin: [optionsFor] here never needs a lookup-repository round
   * trip (see that function's own doc).
   */
  fun buildFieldSummary(imageCapturedLabel: String, mediaCompletedLabel: String): List<SummarySection> {
    val fields = visibleFields()
    val sectionTitles = fields.map(::sectionOf).distinct()
    return sectionTitles.map { title ->
      SummarySection(
        title = title,
        rows = fields.filter { sectionOf(it) == title }
          .mapNotNull { summaryRowFor(it, imageCapturedLabel, mediaCompletedLabel) },
      )
    }.filter { it.rows.isNotEmpty() }
  }

  private fun summaryRowFor(
    field: FormFieldSchema,
    imageCapturedLabel: String,
    mediaCompletedLabel: String,
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
      FormFieldInputType.IMAGE -> imageCapturedLabel.takeIf { field.questionCode in state.capturedImages }
      FormFieldInputType.MEDIA -> mediaCompletedLabel.takeIf { field.questionCode in state.mediaCompleted }
      FormFieldInputType.SELECT, FormFieldInputType.RADIO -> {
        val code = state.answers.valueOf(field.questionCode)
        if (code.isNullOrBlank()) null else optionsFor(field).firstOrNull { it.valueCode == code }?.label ?: code
      }
      // text / text_geo / number / date / computed read-only — the stored value is display-ready.
      else -> state.answers.valueOf(field.questionCode)
    }
    return value?.takeIf { it.isNotBlank() }?.let { SummaryRow(label = field.label, value = it) }
  }

  fun setAnswer(questionCode: String, value: String?) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withSingleValue(questionCode, value)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
    recomputeDerivedFields()
    recheckCriticalCondition()
    recheckGoRulesRisk()
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
    recheckGoRulesRisk()
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
        in FORM_CODES_INFANT_FAMILY -> InfantVisitFormComputedFieldEvaluator.compute(computedFrom, answers, visitDate)
        FORM_CODE_POSTPARTUM -> VisitFormComputedFieldEvaluator.compute(
          computedFrom,
          answers,
          visitDate,
          state.registrationWeightKg,
        )
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

  /**
   * Grades [answers] against whichever GoRules risk pack [formCode] maps to (ANC_VISIT ->
   * mother/ANC pack; NEONATAL_VISIT/INFANT_VISIT/INC_VISIT/CCV_VISIT -> the shared infant pack
   * under the matching [InfantRiskAnswerMapper.InfantFormFamily]) — null for any other form code
   * (e.g. POSTPARTUM_VISIT, which has no risk-grading pack at all, same as
   * [org.armman.sakhi.data.rules.GoRulesRiskAdapter] never having a PP variant to call).
   *
   * Shared by [recheckGoRulesRisk] (fired live, on every answer change, for real-time field
   * highlighting) and [onFinish] (fired once more at submit time, against the complete final
   * answer set, for the visit-level result attached to the local submission record — Phase 5).
   * Deliberately NOT memoized/cached between those two call sites: the submit-time call is meant
   * to be the authoritative one, re-evaluated fresh rather than trusting whatever the last live
   * per-field recompute happened to produce (which could in principle be stale if a prior
   * evaluation silently no-opped for lack of a cached rule pack at that instant).
   */
  private suspend fun evaluateGoRulesRisk(formCode: String, answers: FormAnswers): RiskGradingResult? = when {
    formCode == FORM_CODE_MOTHER -> {
      val input = AncRiskAnswerMapper.toRuleInput(answers)
      ancRiskRegistrationResolver.addRegistrationFields(input, beneficiaryId)
      goRulesRiskAdapter.gradeAncRisk(input)
    }
    formCode == FORM_CODE_NEONATAL -> goRulesRiskAdapter.gradeInfantRisk(
      InfantRiskAnswerMapper.toRuleInput(answers, InfantRiskAnswerMapper.InfantFormFamily.NEONATAL),
    )
    formCode == FORM_CODE_INFANT -> goRulesRiskAdapter.gradeInfantRisk(
      InfantRiskAnswerMapper.toRuleInput(answers, InfantRiskAnswerMapper.InfantFormFamily.INFANT),
    )
    formCode == "INC_VISIT" -> goRulesRiskAdapter.gradeInfantRisk(
      InfantRiskAnswerMapper.toRuleInput(answers, InfantRiskAnswerMapper.InfantFormFamily.INC),
    )
    formCode == "CCV_VISIT" -> goRulesRiskAdapter.gradeInfantRisk(
      InfantRiskAnswerMapper.toRuleInput(answers, InfantRiskAnswerMapper.InfantFormFamily.CCV),
    )
    else -> null
  }

  /**
   * Offline high-risk rule evaluation (CR — real-time field highlighting), fired on every answer
   * change alongside [recheckCriticalCondition] — the whole point of this CR over the pre-existing
   * Summary-tab-only [testsFindings]/[infantKnownRisks] is that grading happens live as each field
   * is filled, not only when the Sakhi reaches Summary.
   *
   * Launched fire-and-forget in [viewModelScope]: [GoRulesRiskAdapter] never throws (evaluate
   * failures return null, same "no cached rule -> nothing changes" contract every other
   * offline-first read in this app follows), so there's nothing here to catch or surface as an
   * error — a failed/unavailable evaluation just means [DynamicVisitFormUiState.goRulesRiskResult]
   * doesn't update this time, exactly like a stale/never-fetched rule pack for scheduling falls
   * back silently rather than blocking the Sakhi.
   *
   * NEONATAL_VISIT is intentionally handled here even though it's not in [FORM_CODES_INFANT_FAMILY]
   * (that set is for the *shared-schema* infant family; NEONATAL_VISIT has its own distinct
   * schema) — [InfantRiskAnswerMapper.InfantFormFamily] already models this exact distinction.
   */
  private fun recheckGoRulesRisk() {
    val state = _uiState.value
    val formCode = state.formCode ?: return
    viewModelScope.launch {
      val result = evaluateGoRulesRisk(formCode, state.answers) ?: return@launch

      val conditionMap = if (formCode == FORM_CODE_MOTHER) RiskConditionIds.ANC else RiskConditionIds.INFANT
      val fieldMap = if (formCode == FORM_CODE_MOTHER) RiskConditionFieldMap.ANC else RiskConditionFieldMap.INFANT
      val idToCode = conditionMap.entries.associate { (code, id) -> id to code }
      // Worst-grade-wins per field: a field mapped from >1 condition (e.g. BP systolic under both
      // HYPERTENSION and HYPOTENSION) must not have its highlight silently downgraded depending on
      // map/list iteration order.
      val highlighted = mutableMapOf<String, RiskGrade>()
      val highlightedRank = mutableMapOf<String, Int>()
      result.conditions
        .filter { it.grade != RiskGrade.NORMAL && it.grade != RiskGrade.UNKNOWN }
        .forEach { finding ->
          val code = idToCode[finding.riskConditionId] ?: return@forEach
          fieldMap[code].orEmpty().forEach { questionCode ->
            val currentRank = highlightedRank[questionCode]
            if (currentRank == null || finding.gradeRank > currentRank) {
              highlightedRank[questionCode] = finding.gradeRank
              highlighted[questionCode] = finding.grade
            }
          }
        }

      _uiState.update { it.copy(goRulesRiskResult = result, highlightedFieldGrades = highlighted) }
    }
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
   * Real submit for [SUBMITTABLE_FORM_CODES] — [FORM_CODE_MOTHER] (`POST /visits` then
   * `POST /forms/ANC_VISIT/submissions`), and, since CR-042, [FORM_CODE_POSTPARTUM]/
   * [FORM_CODE_NEONATAL] (PP1/NN1/NN2, the same two-call sequence under their own resolved
   * formCode), and now also [FORM_CODE_INFANT] — via [VisitFormDraftRepository.submitDraft]
   * (CR-026b), which saves locally first, then attempts the real submission immediately while
   * online (identical outcome to the pre-CR-026b direct coordinator call), or queues it for the
   * next manual Data Upload while offline. Once a PP1/NN submission actually succeeds,
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.submit] advances the
   * beneficiary's [org.armman.sakhi.data.delivery.DeliverySessionEntity] on its own (CR-042 step
   * advancement) — this ViewModel does not need to know that happened.
   *
   * Phase 5 (CR — offline high-risk rule evaluation): also computes the final, authoritative
   * [org.armman.sakhi.data.rules.RiskGradingResult] via [evaluateGoRulesRisk] and passes it to
   * [VisitFormDraftRepository.submitDraft] so it's persisted with the local draft — works fully
   * offline, independent of whether the network submission below succeeds, fails, or queues. Not
   * yet forwarded to the backend itself (see [VisitFormDraftPayload.riskResult]'s doc).
   */
  fun onFinish() {
    val state = _uiState.value
    if (state.formCode !in SUBMITTABLE_FORM_CODES) {
      _events.trySend(DynamicVisitFormEvent.ComingSoon)
      return
    }
    if (state.isSubmitting) return
    val version = state.version ?: return

    viewModelScope.launch {
      val formCode = state.formCode.orEmpty()
      val finalAnswers = _uiState.value.answers
      // Phase 5: one last, authoritative grading against the complete final answers — see
      // evaluateGoRulesRisk's doc for why this isn't just state.goRulesRiskResult reused as-is.
      // Recomputed on every onFinish() call, including the second one below (after the referral
      // capture step) — cheap and deterministic against the same unchanged visit answers, so
      // there's no need to stash the first call's result across the two taps.
      val finalRiskResult = evaluateGoRulesRisk(formCode, finalAnswers)

      // CR-Referral-01 Pass 4: on-device trigger check, gating the referral capture step —
      // see DynamicVisitFormUiState.showReferralCaptureStep's doc for why this must be on-device
      // rather than waiting for the server's own risk-assessment call. Only fires once per visit
      // (state.showReferralCaptureStep already true means she's already been through this step
      // this submit attempt, whether she filled it in or skipped it).
      val referralTriggered = finalRiskResult?.conditions.orEmpty().any { it.isReferralTrigger }
      if (referralTriggered && !state.showReferralCaptureStep) {
        _uiState.update { it.copy(showReferralCaptureStep = true) }
        return@launch
      }

      _uiState.update { it.copy(isSubmitting = true) }
      val result = visitFormDraftRepository.submitDraft(
        localScheduleUuid = visitId,
        formCode = formCode,
        formVersionId = version.id,
        answers = finalAnswers,
        visitDate = visitDate,
        riskResult = finalRiskResult,
        referralCapture = referralCaptureOrNull(),
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

  /** The schema's own `validationJson` cross-field rules (`EXCLUSIVE_OPTION`, `LTE`, etc.)
   * currently violated by the answers so far — same helper every sibling dynamic form ViewModel
   * exposes (e.g. [org.armman.sakhi.ui.delivery.DeliverySessionViewModel.crossFieldViolations]).
   * Needed here specifically because [org.armman.sakhi.data.forms.FormMultiSelectExclusivity]'s
   * checkbox-greying only prevents a NEW conflicting tap — it can't undo a conflicting answer that
   * arrived some other way (an older draft, a backend-restored answer), so submission needs its
   * own guard against whatever the backend has declared for this form version. */
  fun crossFieldViolations(): List<FormCrossFieldRule> {
    val version = _uiState.value.version ?: return emptyList()
    return FormCrossFieldValidator.violatedRules(version.validationJson, _uiState.value.answers)
  }

  /** Whether every currently-visible required field has an answer, every `number` field with a
   * `numericRange` satisfies it, and no `validationJson` cross-field rule is violated — gates the
   * final Submit button. */
  fun isReadyToSubmit(): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(visibleFields()) && crossFieldViolations().isEmpty()
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

    // INFANT_VISIT's own "Tests" section question codes prefillFromChildRegistration() writes.
    // Confirmed 2026-08-20 against the live GET /forms/INFANT_VISIT/active-version payload.
    const val DATE_OF_BIRTH_QUESTION_CODE = "date_of_birth"
    const val NAME_OF_CHILD_QUESTION_CODE = "name_of_the_child"
    const val SEX_OF_INFANT_QUESTION_CODE = "sex_of_the_infant"
    const val BIRTH_WEIGHT_KG_QUESTION_CODE = "birth_weight_in_kg"
    const val BIRTH_LENGTH_CM_QUESTION_CODE = "length_of_the_baby_at_the_time_of_birth_in_cm"
    const val PREMATURE_CHILD_QUESTION_CODE = "premature_child"
    const val VALUE_DONT_KNOW = "dont_know"

    // CHILD_REGISTRATION's `sex_of_child` (male/female/intersex_other) -> INFANT_VISIT's
    // `sex_of_the_infant` (male/female/transgender) — not a verbatim copy, see
    // ChildRegistrationQuestionCodes.SEX_OF_CHILD's own doc.
    val SEX_VALUE_MAP = mapOf(
      "male" to "male",
      "female" to "female",
      "intersex_other" to "transgender",
    )

    // CHILD_REGISTRATION's `term_of_delivery` (pre_term/full_term/post_term) -> INFANT_VISIT's
    // `premature_child` (preterm_lt_37_weeks/full_term_gte_37_weeks/dont_know). post_term is still
    // >= 37 weeks, so it maps to full_term_gte_37_weeks, not a third bucket.
    val PREMATURE_VALUE_MAP = mapOf(
      "pre_term" to "preterm_lt_37_weeks",
      "full_term" to "full_term_gte_37_weeks",
      "post_term" to "full_term_gte_37_weeks",
    )
  }
}
