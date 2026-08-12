package org.armman.sakhi.ui.forms

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.AGE_FROM_DOB_QUESTION_CODES
import org.armman.sakhi.data.forms.isAgeFromDobReadOnly
import org.armman.sakhi.data.forms.BeneficiaryNameRule
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormDateRuleset
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormNumericInputRule
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.MobileNumberRule
import org.armman.sakhi.data.forms.FormMultiSelectExclusivity
import org.armman.sakhi.data.forms.TRIMESTER_QUESTION_CODE
import org.armman.sakhi.ui.enrollment.components.AppCheckboxGroup
import org.armman.sakhi.ui.enrollment.components.AppDateField
import org.armman.sakhi.ui.enrollment.components.AppDropdownField
import org.armman.sakhi.ui.enrollment.components.AppRadioGroup
import org.armman.sakhi.ui.enrollment.components.AppReadOnlyField
import org.armman.sakhi.ui.enrollment.components.AppSingleCheckbox
import org.armman.sakhi.ui.enrollment.components.AppTextInputField
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.VideoPlaceholderSurface
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Consent-audio field code — the one `media` field the design renders as a "Play …" pill button
 * instead of the video player box. See the MEDIA branch in [DynamicFormField]. */
private const val QUESTION_CODE_CONSENT_AUDIO = "consent_audio"

/** Consent welcome-video field code — the `media` field rendered as the embedded player box under
 * the design's "Welcome to Arogyasakhi Program!" heading (which replaces the plain schema label). */
private const val QUESTION_CODE_CONSENT_VIDEO = "arogya_sakhi_video"

private const val VALUE_YES = "yes"
private const val VALUE_NO = "no"

/** `number` fields whose real-world unit needs a fractional part — e.g. a newborn's weight in kg
 * ("3.2"), unlike a count field like household members. Kept as local literals (matching the
 * "Date of visit" precedent above), not an import from `ChildRegistrationQuestionCodes`, so this
 * generic renderer doesn't pick up a dependency on the child-registration flow for one field.
 * Every other `number` field keeps the existing digits-only behaviour. */
private val DECIMAL_NUMBER_QUESTION_CODES = setOf(
  "child_weight_at_birth_in_kg",
)

/** The Consent tab's affirmation items. The schema types them as yes/no `radio`s, but the design
 * shows each as a single checkbox under "Ensure that the beneficiary". Rendered as a checkbox for
 * exactly these codes; every other radio (incl. `did_we_receive_consent`) keeps the Yes/No pair.
 * The stored answer is unchanged — checked = "yes", unchecked = "no" — so the submission payload
 * is identical to the radio version; only the widget differs. */
private val CONSENT_CHECKBOX_QUESTION_CODES = setOf(
  "consent_willing_share_personal_info",
  "consent_willing_share_health_history",
  "consent_willing_participate_diagnostic_tests",
  "consent_understands_referral_logic",
)

/** The Visit Form's "Date of visit" (`org.armman.sakhi.data.visitform.VisitFormQuestionCodes
 * .DATE_OF_VISIT` — kept as a local literal, not an import, so this generic renderer doesn't pick
 * up a dependency on the visit-form package for one field). Shown in dd-mm-yyyy, unlike every
 * other date field's "dd MMM yyyy" — asked for explicitly (bharath, 2026-08-07), this field only. */
private const val DATE_OF_VISIT_QUESTION_CODE = "date_of_visit"

