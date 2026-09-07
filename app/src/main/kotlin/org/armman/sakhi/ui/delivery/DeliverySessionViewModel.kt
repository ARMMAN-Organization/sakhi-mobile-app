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
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliveryFormSubmitResult
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
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
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class DeliverySessionUiState(
  val isLoading: Boolean = true,
  /** Non-null only if the schema could not be loaded at all. As of this pass `DELIVERY_VISIT` has
   * no published backend schema yet (see [DeliveryQuestionCodes]'s doc) — this state is therefore
   * the expected one in production until that ships, same "can't render without one" gap every
   * other schema-driven form has before its own schema is published. */
  val hasError: Boolean = false,
  val version: FormVersion? = null,
  val answers: FormAnswers = FormAnswers(),
  val capturedImages: Map<String, String> = emptyMap(),
  val isSubmitting: Boolean = false,
  /** The mother's ANC registration date, for [FormDateRuleset.DATE_OF_DELIVERY]/
   * [DeliveryQuestionCodes.DATE_OF_DISCHARGE]'s ">registration date" floor. Best-effort — see
   * [DeliverySessionViewModel.loadBeneficiaryDateContext] — null when unresolvable, same
   * "missing data gap" convention every other caller of [FormDateRuleset] already follows. */
  val beneficiaryRegistrationDate: LocalDate? = null,
  /** The mother's LMP on file, for the same two fields' ">LMP date" floor. Same best-effort/null
   * convention as [beneficiaryRegistrationDate]. */
  val motherLmpDate: LocalDate? = null,
)

sealed interface DeliverySessionEvent {
  data object ExitForm : DeliverySessionEvent

  /**
   * Delivery form submitted and synced. [childBeneficiaryIds] is exactly
   * [org.armman.sakhi.data.forms.SubmissionResponseData.childBeneficiaryIds] (null = no live
   * birth). [pp1LocalScheduleUuid] is the freshly-generated PP1 visit's schedule id if one was
   * found (it always should be, since [org.armman.sakhi.data.schedule.VisitScheduleCoordinator
   * .onDeliveryRecorded] generates the PP series unconditionally) — null only defensively.
   */
  data class Submitted(val childBeneficiaryIds: List<String>?, val pp1LocalScheduleUuid: String?) : DeliverySessionEvent
  data object QueuedOffline : DeliverySessionEvent
  data class SubmitFailed(val message: String) : DeliverySessionEvent
}

/**
 * Drives the `DELIVERY_VISIT` form — the CR-042 Delivery Event Session's entry step. Deliberately
 * scoped to ONLY this first step: the CHILD_REGISTRATION/PP1/NN steps that follow a successful
 * submission are NOT hosted by this screen. PP1 and any same-session NN visit are already-scheduled
 * visits by the time this form submits ([org.armman.sakhi.data.schedule.VisitScheduleCoordinator
 * .onDeliveryRecorded] generates them under the mother's own `localBeneficiaryId` — no separate
 * child beneficiary needed for either), so they're handled by navigating straight into the
 * existing [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen] via its own route, the same way
 * every other scheduled visit opens. CHILD_REGISTRATION has no equivalent shortcut: it needs a
 * real local beneficiary record for the auto-created child, and the contract for that (does the
 * backend already fully register the child, or does the device still need to submit
 * `CHILD_REGISTRATION` against it, and what carries the mother↔child link locally) is still an
 * open backend-needs question (CR-041, item 2.4) — deliberately left unbuilt rather than guessed
 * at. See [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryProfileViewModel]'s delivery-button
 * state for how that gap is surfaced honestly to the Sakhi instead of silently broken.
 *
 * Otherwise a close copy of [org.armman.sakhi.ui.adhocform.AdHocFormViewModel] — same schema-load,
 * field-state, validation and image-capture shape — with [formCode] hardcoded to `DELIVERY_VISIT`
 * rather than a nav arg (this screen serves exactly one form, unlike the five ad-hoc forms sharing
 * one ViewModel).
 */
