package org.armman.sakhi.ui.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.AGE_FROM_DOB_QUESTION_CODES
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormNumericRangeValidator
import org.armman.sakhi.data.forms.GeographyQuestionCodes
import org.armman.sakhi.data.forms.MobileNumberRule
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

/** Consent-audio field code — the one `media` field the design renders as a "Play …" pill button
 * instead of the video player box. See the MEDIA branch in [DynamicFormField]. */
private const val QUESTION_CODE_CONSENT_AUDIO = "consent_audio"

/** Consent welcome-video field code — the `media` field rendered as the embedded player box under
 * the design's "Welcome to Arogyasakhi Program!" heading (which replaces the plain schema label). */
private const val QUESTION_CODE_CONSENT_VIDEO = "arogya_sakhi_video"

private const val VALUE_YES = "yes"
private const val VALUE_NO = "no"

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

/**
 * Renders one [FormFieldSchema] generically, dispatching on [FormFieldSchema.inputType] — the
 * heart of CR-018: a field the backend adds or changes shows up here automatically, no per-field
 * Compose code to write.
 *
 * Known, deliberate simplification: `multiselect_date` (currently only
 * `has_the_women_received_td_dose`) renders as a plain multi-select checkbox group, without a
 * per-option date picker. The real schema implies each selected option should also capture a
 * date (Td-1/Td-2/Booster dates), but building a bespoke multi-date-per-checkbox UI wasn't
 * included in this pass — flagged here rather than silently dropped; a follow-up should extend
 * this case once confirmed how those per-option dates should be captured/submitted.
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
 * The DOB-derived age field ([AGE_FROM_DOB_QUESTION_CODES]) is treated the same way even though the
 * live schema doesn't (yet) set `computedFrom` on it — see that constant's doc. Without this, it
 * would render as a normal editable number box whose typed value gets silently clobbered by
 * `recomputeDerivedFields`'s stopgap every time any other answer changes — exactly the broken
 * pattern this whole read-only branch exists to avoid.
 */