/**
 * Renders one [FormFieldSchema] generically, dispatching on [FormFieldSchema.inputType] — the
 * heart of CR-018: a field the backend adds or changes shows up here automatically, no per-field
 * Compose code to write.
 *
 * `multiselect_date` (currently only `has_the_women_received_td_dose`) renders as a plain
 * multi-select checkbox group with NO per-option date picker embedded in it — that part is
 * deliberate and permanent, not a gap. The per-option dates ([TdDoseQuestionCodes]) are 3
 * separate ordinary `date` fields the schema declares alongside it (added 2026-08-06, see
 * `td-dose-dates-schema-gap.md`), each shown/hidden by its own `visibleWhen` `contains` rule
 * against this field's answer — so they render through the plain DATE branch below like any
 * other date field, and CR-018's "no per-field Compose code" property holds even for this case.
 *
 * `media`/`image` fields don't perform real capture themselves (same as the existing static
 * Consent step) — [onPlayMedia]/[onCaptureImage] are callbacks the hosting screen wires to real
 * playback/camera flows, mirroring `ConsentStep`'s `onPlayVideo`/`onTakePhoto`.
 *
 * A field with [FormFieldSchema.computedFrom] set (e.g. EDD, gestational age, unique_id) is never
 * Sakhi-entered — it's rendered read-only via [AppReadOnlyField] regardless of `input_type`,
 * rather than as an editable box whose typed value would just get silently overwritten the next
 * time [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.recomputeDerivedFields] runs.
 * A blank/not-yet-computed value shows a placeholder rather than an empty box, so it doesn't look
 * broken.
 *
 * The trimester field ([TRIMESTER_QUESTION_CODE]) is treated the same way even though the live
 * schema doesn't (yet) set `computedFrom` on it — see that constant's doc. Without this, it'd
 * render as a normal editable box whose typed value gets silently clobbered by
 * `recomputeDerivedFields`'s stopgap every time any other answer changes.
 *
 * The DOB-derived age field ([AGE_FROM_DOB_QUESTION_CODES]) is the one exception to "computedFrom
 * means read-only": per CR-037 the spec's "either DOB or age" pair means this field is read-only
 * ONLY while `date_of_birth` has a value ([isAgeFromDobReadOnly]) — once DOB is blank it renders as
 * a normal editable NUMBER input so the Sakhi can type age directly instead.
 */