@HiltViewModel
class DeliverySessionViewModel @Inject constructor(
  private val formsRepository: FormsRepository,
  private val lookupRepository: LookupRepository,
  private val deliveryFormDraftRepository: DeliveryFormDraftRepository,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val beneficiaryProfileRepository: BeneficiaryProfileRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  val beneficiaryId: String = savedStateHandle[NAV_ARG_BENEFICIARY_ID] ?: ""

  /** Minted once by the caller when the Sakhi first taps Delivery Form (see
   * [org.armman.sakhi.ui.navigation.Routes.deliverySession]'s doc) and passed as a nav arg so a
   * process-death-and-resume re-creates this ViewModel against the SAME session id rather than
   * accidentally starting a second one. */
  val sessionUuid: String = savedStateHandle[NAV_ARG_SESSION_UUID] ?: ""

  /** Fresh per ViewModel instance, same rationale as [org.armman.sakhi.ui.adhocform
   * .AdHocFormViewModel.localFormInstanceUuid] — this screen only ever hosts the DELIVERY_FORM
   * step (see class doc), which per [org.armman.sakhi.data.delivery.DeliverySessionEntity]'s own
   * doc has no persisted draft to resume before a first submit succeeds. */
  val localSubmissionUuid: String = UUID.randomUUID().toString()

  private val _uiState = MutableStateFlow(DeliverySessionUiState())
  val uiState: StateFlow<DeliverySessionUiState> = _uiState.asStateFlow()

  private val _events = Channel<DeliverySessionEvent>(Channel.BUFFERED)
  val events: Flow<DeliverySessionEvent> = _events.receiveAsFlow()

  init {
    load()
  }

  fun load() {
    _uiState.update { DeliverySessionUiState(isLoading = true) }
    viewModelScope.launch {
      if (beneficiaryId.isBlank() || sessionUuid.isBlank()) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      val version = formsRepository.getActiveVersion(FORM_CODE_DELIVERY_VISIT)
      if (version == null) {
        _uiState.update { it.copy(isLoading = false, hasError = true) }
        return@launch
      }
      _uiState.update { it.copy(isLoading = false, version = version) }
      loadBeneficiaryDateContext()
      prefillTodayDateFields()
    }
  }

  /** Best-effort, same contract as [org.armman.sakhi.ui.adhocform.AdHocFormViewModel
   * .loadBeneficiaryRegistrationDate]: a lookup failure (offline, id not resolvable) must not
   * block the already-loaded form — [FormDateRuleset]'s registration/LMP floors for
   * [DeliveryQuestionCodes.DATE_OF_DELIVERY]/[DeliveryQuestionCodes.DATE_OF_DISCHARGE] simply stay
   * unset in that case. [BeneficiaryProfile.lmp] is parsed with the same format every other reader
   * of that field uses (matches [org.armman.sakhi.data.beneficiaryprofile
   * .ScheduleBackedBeneficiaryProfileRepository.PROFILE_DATE_FORMAT] — same source). */
  private suspend fun loadBeneficiaryDateContext() {
    val profile = runCatching { beneficiaryProfileRepository.getBeneficiary(beneficiaryId) }.getOrNull()
    val lmp = profile?.lmp?.let { runCatching { LocalDate.parse(it, PROFILE_DATE_FORMAT) }.getOrNull() }
    _uiState.update { it.copy(beneficiaryRegistrationDate = profile?.registrationDate, motherLmpDate = lmp) }
  }

  /** "Delivery form filled date" (spec: "Should automatically select today's date") — mirrors
   * [org.armman.sakhi.ui.adhocform.AdHocFormViewModel.prefillTodayDateFields]'s exact reasoning.
   * Only sets the field if it's blank (a restored draft is never overwritten); a no-op if the
   * loaded schema doesn't carry this code at all. */
  private fun prefillTodayDateFields() {
    _uiState.update { state ->
      val fieldsPresent = state.version?.schemaJson.orEmpty().map { it.questionCode }.toSet()
      val code = DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON
      if (code in fieldsPresent && state.answers.valueOf(code).isNullOrBlank()) {
        state.copy(answers = state.answers.withSingleValue(code, LocalDate.now().toString()))
      } else {
        state
      }
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

  /** Fields currently shown, in schema order — honors [FormVisibilityEvaluator]. */
  fun visibleFields(): List<FormFieldSchema> {
    val state = _uiState.value
    val version = state.version ?: return emptyList()
    return version.schemaJson.filter { FormVisibilityEvaluator.isVisible(it, state.answers) }
  }

  suspend fun optionsFor(field: FormFieldSchema): List<FormFieldOption> {
    field.options?.let { return it.sortedBy(FormFieldOption::sortOrder) }
    val categoryCode = field.lookupCategoryCode ?: return emptyList()
    return lookupRepository.getValues(categoryCode)
      .mapIndexed { index, value -> FormFieldOption(label = value.valueLabel, sortOrder = index, valueCode = value.valueCode) }
  }

  /** This field's Summary-tab card title — falls back to [FALLBACK_SECTION] if the schema didn't
   * tag one. Only used for the Summary tab's grouping: unlike [DeliveryChildRegistrationViewModel],
   * this form's input side stays one flat scrollable list regardless of section (see class doc) —
   * only the review groups fields into per-section cards, e.g. "Delivery Details"/"Infant Details"
   * (the exact titles come from the active schema's own `section` values). */
  private fun sectionOf(field: FormFieldSchema): String = field.section ?: FALLBACK_SECTION

  /**
   * Resolved review data for the Summary tab: every answered, currently-visible field grouped by
   * schema section into its own card, in the order each section first appears, coded values mapped
   * to display labels. [imageCapturedLabel] is passed in so localized text stays in Compose while
   * resolution stays testable here. Empty sections are dropped. This form has no MEDIA fields (see
   * class doc), so unlike [DeliveryChildRegistrationViewModel.buildSummary] there is no
   * mediaCompletedLabel to plumb through.
   */
  suspend fun buildSummary(imageCapturedLabel: String): List<SummarySection> {
    if (_uiState.value.version == null) return emptyList()
    val fields = visibleFields()
    val sectionTitles = fields.map(::sectionOf).distinct()
    return sectionTitles.map { title ->
      SummarySection(
        title = title,
        rows = fields.filter { sectionOf(it) == title }.mapNotNull { summaryRowFor(it, imageCapturedLabel) },
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

  fun crossFieldViolations(): List<FormCrossFieldRule> {
    val version = _uiState.value.version ?: return emptyList()
    return FormCrossFieldValidator.violatedRules(version.validationJson, _uiState.value.answers)
  }

  fun isReadyToSubmit(): Boolean {
    val state = _uiState.value
    if (state.version == null) return false
    val fields = visibleFields()
    return fieldsAnsweredAndInRange(fields) &&
      crossFieldViolations().isEmpty() &&
      FormDateRuleset.allDatesValid(fields, state.answers, LocalDate.now())
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
    _events.trySend(DeliverySessionEvent.ExitForm)
  }

  /** No-op if [isReadyToSubmit] is false or a submit is already in flight — mirrors every other
   * dynamic form's submit-button gating contract. */
  fun onSubmit() {
    val state = _uiState.value
    if (state.isSubmitting || !isReadyToSubmit()) return
    val version = state.version ?: return

    val deliveryDate = resolveDeliveryDate(state.answers)
    val deliveryFormFilledOn = resolveDeliveryFormFilledOn(state.answers, deliveryDate)

    _uiState.update { it.copy(isSubmitting = true) }
    viewModelScope.launch {
      val result = deliveryFormDraftRepository.submitDraft(
        localSubmissionUuid = localSubmissionUuid,
        localSessionUuid = sessionUuid,
        localBeneficiaryId = beneficiaryId,
        formVersionId = version.id,
        answers = state.answers,
        deliveryDate = deliveryDate,
        deliveryFormFilledOn = deliveryFormFilledOn,
      )
      _uiState.update { it.copy(isSubmitting = false) }
      when (result) {
        is DeliveryFormSubmitResult.Synced -> {
          val pp1ScheduleUuid = findPp1ScheduleUuid()
          _events.trySend(DeliverySessionEvent.Submitted(result.childBeneficiaryIds, pp1ScheduleUuid))
        }
        is DeliveryFormSubmitResult.QueuedOffline -> _events.trySend(DeliverySessionEvent.QueuedOffline)
        is DeliveryFormSubmitResult.Failed -> _events.trySend(DeliverySessionEvent.SubmitFailed(result.message))
      }
    }
  }

  /** [DeliveryQuestionCodes.DATE_OF_DELIVERY], falling back to today only if the field is somehow
   * unanswered despite [isReadyToSubmit] having passed (defensive — should be unreachable while
   * that field is `required`). */
  private fun resolveDeliveryDate(answers: FormAnswers): LocalDate =
    answers.valueOf(DeliveryQuestionCodes.DATE_OF_DELIVERY)?.let(LocalDate::parse) ?: LocalDate.now()

  /** [DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON] if the schema carries it, else [deliveryDate]
   * itself — see that constant's own doc for why that fallback is correct, not just convenient. */
  private fun resolveDeliveryFormFilledOn(answers: FormAnswers, deliveryDate: LocalDate): LocalDate =
    answers.valueOf(DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON)?.let(LocalDate::parse) ?: deliveryDate

  /** The mother's PP1 row, if it still needs the Sakhi's input. Best-effort: a lookup failure or
   * a genuinely absent row (shouldn't happen — see [DeliverySessionEvent.Submitted]'s doc) just
   * means the screen falls back to "Delivery recorded" and returns to the profile instead of
   * auto-navigating into PP1.
   *
   * Bug fix (2026-09-02): also excludes an already-[VisitScheduleStatus.COMPLETED] PP1 row — same
   * fix and same rationale as [DeliveryChildRegistrationViewModel.findPp1ScheduleUuid]. This
   * screen only calls this immediately after the DELIVERY_VISIT form itself submits (no live
   * birth, so there is no child-registration detour), so a completed PP1 can only happen here on a
   * resumed/retried session — but the guard belongs on both call sites for the same invariant:
   * never auto-navigate into a PP1 the Sakhi has already submitted. */
  private suspend fun findPp1ScheduleUuid(): String? =
    runCatching {
      visitScheduleRepository.getActiveForBeneficiary(beneficiaryId)
        .firstOrNull {
          it.visitType == VisitCodeType.PP && it.sequenceNo == 1 && it.status != VisitScheduleStatus.COMPLETED
        }
        ?.localScheduleUuid
    }.getOrNull()

  private companion object {
    const val NAV_ARG_BENEFICIARY_ID = "beneficiaryId"
    const val NAV_ARG_SESSION_UUID = "sessionUuid"
    const val FORM_CODE_DELIVERY_VISIT = "DELIVERY_VISIT"

    /** Matches ScheduleBackedBeneficiaryProfileRepository.PROFILE_DATE_FORMAT — same source. */
    val PROFILE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
  }
}