@Composable
fun DynamicFormField(
  field: FormFieldSchema,
  answers: FormAnswers,
  mediaCompleted: Boolean,
  capturedImageUri: String?,
  loadOptions: suspend () -> List<FormFieldOption>,
  onSingleAnswer: (String?) -> Unit,
  onMultiAnswer: (List<String>) -> Unit,
  onPlayMedia: () -> Unit,
  onCaptureImage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val singleValue = answers.valueOf(field.questionCode).orEmpty()
  val multiValue = answers.multiValueOf(field.questionCode)

  if (field.computedFrom != null || field.questionCode in AGE_FROM_DOB_QUESTION_CODES) {
    AppReadOnlyField(
      label = field.label,
      value = singleValue.ifBlank { "Auto-calculated" },
      modifier = modifier,
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
      modifier = modifier,
    )
    return
  }

  when (field.inputType) {
    FormFieldInputType.TEXT, FormFieldInputType.TEXT_GEO ->
      AppTextInputField(
        label = field.label,
        placeholder = field.label,
        value = singleValue,
        onValueChange = onSingleAnswer,
        modifier = modifier,
      )

    FormFieldInputType.NUMBER -> {
      // `mobile_number` is a plain `number` in the schema but must be exactly 10 digits — cap input
      // and validate here (see MobileNumberRule). Other number fields keep the numericRange check.
      val isMobile = field.questionCode == MobileNumberRule.QUESTION_CODE
      val error = when {
        isMobile ->
          if (singleValue.isNotBlank() && !MobileNumberRule.isComplete(singleValue)) {
            stringResource(R.string.enrollment_error_mobile)
          } else {
            null
          }
        singleValue.isNotBlank() && !FormNumericRangeValidator.isWithinRange(field.numericRange, singleValue) -> {
          val range = field.numericRange
          "Must be between ${range?.min?.toInt()} and ${range?.max?.toInt()}"
        }
        else -> null
      }
      AppTextInputField(
        label = field.label,
        placeholder = field.label,
        value = singleValue,
        onValueChange = { new ->
          val digits = new.filter { it.isDigit() }
          onSingleAnswer(if (isMobile) digits.take(MobileNumberRule.REQUIRED_DIGITS) else digits)
        },
        keyboardType = KeyboardType.Number,
        errorText = error,
        modifier = modifier,
      )
    }

    FormFieldInputType.DATE ->
      AppDateField(
        label = field.label,
        placeholder = field.label,
        value = singleValue.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        onDateSelected = { date -> onSingleAnswer(date.toString()) },
        modifier = modifier,
      )

    FormFieldInputType.SELECT -> {
      var options by remember(field.questionCode) { mutableStateOf<List<FormFieldOption>>(emptyList()) }
      LaunchedEffect(field.questionCode, answers) { options = loadOptions() }
      AppDropdownField(
        label = field.label,
        placeholder = field.label,
        options = options.map { it.label },
        selectedIndex = options.indexOfFirst { it.valueCode == singleValue }.takeIf { it >= 0 },
        onSelected = { index -> onSingleAnswer(options.getOrNull(index)?.valueCode) },
        modifier = modifier,
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
          modifier = modifier,
        )
      } else {
        var options by remember(field.questionCode) { mutableStateOf<List<FormFieldOption>>(emptyList()) }
        LaunchedEffect(field.questionCode, answers) { options = loadOptions() }
        AppRadioGroup(
          label = field.label,
          options = options.map { it.label },
          selectedIndex = options.indexOfFirst { it.valueCode == singleValue }.takeIf { it >= 0 },
          onSelected = { index -> onSingleAnswer(options.getOrNull(index)?.valueCode) },
          modifier = modifier,
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
        modifier = modifier,
      )
    }

    FormFieldInputType.MEDIA ->
      // Design intent (Consent tab): the welcome VIDEO is an embedded player box under a "Welcome
      // to Arogyasakhi Program!" heading, while the audio guidelines are a "Play …" pill button.
      // All are the same `media` input_type in the schema, so they're told apart by question_code —
      // the only distinguishing signal available.
      when (field.questionCode) {
        QUESTION_CODE_CONSENT_AUDIO ->
          MediaPlayButton(label = field.label, completed = mediaCompleted, onPlay = onPlayMedia, modifier = modifier)
        QUESTION_CODE_CONSENT_VIDEO ->
          MediaField(
            label = stringResource(R.string.enrollment_consent_welcome),
            completed = mediaCompleted,
            onPlay = onPlayMedia,
            modifier = modifier,
          )
        else ->
          MediaField(label = field.label, completed = mediaCompleted, onPlay = onPlayMedia, modifier = modifier)
      }

    FormFieldInputType.IMAGE ->
      ConsentPhotoButton(
        label = field.label,
        captured = capturedImageUri != null,
        onCapture = onCaptureImage,
        modifier = modifier,
      )

    FormFieldInputType.UNKNOWN ->
      Text(
        // A future input_type the app doesn't understand yet — surfaced visibly rather than
        // silently skipped, so a genuine gap gets noticed during testing, not after release.
        text = "Unsupported field type for \"${field.label}\" (${field.inputTypeRaw}) — app update needed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
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
    AppReadOnlyField(label = field.label, value = single.label, modifier = modifier)
  } else {
    AppDropdownField(
      label = field.label,
      placeholder = field.label,
      options = options.map { it.label },
      selectedIndex = options.indexOfFirst { it.valueCode == selectedValue }.takeIf { it >= 0 },
      onSelected = { index -> onSingleAnswer(options.getOrNull(index)?.valueCode) },
      modifier = modifier,
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
 * only the visual changed from a bordered box to a pill. */
@Composable
private fun ConsentPhotoButton(label: String, captured: Boolean, onCapture: () -> Unit, modifier: Modifier = Modifier) {
  SecondaryButton(
    text = label,
    onClick = onCapture,
    trailingIcon = painterResource(
      if (captured) R.drawable.ic_check_circle_small else R.drawable.ic_camera,
    ),
    modifier = modifier,
  )
}