// `WindowInsets.isImeVisible` and `BringIntoViewRequester` are both still opt-in; used only for the
// keep-focused-field-visible effect below.
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DynamicFormField(
  field: FormFieldSchema,
  answers: FormAnswers,
  /** The form's registration date (the hosting ViewModel's own `registrationDate`). Reference point
   * for [FormDateRuleset]'s bounds/validation, so the picker limits and the derived age field agree
   * on the same "now". */
  registrationDate: LocalDate,
  mediaCompleted: Boolean,
  capturedImageUri: String?,
  loadOptions: suspend () -> List<FormFieldOption>,
  onSingleAnswer: (String?) -> Unit,
  onMultiAnswer: (List<String>) -> Unit,
  onPlayMedia: () -> Unit,
  onCaptureImage: () -> Unit,
  modifier: Modifier = Modifier,
  /** Server-side per-field validation message (from a `400 VALIDATION_ERROR`), shown inline under
   * the field. For text/number fields it feeds the widget's own `errorText` slot and takes
   * precedence over the local range/mobile hint; for every other field type it renders as a
   * trailing error line. Null = no server error for this field. */
  errorText: String? = null,
  /** Question codes the HOST screen has decided are locked shut for reasons the schema itself
   * can't express — e.g. the Visit Form's "height" (ANC_VISIT spec row 12: "Open only in first
   * visit; auto-populate in the rest"), which is read-only once a prior visit already captured it,
   * not because it's `computedFrom` anything. Renders via [AppReadOnlyField] like a computed field,
   * but shows the plain value with no "Auto-calculated" placeholder (bharath, 2026-08-08). Empty
   * for every caller except the Visit Form. */
  readOnlyQuestionCodes: Set<String> = emptySet(),
) {
  val singleValue = answers.valueOf(field.questionCode).orEmpty()
  val multiValue = answers.multiValueOf(field.questionCode)

  // Text/number/date branches surface [errorText] through the input widget's own error slot; every
  // other branch gets a trailing [FieldErrorText] appended below it, so a select/radio/geography
  // field can show a server error too. Tracked so the trailing line isn't duplicated for the
  // widget-native cases.
  // The age-from-DOB field is only forced into the read-only/no-inline-error bucket while DOB is
  // actually answered (see isAgeFromDobReadOnly's doc) — once DOB is blank it renders and errors
  // exactly like any other NUMBER field.
  val ageFromDobEditable = field.questionCode in AGE_FROM_DOB_QUESTION_CODES &&
    !isAgeFromDobReadOnly(answers)
  val rendersErrorInline = if (ageFromDobEditable) {
    field.inputType in INLINE_ERROR_INPUT_TYPES
  } else {
    field.computedFrom == null &&
      field.questionCode !in AGE_FROM_DOB_QUESTION_CODES &&
      field.questionCode != TRIMESTER_QUESTION_CODE &&
      field.questionCode !in GeographyQuestionCodes.ALL &&
      field.questionCode !in readOnlyQuestionCodes &&
      field.inputType in INLINE_ERROR_INPUT_TYPES
  }

  // Keep a focused field above the keyboard.
  //
  // A TextField already asks to be scrolled into view when it gains focus, but that request is
  // measured against the viewport as it is at that instant — *before* the IME animates open and
  // `safeDrawingPadding()` shrinks the form sheet under it. Nothing re-runs afterwards, so the
  // field ends up clipped by the new, shorter viewport. Re-requesting once the IME is actually
  // visible fixes it for every input type at once, which is why this lives on the shared wrapper
  // rather than in the TEXT/NUMBER branches: focus events bubble up from whatever widget the
  // branch rendered.
  val bringIntoViewRequester = remember { BringIntoViewRequester() }
  var hasFocus by remember { mutableStateOf(false) }
  val imeVisible = WindowInsets.isImeVisible
  LaunchedEffect(hasFocus, imeVisible) {
    if (hasFocus && imeVisible) bringIntoViewRequester.bringIntoView()
  }

  Column(
    modifier = modifier
      .bringIntoViewRequester(bringIntoViewRequester)
      // `hasFocus`, not `isFocused`: the focus sits on the child widget, not on this Column.
      .onFocusEvent { hasFocus = it.hasFocus },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    DynamicFormFieldBody(
      field = field,
      answers = answers,
      registrationDate = registrationDate,
      singleValue = singleValue,
      multiValue = multiValue,
      mediaCompleted = mediaCompleted,
      capturedImageUri = capturedImageUri,
      loadOptions = loadOptions,
      onSingleAnswer = onSingleAnswer,
      onMultiAnswer = onMultiAnswer,
      onPlayMedia = onPlayMedia,
      onCaptureImage = onCaptureImage,
      serverErrorText = errorText,
      readOnlyQuestionCodes = readOnlyQuestionCodes,
    )
    if (errorText != null && !rendersErrorInline) {
      FieldErrorText(errorText)
    }
  }
}

/** `input_type`s whose widget has its own `errorText` slot and therefore renders a server error
 * inline itself (so no separate trailing error line is added for them). */
private val INLINE_ERROR_INPUT_TYPES = setOf(
  FormFieldInputType.TEXT,
  FormFieldInputType.TEXT_GEO,
  FormFieldInputType.NUMBER,
  FormFieldInputType.DATE,
)

/** User-facing message for a [FormDateRuleset.Violation]. Reuses the static enrollment flow's
 * existing (already translated) messages, so both flows word the same rule identically. */
