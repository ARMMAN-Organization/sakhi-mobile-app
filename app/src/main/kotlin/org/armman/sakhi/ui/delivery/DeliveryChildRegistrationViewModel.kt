package org.armman.sakhi.ui.delivery

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
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.childregistration.ChildNonRenderableQuestionCodes
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftRepository
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmitResult
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.armman.sakhi.data.delivery.DeliveryToChildRegistrationPrefill
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormComputedFieldEvaluator
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormCrossFieldValidator
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
import org.armman.sakhi.data.lookup.LookupRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** `question_code`s this screen never renders as a fillable input — the CR-042 twin of
 * [ChildNonRenderableQuestionCodes.ALL], with one addition: [ChildRegistrationQuestionCodes
 * .WHO_ARE_YOU_REGISTERING] is hidden here too because this flow hardcodes it to
 * [ChildRegistrationQuestionCodes.PATH_REGISTERED_MOTHER] (see [DeliveryToChildRegistrationPrefill]'s
 * doc) — every child registered through a Delivery Event Session is definitionally on that path,
 * so re-showing the radio would let the Sakhi "choose" an answer that isn't actually a choice, and
 * changing it away would break the [ChildRegistrationQuestionCodes.PATH_DIRECT] assumptions nothing
 * else in this screen accounts for.
 *
 * `mother_beneficiary_id` is deliberately NOT in this set — its correct value for this flow is
 * still an open backend question (see [DeliveryToChildRegistrationPrefill]'s doc), so it stays
 * visible and Sakhi-fillable exactly as it already is on the standalone registered-mother path
 * ([org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel.visibleFields]).
 */
private val HIDDEN_QUESTION_CODES: Set<String> =
  ChildNonRenderableQuestionCodes.ALL + ChildRegistrationQuestionCodes.WHO_ARE_YOU_REGISTERING

private const val FORM_CODE_CHILD_REGISTRATION = "CHILD_REGISTRATION"

data class DeliveryChildRegistrationUiState(
  val isLoading: Boolean = true,
  /** True if the session/child/schema/delivery-answers couldn't be resolved at all — see
   * [DeliveryChildRegistrationViewModel.load]'s own checks for the specific cases folded into this
   * one flag (mirrors [DeliverySessionUiState.hasError]'s same deliberately-coarse shape). */
  val hasError: Boolean = false,
  val version: FormVersion? = null,
  val answers: FormAnswers = FormAnswers(),
  val capturedImages: Map<String, String> = emptyMap(),
  /** `question_code`s of MEDIA fields the Sakhi has played back to completion — the same
   * per-field-not-per-form gate [org.armman.sakhi.ui.childregistration
   * .DynamicChildRegistrationViewModel] uses, needed here because CHILD_REGISTRATION (unlike
   * DELIVERY_VISIT) actually has MEDIA fields. */
  val mediaCompleted: Set<String> = emptySet(),
  val isSubmitting: Boolean = false,
  /** 0-based — which of (child1, child2, child3) this screen instance is currently registering.
   * Only meaningful once [isLoading] is false and [hasError] is false. */
  val childIndex: Int = 0,
  /** The already-known, already-created child beneficiary id this submission targets — see
   * [DeliveryChildRegistrationViewModel]'s class doc for why this is injected rather than obtained
   * from a `POST /beneficiaries` call. Null only before the first successful [load]. */
  val serverBeneficiaryId: String? = null,
  /** The mother's answered [DeliveryQuestionCodes.DATE_OF_DELIVERY], parsed — forwarded to
   * [FormDateRuleset.boundsFor] so the infant DOB picker can't be scrolled back before the delivery
   * that produced this registration (reported bug, 2026-08-25). Null only if the delivery answer is
   * somehow missing/unparseable — see [DeliveryChildRegistrationViewModel.load]'s own "shouldn't
   * happen" caveat for [deliveryAnswers] — in which case the DOB bound simply falls back to the
   * wider age-ceiling window, same "missing data gap" convention as every other optional bound in
   * [FormDateRuleset]. */
  val deliveryDate: LocalDate? = null,
)

sealed interface DeliveryChildRegistrationEvent {
  data object ExitForm : DeliveryChildRegistrationEvent

  /**
   * A child's CHILD_REGISTRATION submitted and synced. [hasMoreChildren] true means a twin/triplet
   * still needs registering — this ViewModel has already reloaded itself for that next child by the
   * time this event is collected, so the screen only needs to acknowledge it (e.g. a toast) rather
   * than navigate anywhere. [hasMoreChildren] false means every child is registered and the session
   * moved to [DeliverySessionStep.PP1] — [pp1LocalScheduleUuid] is that visit's schedule id, the
   * same best-effort/nullable contract as [DeliverySessionEvent.Submitted.pp1LocalScheduleUuid].
   */
  data class Submitted(val hasMoreChildren: Boolean, val pp1LocalScheduleUuid: String?) : DeliveryChildRegistrationEvent
  data object QueuedOffline : DeliveryChildRegistrationEvent
  data class SubmitFailed(val message: String) : DeliveryChildRegistrationEvent
}

/**
 * Drives CR-042's `CHILD_REGISTRATION` step: registering the child(ren) a `DELIVERY_VISIT`
 * submission auto-created server-side. Structurally a close copy of [DeliverySessionViewModel] —
 * same schema-load/field-state/validation/image-capture shape — but hardcoded to
 * `CHILD_REGISTRATION` and, unlike that ViewModel, resolves WHICH child/index it's registering from
 * the loaded [DeliverySessionEntity] rather than always being "the one form this screen hosts".
 *
 * One instance handles the WHOLE child sequence for twins/triplets, not just one child: on a
 * successful submit, if [DeliverySessionEntity.step] is still [DeliverySessionStep.CHILD_REGISTRATION]
 * afterward (i.e. another child is still pending), [onSubmit] calls [load] again rather than the
 * screen tearing down and recreating this ViewModel — the session row itself (re-read from
 * [deliverySessionRepository]) is the single source of truth for which child comes next, so there is
 * no separate per-child nav route to round-trip through.
 *
 * Does NOT call `POST /beneficiaries` — see [DeliveryChildRegistrationDraftRepository] and
 * [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmissionCoordinator]'s own docs for why
 * that would silently create a duplicate child beneficiary here.
 */
@HiltViewModel
class DeliveryChildRegistrationViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val lookupRepository: LookupRepository,
  private val geographyFieldOptionsResolver: GeographyFieldOptionsResolver,
  private val deliveryFormDraftRepository: DeliveryFormDraftRepository,
  private val deliveryChildRegistrationDraftRepository: DeliveryChildRegistrationDraftRepository,
  private val deliverySessionRepository: DeliverySessionRepository,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val localEnrolmentBeneficiarySource: LocalEnrolmentBeneficiarySource,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  /** The mother's local beneficiary id — needed only for [findPp1ScheduleUuid] once every child is
   * registered (PP1 is scheduled under the mother's own beneficiary, same as [DeliverySessionViewModel
   * .beneficiaryId]'s own use). */
  val motherLocalBeneficiaryId: String = savedStateHandle[NAV_ARG_BENEFICIARY_ID] ?: ""

  val sessionUuid: String = savedStateHandle[NAV_ARG_SESSION_UUID] ?: ""

  /** Re-minted on every [load] (not a fixed `val` the way [DeliverySessionViewModel
   * .localSubmissionUuid] is) — a single instance submits once per child in a twin/triplet
   * sequence, and each child's `CHILD_REGISTRATION` is its own distinct submission. */
  private var localSubmissionUuid: String = UUID.randomUUID().toString()

  private val _uiState = MutableStateFlow(DeliveryChildRegistrationUiState())
  val uiState: StateFlow<DeliveryChildRegistrationUiState> = _uiState.asStateFlow()

  private val _events = Channel<DeliveryChildRegistrationEvent>(Channel.BUFFERED)
  val events: Flow<DeliveryChildRegistrationEvent> = _events.receiveAsFlow()

  init {
    load()
  }

  fun load() {
    _uiState.update { DeliveryChildRegistrationUiState(isLoading = true) }
    localSubmissionUuid = UUID.randomUUID().toString()
    viewModelScope.launch {
      if (sessionUuid.isBlank()) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val session = deliverySessionRepository.getBySessionUuid(sessionUuid)
      val deliverySubmissionLocalUuid = session?.deliverySubmissionLocalUuid
      if (session == null || deliverySubmissionLocalUuid == null) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val childIndex = session.nextChildIndexToRegister
      val serverBeneficiaryId = childBeneficiaryIdAt(session, childIndex)
      // The REAL birth-order slot this compacted childIndex maps to (see
      // DeliverySessionEntity.child1BirthOrder's own doc) — falls back to childIndex itself when
      // unknown, i.e. exactly today's (imperfect but not new) behavior, not a new failure mode.
      val prefillChildIndex = childBirthOrderAt(session, childIndex)?.minus(1) ?: childIndex
      if (serverBeneficiaryId == null) {
        // Defensive: step is CHILD_REGISTRATION but no child is recorded at this index — nothing
        // this screen can do about that, same "can't render" fallback as a missing schema.
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val version = formsRepository.getActiveVersion(FORM_CODE_CHILD_REGISTRATION)
      if (version == null) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      // Empty FormAnswers() if the DELIVERY_VISIT draft payload is somehow gone (shouldn't happen —
      // see DeliveryFormDraftRepository.getAnswers's own doc): DeliveryToChildRegistrationPrefill
      // still seeds WHO_ARE_YOU_REGISTERING off an empty FormAnswers (every other .valueOf lookup on
      // it just returns null), so the Sakhi degrades to filling everything in fresh rather than
      // being blocked outright.
      val deliveryAnswers = deliveryFormDraftRepository.getAnswers(deliverySubmissionLocalUuid) ?: FormAnswers()
      val deliveryDate = deliveryAnswers.valueOf(DeliveryQuestionCodes.DATE_OF_DELIVERY)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
      // The mother's own MOTHER_REGISTRATION answers — read locally (no network call, see
      // DeliveryToChildRegistrationPrefill.singleValueAnswersFor's own doc) so her name, DOB/age,
      // geography, mobile/address, consent and household socio-demographics can all prefill onto
      // her child's registration instead of coming up blank (reported bug, 2026-08-19, initially
      // just geography — widened the same day to the full mother-details set on request).
      val motherAnswers = localEnrolmentBeneficiarySource.answersFor(motherLocalBeneficiaryId)
      val prefilledAnswers = FormAnswers(
        singleValues = DeliveryToChildRegistrationPrefill.singleValueAnswersFor(
          deliveryAnswers = deliveryAnswers,
          childIndex = prefillChildIndex,
          motherAnswers = motherAnswers,
          motherGeography = version.geography.orEmpty(),
          registrationDate = LocalDate.now(),
        ),
        multiValues = DeliveryToChildRegistrationPrefill.multiValueAnswersFor(deliveryAnswers, prefillChildIndex),
      )
      // registrtion_date and project_name are both hidden from the Sakhi (HIDDEN_QUESTION_CODES
      // includes ChildNonRenderableQuestionCodes.ALL) on the promise -- see that object's own doc --
      // that "something still auto-fills and submits them exactly as before". Nothing here ever did,
      // which is exactly the reported 422 (Missing required field: registrtion_date / project_name).
      val withRegistrationDate = RegistrationDatePrefill.apply(version.schemaJson, prefilledAnswers, LocalDate.now())
      val initialAnswers = prefillAutoSelectedGeography(version, withRegistrationDate)
      _uiState.update {
        it.copy(
          isLoading = false,
          version = version,
          answers = initialAnswers,
          childIndex = childIndex,
          serverBeneficiaryId = serverBeneficiaryId,
          deliveryDate = deliveryDate,
        )
      }
      // Bug fix (2026-08-21): `current_age_of_infant_in_days` ("Age of infant") is declared
      // `computedFrom: "CHILD_AGE_MONTHS"` on the live CHILD_REGISTRATION schema (see
      // FormComputedFieldEvaluator's own doc on that token) and derives from
      // `date_of_birth_of_infant`, which `initialAnswers` above already prefilled from the
      // Delivery form's date of delivery. But unlike DynamicChildRegistrationViewModel (the
      // standalone registration path), this ViewModel never actually evaluated any
      // `computedFrom` field — so the age field stayed blank on open even though its source DOB
      // was already there. Mirrors DynamicChildRegistrationViewModel.recomputeDerivedFields().
      recomputeDerivedFields()
    }
  }

  private fun recomputeDerivedFields() {
    val version = _uiState.value.version ?: return
    var answers = _uiState.value.answers
    version.schemaJson.forEach { field ->
      val computedFrom = field.computedFrom ?: return@forEach
      val value = FormComputedFieldEvaluator.compute(computedFrom, answers, LocalDate.now())
      answers = answers.withSingleValue(field.questionCode, value)
    }
    _uiState.update { it.copy(answers = answers) }
  }

  /**
   * Auto-selects every geography field (and project_name) that resolves to exactly one backend
   * unit -- mirrors [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel
   * .prefillAutoSelectedGeography]. Runs AFTER [DeliveryToChildRegistrationPrefill]'s mother-geography
   * copy so it only fills whatever level the mother-copy left blank (no local mother draft, or a
   * level she holds an id CHILD_REGISTRATION's active version didn't ship) -- project_name itself is
   * never covered by that copy at all, so without this step it was ALWAYS missing here.
   */
  private suspend fun prefillAutoSelectedGeography(version: FormVersion, answers: FormAnswers): FormAnswers {
    val geography = version.geography.orEmpty()
    var result = answers
    version.schemaJson
      .filter { it.questionCode in GeographyQuestionCodes.ALL }
      .forEach { field ->
        if (!result.valueOf(field.questionCode).isNullOrBlank()) return@forEach
        val only = geographyFieldOptionsResolver
          .optionsFromVersionGeography(field.questionCode, geography)
          .singleOrNull() ?: return@forEach
        result = result.withSingleValue(field.questionCode, only.valueCode)
      }
    return result
  }

  fun setAnswer(questionCode: String, value: String?) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withSingleValue(questionCode, value)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
    recomputeDerivedFields()
  }

  fun setMultiAnswer(questionCode: String, values: List<String>) {
    val fields = _uiState.value.version?.schemaJson.orEmpty()
    _uiState.update {
      val previousAnswers = it.answers
      val updatedAnswers = previousAnswers.withMultiValue(questionCode, values)
      it.copy(answers = FormHiddenFieldReset.apply(fields, previousAnswers, updatedAnswers))
    }
    recomputeDerivedFields()
  }

  fun setCapturedImage(questionCode: String, uri: String?) {
    _uiState.update {
      it.copy(
        capturedImages = if (uri == null) it.capturedImages - questionCode else it.capturedImages + (questionCode to uri),
        answers = it.answers.withSingleValue(questionCode, uri),
      )
    }
  }

  /** Marks a MEDIA field's playback complete — mirrors [org.armman.sakhi.ui.childregistration
   * .DynamicChildRegistrationViewModel.markMediaComplete], including that ViewModel's own fix: the
   * completion must ALSO be mirrored into FormAnswers as "true", not just [mediaCompleted] -- the
   * backend requires the field present in the submitted formData and 422s otherwise (Missing
   * required field: arogya_sakhi_video / consent_audio, reported 2026-08-19). Idempotent; there is
   * no "un-mark". */
  fun markMediaComplete(questionCode: String) {
    _uiState.update {
      it.copy(
        mediaCompleted = it.mediaCompleted + questionCode,
        answers = it.answers.withSingleValue(questionCode, "true"),
      )
    }
  }

  /** Fields currently shown, in schema order — honors [FormVisibilityEvaluator] and excludes
   * [HIDDEN_QUESTION_CODES]. */
  fun visibleFields(): List<FormFieldSchema> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    return version.schemaJson.filter { field ->
      FormVisibilityEvaluator.isVisible(field, state.answers) && field.questionCode !in HIDDEN_QUESTION_CODES
    }
  }

  /** This field's tab label — falls back to [FALLBACK_SECTION] if the schema didn't tag one.
   * Mirrors [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel.sectionOf]. */
  fun sectionOf(field: FormFieldSchema): String = field.section ?: FALLBACK_SECTION

  /** Distinct tab labels across currently-visible fields, in the order each first appears. */
  fun sections(): List<String> = visibleFields().map(::sectionOf).distinct()

  /** Visible fields belonging to one tab, in schema order. */
  fun fieldsInSection(section: String): List<FormFieldSchema> =
    visibleFields().filter { sectionOf(it) == section }

  /**
   * Options for a select/radio/multiselect field, in priority order: inline `options`, then the
   * geography special case, then a `lookup_category_code` fetch.
   *
   * The geography branch was MISSING here until this fix — reported bug, 2026-08-19: every
   * State/District/Block/Village/Pada/PHC/Sub Centre dropdown on this screen rendered with no
   * options at all — not just no *selected* value, no options to pick from either — because
   * CHILD_REGISTRATION's geography fields carry neither inline `options` nor a
   * `lookup_category_code`; they only resolve against the active version's own `geography` array,
   * exactly like [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel.optionsFor]
   * already does for the standalone registration path. Without this branch every geography answer
   * this ViewModel prefills from the mother's record (see [DeliveryToChildRegistrationPrefill]) was
   * set correctly in [FormAnswers] but had no option list to resolve its label against, so it never
   * rendered — indistinguishable on screen from the prefill itself having failed.
   */
  suspend fun optionsFor(field: FormFieldSchema): List<FormFieldOption> {
    field.options?.let { return it.sortedBy(FormFieldOption::sortOrder) }
    if (field.questionCode in GeographyQuestionCodes.ALL) {
      return geographyFieldOptionsResolver.optionsFromVersionGeography(
        field.questionCode,
        _uiState.value.version?.geography.orEmpty(),
      )
    }
    val categoryCode = field.lookupCategoryCode ?: return emptyList()
    return lookupRepository.getValues(categoryCode)
      .mapIndexed { index, value -> FormFieldOption(label = value.valueLabel, sortOrder = index, valueCode = value.valueCode) }
  }

  fun crossFieldViolations(): List<FormCrossFieldRule> {
    val version = _uiState.value.version ?: return emptyList()
    return FormCrossFieldValidator.violatedRules(version.validationJson, _uiState.value.answers)
  }

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
        FormFieldInputType.MEDIA -> field.questionCode in state.mediaCompleted
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
   * in range. Cross-field rules are only enforced at final submit via [isReadyToSubmit]. */
  fun isSectionReady(section: String): Boolean {
    if (_uiState.value.version == null) return false
    return fieldsAnsweredAndInRange(fieldsInSection(section))
  }

  /**
   * Resolved review data for the Summary tab: every answered, currently-visible field grouped by
   * section, coded values mapped to display labels. [mediaCompletedLabel]/[imageCapturedLabel] are
   * passed in so localized text stays in Compose while resolution stays testable here. Empty
   * sections are dropped. Mirrors [org.armman.sakhi.ui.childregistration
   * .DynamicChildRegistrationViewModel.buildSummary].
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

  fun exitForm() {
    _events.trySend(DeliveryChildRegistrationEvent.ExitForm)
  }

  /** No-op if [isReadyToSubmit] is false or a submit is already in flight — mirrors every other
   * dynamic form's submit-button gating contract. */
  fun onSubmit() {
    val state = _uiState.value
    if (state.isSubmitting || !isReadyToSubmit()) return
    val version = state.version ?: return
    val serverBeneficiaryId = state.serverBeneficiaryId ?: return

    // The one non-renderable field this coordinator requires the caller to inject itself — see
    // DeliveryChildRegistrationSubmissionCoordinator.submit's own doc for why it's deliberately not
    // done for us.
    val submissionAnswers = state.answers.withSingleValue(
      ChildNonRenderableQuestionCodes.BENEFICIARY_ID,
      serverBeneficiaryId,
    )

    _uiState.update { it.copy(isSubmitting = true) }
    viewModelScope.launch {
      val result = deliveryChildRegistrationDraftRepository.submitDraft(
        localSubmissionUuid = localSubmissionUuid,
        localSessionUuid = sessionUuid,
        serverBeneficiaryId = serverBeneficiaryId,
        formVersionId = version.id,
        answers = submissionAnswers,
      )
      when (result) {
        is DeliveryChildRegistrationSubmitResult.Synced -> handleSynced()
        is DeliveryChildRegistrationSubmitResult.QueuedOffline -> {
          _uiState.update { it.copy(isSubmitting = false) }
          _events.trySend(DeliveryChildRegistrationEvent.QueuedOffline)
        }
        is DeliveryChildRegistrationSubmitResult.Failed -> {
          _uiState.update { it.copy(isSubmitting = false) }
          _events.trySend(DeliveryChildRegistrationEvent.SubmitFailed(result.message))
        }
      }
    }
  }

  /** Re-reads the session (the coordinator already advanced it on success — see
   * [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmissionCoordinator
   * .advanceSessionAfterChildRegistered]) to decide whether another child is still pending or the
   * session has moved on to PP1. */
  private suspend fun handleSynced() {
    val session = deliverySessionRepository.getBySessionUuid(sessionUuid)
    if (session != null && session.step == DeliverySessionStep.CHILD_REGISTRATION) {
      _events.trySend(DeliveryChildRegistrationEvent.Submitted(hasMoreChildren = true, pp1LocalScheduleUuid = null))
      load()
      return
    }
    _uiState.update { it.copy(isSubmitting = false) }
    val pp1ScheduleUuid = findPp1ScheduleUuid()
    _events.trySend(DeliveryChildRegistrationEvent.Submitted(hasMoreChildren = false, pp1LocalScheduleUuid = pp1ScheduleUuid))
  }

  private fun childBeneficiaryIdAt(session: DeliverySessionEntity, index: Int): String? = when (index) {
    0 -> session.child1BeneficiaryId
    1 -> session.child2BeneficiaryId
    2 -> session.child3BeneficiaryId
    else -> null
  }

  /** See [DeliverySessionEntity.child1BirthOrder]'s own doc — the REAL 1-based `DELIVERY_VISIT`
   * birth-order slot for the child at compacted [index], or null if unknown (pre-migration session
   * row, or the delivery answers didn't parse cleanly at submit time). */
  private fun childBirthOrderAt(session: DeliverySessionEntity, index: Int): Int? = when (index) {
    0 -> session.child1BirthOrder
    1 -> session.child2BirthOrder
    2 -> session.child3BirthOrder
    else -> null
  }

  /** The mother's PP1 row, if it still needs the Sakhi's input — same best-effort contract as
   * [DeliverySessionViewModel.findPp1ScheduleUuid].
   *
   * Bug fix (2026-09-02): excludes an already-[VisitScheduleStatus.COMPLETED] PP1 row. Without
   * this, a Sakhi who opened PP1 straight from "See Visits" and submitted it *before* finishing
   * the child's registration (a valid, if out-of-sequence, path — PP1 is generated and startable
   * the moment the delivery form syncs, independently of this session's own step machine) would
   * be auto-navigated straight back into that same PP1 visit the moment child registration
   * finished — landing on a blank [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen] that
   * looks like PP1 "started over", even though [org.armman.sakhi.data.schedule
   * .VisitScheduleEntity.status] was already correctly COMPLETED. Returning null here instead
   * makes [handleSynced] fall back to the normal "no PP1 to hand off to" outcome, same as if PP1
   * had never been generated at all. */
  private suspend fun findPp1ScheduleUuid(): String? =
    runCatching {
      visitScheduleRepository.getActiveForBeneficiary(motherLocalBeneficiaryId)
        .firstOrNull {
          it.visitType == VisitCodeType.PP && it.sequenceNo == 1 && it.status != VisitScheduleStatus.COMPLETED
        }
        ?.localScheduleUuid
    }.getOrNull()

  private companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_SESSION_UUID = "sessionUuid"
  }
}