@StringRes
private fun FormDateRuleset.Violation.messageRes(): Int = when (this) {
  FormDateRuleset.Violation.AGE_OUT_OF_RANGE -> R.string.enrollment_error_age_range
  FormDateRuleset.Violation.LMP_FUTURE -> R.string.enrollment_error_lmp_future
  FormDateRuleset.Violation.LMP_TOO_RECENT -> R.string.enrollment_error_lmp_recent
  FormDateRuleset.Violation.LMP_TOO_OLD -> R.string.enrollment_error_lmp_old
  FormDateRuleset.Violation.GESTATIONAL_AGE_BEYOND_ENROLLMENT_WINDOW -> R.string.enrollment_error_gestational_age_ceiling
  FormDateRuleset.Violation.REGISTRATION_DATE_IN_FUTURE -> R.string.enrollment_error_registration_date_future
  FormDateRuleset.Violation.ANC1_DATE_NOT_AFTER_LMP -> R.string.enrollment_error_anc1_date_not_after_lmp
  FormDateRuleset.Violation.ANC1_DATE_TOO_LATE -> R.string.enrollment_error_anc1_date_too_late
  FormDateRuleset.Violation.TD_DATE_IN_FUTURE -> R.string.form_error_td_date_future
  FormDateRuleset.Violation.TD_2_NOT_AFTER_TD_1 -> R.string.form_error_td2_not_after_td1
  FormDateRuleset.Violation.TD_BOOSTER_NOT_AFTER_TD_2 -> R.string.form_error_td_booster_not_after_td2
  FormDateRuleset.Violation.LMP_DATE_EDIT_FUTURE -> R.string.form_error_td_date_future
  FormDateRuleset.Violation.VACCINATION_AT_BIRTH_DATE_IN_FUTURE -> R.string.form_error_td_date_future
}

/** Display formatter shared with every other date-picker field in this form ([AppDateField] /
 * [org.armman.sakhi.ui.enrollment.components.FormFields]'s "dd MMM yyyy"), so a computed date
 * (e.g. EDD_FROM_LMP) reads the same as the LMP date the Sakhi picked it from, instead of the raw
 * ISO string ([org.armman.sakhi.data.forms.FormComputedFieldEvaluator] stores/returns
 * `LocalDate.toString()`, which is ISO-8601 "yyyy-MM-dd"). Non-date computed values (gestational
 * age, unique_id) simply fail the parse and pass through unchanged.
 */
private val ISO_TO_DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())

private fun formatIfIsoDate(value: String): String =
  runCatching { LocalDate.parse(value).format(ISO_TO_DISPLAY_DATE_FORMATTER) }.getOrDefault(value)

/** Inline error line shown under a field whose widget has no native error slot (select, radio,
 * checkbox, geography, media, image, read-only). Matches the [AppTextInputField] error tone. */
@Composable
private fun FieldErrorText(message: String, modifier: Modifier = Modifier) {
  Text(
    text = message,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier = modifier,
  )
}

/**
 * Renders the field widget itself, dispatching on [FormFieldSchema.inputType]. Split out from
 * [DynamicFormField] so the wrapper there can uniformly append an inline [FieldErrorText] for the
 * field types that need one. [serverErrorText] is only consumed by the text/number/date branches
 * (their widgets have a native error slot — see [INLINE_ERROR_INPUT_TYPES]); other branches ignore
 * it and the wrapper renders their trailing error instead.
 */
@Composable
private fun DynamicFormFieldBody(
  field: FormFieldSchema,
  answers: FormAnswers,
  registrationDate: LocalDate,
  singleValue: String,
  multiValue: List<String>,
  mediaCompleted: Boolean,
  capturedImageUri: String?,
  loadOptions: suspend () -> List<FormFieldOption>,
  onSingleAnswer: (String?) -> Unit,
  onMultiAnswer: (List<String>) -> Unit,
  onPlayMedia: () -> Unit,
  onCaptureImage: () -> Unit,
  serverErrorText: String?,
  readOnlyQuestionCodes: Set<String> = emptySet(),
) {
  val ageFromDobEditable = field.questionCode in AGE_FROM_DOB_QUESTION_CODES &&
    !isAgeFromDobReadOnly(answers)
  if (field.questionCode in readOnlyQuestionCodes) {
    // Locked by the host screen, not by the schema — see DynamicFormField's readOnlyQuestionCodes
    // doc. No "Auto-calculated" fallback: an empty value here means missing data, not a pending
    // calculation.
    AppReadOnlyField(label = field.label, value = singleValue)
    return
  }
  if (!ageFromDobEditable &&
    (field.computedFrom != null ||
      field.questionCode in AGE_FROM_DOB_QUESTION_CODES ||
      field.questionCode == TRIMESTER_QUESTION_CODE)
  ) {
    AppReadOnlyField(
      label = field.label,
      value = singleValue.ifBlank { "Auto-calculated" }
        .let { formatIfIsoDate(it) },
    )
    return
  }

  // Geography (and project_name) options come from the backend's `geography`/profile, one unit per
  // level. A single option renders read-only (pre-selected by the ViewModel); multiple falls back
  // to a dropdown. Handled here rather than in the SELECT branch so the read-only/dropdown split
  // stays in one place. See GeographyFieldOptionsResolver.optionsFromVersionGeography.
  if (field.questionCode in GeographyQuestionCodes.ALL) {
    GeographyField(
      field = field,
      selectedValue = singleValue,
      loadOptions = loadOptions,
      onSingleAnswer = onSingleAnswer,
    )
    return
  }

  // Red `*` next to the label of every field the Sakhi must fill in. See [RequiredFieldMarker] for
  // which fields qualify. `placeholder` is intentionally blank (no ghost text duplicating the
  // label inside the field) — see per-field call sites below.
  val required = RequiredFieldMarker.isShownFor(field)

  when (field.inputType) {
    FormFieldInputType.TEXT, FormFieldInputType.TEXT_GEO -> {
      // Beneficiary name questions take letters and spaces only (form spec S.No 19) — special
      // characters are filtered out as they're typed or pasted, so they can never reach the
      // answer. Every other TEXT field (address, RCH number, …) keeps the raw pass-through.
      // See BeneficiaryNameRule.
      val isName = BeneficiaryNameRule.appliesTo(field.questionCode)
      // Unreachable by typing thanks to the filter above; this covers a value that got in another
      // way (older draft, backend-restored answer) so the Sakhi sees *why* Next is blocked rather
      // than facing a disabled button with no explanation.
      val localError = if (isName && !BeneficiaryNameRule.isValid(singleValue)) {
        stringResource(R.string.enrollment_error_name_chars)
      } else {
        null
      }
      AppTextInputField(
        label = field.label,
        placeholder = "",
        value = singleValue,
        onValueChange = { new ->
          onSingleAnswer(if (isName) BeneficiaryNameRule.sanitize(new) else new)
        },
        // Server error wins on submit; once the Sakhi edits the field its server error is cleared
        // (ViewModel.setAnswer), so the local hint takes over again — no double error.
        errorText = serverErrorText ?: localError,
        required = required,
      )
    }

    FormFieldInputType.NUMBER -> {
      // `mobile_number` is a plain `number` in the schema but must be exactly 10 digits — cap input
      // and validate here (see MobileNumberRule). Other number fields keep the numericRange check.
      val isMobile = field.questionCode == MobileNumberRule.QUESTION_CODE
      val localError = when {
        isMobile ->
          if (singleValue.isNotBlank() && !MobileNumberRule.isComplete(singleValue)) {
            stringResource(R.string.enrollment_error_mobile)
          } else {
            null
          }
        singleValue.isNotBlank() && !FormNumericRangeValidator.isWithinRange(field.numericRange, singleValue) ->
          // A missing `min` reads as 0, which is accurate here: the input filter accepts digits
          // only, so no negative value can reach this field anyway.
          stringResource(
            R.string.enrollment_error_number_range,
            field.numericRange?.min?.toInt() ?: 0,
            field.numericRange?.max?.toInt() ?: 0,
          )
        else -> null
      }
      // Spec digit cap (e.g. household members = "2 digit"), so out-of-length values can't be typed
      // at all; the range message above covers right-length-but-out-of-range entries.
      val maxDigits = FormNumericInputRule.maxDigits(field)
      val allowsDecimal = field.questionCode in DECIMAL_NUMBER_QUESTION_CODES
      AppTextInputField(
        label = field.label,
        placeholder = "",
        value = singleValue,
        onValueChange = { new ->
          // Decimal fields keep digits AND a single "." (first one wins — any later "." is
          // dropped rather than replacing/duplicating it); every other number field stays
          // digits-only exactly as before.
          val sanitized = if (allowsDecimal) {
            val sb = StringBuilder()
            var seenDot = false
            for (c in new) {
              when {
                c.isDigit() -> sb.append(c)
                c == '.' && !seenDot -> {
                  seenDot = true
                  sb.append(c)
                }
              }
            }
            sb.toString()
          } else {
            new.filter { it.isDigit() }
          }
          val capped = when {
            isMobile -> sanitized.take(MobileNumberRule.REQUIRED_DIGITS)
            // maxDigits counts digits in numericRange.max's WHOLE number (e.g. 2 for a 0.5..15
            // range) — correct for an integer field, but would wrongly truncate a legitimate
            // "12.5" (3 digit characters) to "12.". Decimal fields skip this cap and rely on
            // exceedsMax below instead, which parses the full value as a Double and already
            // blocks anything over `max` no matter how it got there.
            !allowsDecimal && maxDigits != null -> sanitized.take(maxDigits)
            else -> sanitized
          }
          // The digit cap alone lets a right-length-but-out-of-range value through (e.g. "16" in
          // a 2..15 field needs only 2 digits, same as "15"). Reject that keystroke outright
          // instead of letting it in and only flagging it via localError below — see
          // FormNumericRangeValidator.exceedsMax for why this can never block a legitimate entry.
          val next = if (FormNumericRangeValidator.exceedsMax(field.numericRange, capped)) {
            singleValue
          } else {
            capped
          }
          onSingleAnswer(next)
        },
        keyboardType = if (allowsDecimal) KeyboardType.Decimal else KeyboardType.Number,
        // Server error wins on submit; once the Sakhi edits the field its server error is cleared
        // (ViewModel.setAnswer), so the local range/mobile hint takes over again — no double error.
        errorText = serverErrorText ?: localError,
        required = required,
      )
    }

    FormFieldInputType.DATE -> {
      // Spec-driven bounds (DOB age range, LMP window, registration date not future) — the picker
      // won't offer an out-of-range date, and any value that got in another way (older draft,
      // backend-restored answer) shows the matching message. Server error still wins.
      val bounds = FormDateRuleset.boundsFor(field.questionCode, answers, registrationDate)
      val localError = FormDateRuleset
        .violationFor(field.questionCode, answers, registrationDate)
        ?.let { stringResource(it.messageRes()) }
      AppDateField(
        label = field.label,
        placeholder = "",
        value = singleValue.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        onDateSelected = { date -> onSingleAnswer(date.toString()) },
        errorText = serverErrorText ?: localError,
        required = required,
        minDate = bounds?.min,
        maxDate = bounds?.max,
        displayPattern = if (field.questionCode == DATE_OF_VISIT_QUESTION_CODE) "dd-MM-yyyy" else "dd MMM yyyy",
      )
    }

    FormFieldInputType.SELECT -> {
      var options by remember(field.questionCode) { mutableStateOf<List<FormFieldOption>>(emptyList()) }
      LaunchedEffect(field.questionCode, answers) { options = loadOptions() }
      AppDropdownField(
        label = field.label,
        placeholder = "",
        options = options.map { it.label },
        selectedIndex = options.indexOfFirst { it.valueCode == singleValue }.takeIf { it >= 0 },
        onSelected = { index -> onSingleAnswer(options.getOrNull(index)?.valueCode) },
        required = required,
      )
    }

    FormFieldInputType.RADIO ->
      if (field.questionCode in CONSENT_CHECKBOX_QUESTION_CODES) {
        // Design: consent affirmations are single checkboxes, not Yes/No radios. Value stays
        // "yes"/"no" so the submission payload is unchanged (see CONSENT_CHECKBOX_QUESTION_CODES).
        AppSingleCheckbox(
          label = field.label,
          checked = singleValue == VALUE_YES,
          onCheckedChange = { checked -> onSingleAnswer(if (checked) VALUE_YES else VALUE_NO) },
          required = required,
        )
      } else {
        var options by remember(field.questionCode) { mutableStateOf<List<FormFieldOption>>(emptyList()) }
        LaunchedEffect(field.questionCode, answers) { options = loadOptions() }
        AppRadioGroup(
          label = field.label,
          options = options.map { it.label },
          selectedIndex = options.indexOfFirst { it.valueCode == singleValue }.takeIf { it >= 0 },
          onSelected = { index -> onSingleAnswer(options.getOrNull(index)?.valueCode) },
          required = required,
        )
      }

    FormFieldInputType.MULTISELECT, FormFieldInputType.MULTISELECT_DATE -> {
      var options by remember(field.questionCode) { mutableStateOf<List<FormFieldOption>>(emptyList()) }
      LaunchedEffect(field.questionCode, answers) { options = loadOptions() }
      val checkedIndices = options.withIndex()
        .filter { (_, option) -> option.valueCode in multiValue }
        .map { it.index }
        .toSet()
      AppCheckboxGroup(
        label = field.label,
        options = options.map { it.label },
        checkedIndices = checkedIndices,
        onToggle = { index ->
          val code = options.getOrNull(index)?.valueCode ?: return@AppCheckboxGroup
          val updated = if (code in multiValue) multiValue - code else multiValue + code
          onMultiAnswer(updated)
        },
        // Spec row 43 (and others sharing the identical dev note — see
        // FormMultiSelectExclusivity's doc): "No known condition"/"Don't know" disable every other
        // option and vice versa. A code the Sakhi has already checked is never disabled, so
        // unchecking is always possible.
        enabled = { index ->
          val code = options.getOrNull(index)?.valueCode
          code == null || !FormMultiSelectExclusivity.isDisabled(field.questionCode, code, multiValue)
        },
        required = required,
      )
    }

    FormFieldInputType.MEDIA ->
      // Design intent (Consent tab): the welcome VIDEO is an embedded player box under a "Welcome
      // to Arogyasakhi Program!" heading, while the audio guidelines are a "Play …" pill button.
      // All are the same `media` input_type in the schema, so they're told apart by question_code —
      // the only distinguishing signal available.
      when (field.questionCode) {
        QUESTION_CODE_CONSENT_AUDIO ->
          MediaPlayButton(label = field.label, completed = mediaCompleted, onPlay = onPlayMedia)
        QUESTION_CODE_CONSENT_VIDEO ->
          MediaField(
            label = stringResource(R.string.enrollment_consent_welcome),
            completed = mediaCompleted,
            onPlay = onPlayMedia,
          )
        else ->
          MediaField(label = field.label, completed = mediaCompleted, onPlay = onPlayMedia)
      }

    FormFieldInputType.IMAGE ->
      ConsentPhotoButton(
        label = field.label,
        captured = capturedImageUri != null,
        imageUri = capturedImageUri,
        onCapture = onCaptureImage,
      )

    FormFieldInputType.UNKNOWN ->
      Text(
        // A future input_type the app doesn't understand yet — surfaced visibly rather than
        // silently skipped, so a genuine gap gets noticed during testing, not after release.
        text = "Unsupported field type for \"${field.label}\" (${field.inputTypeRaw}) — app update needed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
  }
}

/**
 * Geography / project_name field. Options are the backend-provided units for this level (via
 * [DynamicMotherRegistrationViewModel.optionsFor] → `optionsFromVersionGeography`). Exactly one
 * option (the normal case — the Sakhi's single assigned unit, already pre-selected in the answers)
 * renders read-only so it can't be left blank or changed to a wrong value; more than one renders a
 * dropdown. Zero options (a backend gap: this level missing from `geography`) renders as an empty,
 * submit-gating dropdown rather than silently passing — the same "notice the gap" behavior as a
 * choice field with no way to populate it.
 *
 * Either rendering shows the red `*` marker when [RequiredFieldMarker.isShownFor] says so — the
 * field is still mandatory in the single-option read-only case, it's just already satisfied.
 */
@Composable
private fun GeographyField(
  field: FormFieldSchema,
  selectedValue: String,
  loadOptions: suspend () -> List<FormFieldOption>,
  onSingleAnswer: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var options by remember(field.questionCode) { mutableStateOf<List<FormFieldOption>>(emptyList()) }
  LaunchedEffect(field.questionCode) { options = loadOptions() }

  val single = options.singleOrNull()
  if (single != null) {
    // Pre-selected and unchangeable, but still shows the `*` when required — there's nothing for
    // the Sakhi to do, but the marker communicates the value is mandatory (matches the dropdown
    // branch below).
    AppReadOnlyField(
      label = field.label,
      value = single.label,
      modifier = modifier,
      required = RequiredFieldMarker.isShownFor(field),
    )
  } else {
    AppDropdownField(
      label = field.label,
      placeholder = "",
      options = options.map { it.label },
      selectedIndex = options.indexOfFirst { it.valueCode == selectedValue }.takeIf { it >= 0 },
      onSelected = { index -> onSingleAnswer(options.getOrNull(index)?.valueCode) },
      modifier = modifier,
      required = RequiredFieldMarker.isShownFor(field),
    )
  }
}

@Composable
private fun MediaField(label: String, completed: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    // titleLarge (bold) so the welcome heading reads as a section title, matching the design.
    Text(text = label, style = MaterialTheme.typography.titleLarge, color = NeutralG400)
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .padding(top = 4.dp)
        .fillMaxWidth()
        .height(Dimens.ConsentVideoHeight)
        .background(VideoPlaceholderSurface, RoundedCornerShape(Dimens.TileRadius))
        .clickable(onClick = onPlay),
    ) {
      if (completed) {
        Icon(
          painter = painterResource(R.drawable.ic_check_circle_small),
          contentDescription = null,
          tint = StatusSuccess,
          modifier = Modifier.size(32.dp),
        )
      } else {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(Dimens.ConsentPlayBadge)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_play_circle),
            contentDescription = label,
            tint = White,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

/** Audio `media` field as an outlined lavender pill (design: "Play consent guidelines ▶"),
 * distinct from the video player box. Trailing icon flips to a success check once played. */
@Composable
private fun MediaPlayButton(label: String, completed: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
  SecondaryButton(
    text = label,
    onClick = onPlay,
    trailingIcon = painterResource(
      if (completed) R.drawable.ic_check_circle_small else R.drawable.ic_play_circle,
    ),
    modifier = modifier,
  )
}

/** `image` field as an outlined lavender pill (design: "Take photo of consent form 📷"). Trailing
 * icon flips to a success check once a photo is captured. Same [onCapture] camera flow as before —
 * only the visual changed from a bordered box to a pill. Once captured, a thumbnail preview of the
 * photo renders below the pill (bharath, 2026-08-07 — Sakhis had no way to confirm what was
 * captured; tapping the pill silently reopened the camera instead of showing anything). Preview
 * only, no clear/retake action on the thumbnail itself — retake still happens via the pill. */
@Composable
private fun ConsentPhotoButton(
  label: String,
  captured: Boolean,
  imageUri: String?,
  onCapture: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SecondaryButton(
      text = label,
      onClick = onCapture,
      trailingIcon = painterResource(
        if (captured) R.drawable.ic_check_circle_small else R.drawable.ic_camera,
      ),
    )
    if (imageUri != null) {
      ConsentPhotoPreview(uri = imageUri)
    }
  }
}

/** Thumbnail preview of the just-captured consent photo, decoded off the main thread from the
 * `content://` FileProvider URI written by the capture flow (see [DynamicFormField]'s host
 * screens). No image-loading library (Coil/Glide) is in the project yet, so this decodes directly
 * via [BitmapFactory] rather than adding a dependency for a single thumbnail. */
@Composable
private fun ConsentPhotoPreview(uri: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(uri) {
    bitmap = withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
          BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
      }.getOrNull()
    }
  }
  val loaded = bitmap
  if (loaded != null) {
    Image(
      bitmap = loaded,
      contentDescription = stringResource(R.string.enrollment_consent_photo_preview),
      contentScale = ContentScale.Crop,
      modifier = modifier
        .size(Dimens.ConsentPhotoPreviewSize)
        .clip(RoundedCornerShape(Dimens.TileRadius)),
    )
  }
}
