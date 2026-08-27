package org.armman.sakhi.ui.enrollment.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.ErrorBorderSoft
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FieldShape = RoundedCornerShape(8.dp)

/** The required-field marker appended to a label, e.g. "Mother's name *". */
private const val RequiredMarker = " *"

/**
 * A field label with a red `*` appended when [required].
 *
 * Built as an [androidx.compose.ui.text.AnnotatedString] rather than two `Text`s so the marker
 * wraps with the label instead of being pushed onto its own line by a long Marathi label, and so
 * the whole thing stays one node for TalkBack. `contentDescription` spells out "required" because
 * a bare `*` is announced as "star", which carries no meaning to a screen-reader user.
 */
@Composable
private fun FieldLabelText(label: String, required: Boolean, modifier: Modifier = Modifier) {
  val errorColor = MaterialTheme.colorScheme.error
  val text = remember(label, required, errorColor) {
    buildAnnotatedString {
      append(label)
      if (required) {
        withStyle(SpanStyle(color = errorColor)) { append(RequiredMarker) }
      }
    }
  }
  val description = if (required) {
    stringResource(R.string.field_required_content_description, label)
  } else {
    null
  }
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = NeutralG400,
    modifier = modifier.then(
      if (description != null) {
        Modifier.semantics { contentDescription = description }
      } else {
        Modifier
      },
    ),
  )
}

/**
 * Shared label + optional error scaffolding for enrollment form fields.
 *
 * [errorBelowLabel] controls where [errorText] renders relative to [content]:
 * - `false` (default, single-row fields like a text/dropdown/date box): error sits directly under
 *   the box, since the box itself is the whole "question".
 * - `true` (multi-row [AppCheckboxGroup] answer lists): error sits directly under the question
 *   label instead, *above* the options — otherwise, with a long options list, "This field is
 *   required" ends up far below the question, after the last checkbox, which reads as attached to
 *   the last option rather than to the question itself.
 */
@Composable
private fun FieldFrame(
  label: String,
  errorText: String?,
  modifier: Modifier = Modifier,
  required: Boolean = false,
  errorBelowLabel: Boolean = false,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    FieldLabelText(
      label = label,
      required = required,
      modifier = Modifier.padding(bottom = 4.dp),
    )
    if (errorBelowLabel && errorText != null) {
      FieldErrorText(errorText, modifier = Modifier.padding(bottom = 4.dp))
    }
    content()
    if (!errorBelowLabel && errorText != null) {
      FieldErrorText(errorText, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

@Composable
private fun FieldErrorText(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.error,
    modifier = modifier,
  )
}

@Composable
private fun fieldBorder(isError: Boolean) =
  // ErrorBorderSoft, not MaterialTheme.colorScheme.error (=RiskHigh) — the full-strength red read
  // as too heavy for a 1dp field outline repeated down a whole form (2026-08 design feedback).
  if (isError) ErrorBorderSoft else NeutralG75

/**
 * Dropdown selector styled like the design's "Select …" fields: outlined box,
 * placeholder, trailing chevron, options in a DropdownMenu.
 *
 * Two behaviors on top of the plain Material3 [DropdownMenu]:
 * - If [options] resolves to exactly one item and nothing is selected yet, it's auto-selected —
 *   a single-option list isn't really a choice, so the Sakhi shouldn't have to open the menu and
 *   tap its only row (common on the geography cascade, where a Sakhi's area often has only one
 *   PHC/sub-centre). Guarded on [enabled] and `selectedIndex == null` so it never overrides an
 *   existing selection or fires on a disabled/dependent field.
 * - The menu's width is pinned to the field's own measured width, instead of [DropdownMenu]'s
 *   default of wrapping each item's text — without this a short single-word option (e.g. a
 *   district name) renders as a narrow floating chip instead of matching the outlined field
 *   underneath it.
 */
@Composable
fun AppDropdownField(
  label: String,
  placeholder: String,
  options: List<String>,
  selectedIndex: Int?,
  onSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  enabled: Boolean = true,
  required: Boolean = false,
) {
  var expanded by remember { mutableStateOf(false) }
  var fieldWidthPx by remember { mutableIntStateOf(0) }
  val density = LocalDensity.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  LaunchedEffect(options, enabled, selectedIndex) {
    if (enabled && selectedIndex == null && options.size == 1) {
      onSelected(0)
    }
  }

  FieldFrame(label = label, errorText = errorText, modifier = modifier, required = required) {
    Box {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .height(Dimens.SmallButtonHeight)
          .onSizeChanged { fieldWidthPx = it.width }
          .clip(FieldShape)
          .background(White)
          .border(1.dp, fieldBorder(errorText != null), FieldShape)
          .clickable(enabled = enabled && options.isNotEmpty()) {
            // A field further down the form (a dropdown, date, checkbox, radio — none of them
            // real text input) doesn't take Compose focus away from whatever AppTextField the
            // Sakhi was just typing in merely by being tapped, since focus only transfers between
            // focusable nodes. Left alone, that still-focused text field can bring the keyboard
            // back regardless of what was actually tapped — see the matching note on AppDateField
            // and SelectableRow (2026-08 QA).
            focusManager.clearFocus()
            keyboardController?.hide()
            expanded = true
          }
          .padding(horizontal = Dimens.ItemSpacing),
      ) {
        Text(
          text = selectedIndex?.let(options::getOrNull) ?: placeholder,
          style = MaterialTheme.typography.bodyLarge,
          color = if (selectedIndex != null) NeutralG400 else NeutralG100,
          modifier = Modifier.weight(1f),
        )
        Icon(
          painter = painterResource(R.drawable.ic_caret_down),
          contentDescription = null,
          tint = NeutralG400,
          modifier = Modifier.size(20.dp),
        )
      }
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.width(with(density) { fieldWidthPx.toDp() }),
      ) {
        options.forEachIndexed { index, option ->
          DropdownMenuItem(
            text = { Text(option, style = MaterialTheme.typography.bodyLarge) },
            onClick = {
              expanded = false
              onSelected(index)
            },
          )
        }
      }
    }
  }
}

/** Nearest date within [min]..[max] (either bound optional), so a picker seeded from an
 * out-of-range value still opens on a month the user can actually pick in. */
private fun LocalDate.coerceIntoRange(min: LocalDate?, max: LocalDate?): LocalDate = when {
  min != null && isBefore(min) -> min
  max != null && isAfter(max) -> max
  else -> this
}

private fun LocalDate.startOfDayMillis(): Long =
  atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun LocalDate.endOfDayMillis(): Long =
  atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Date field per the design's "Enter LMP" pattern: outlined box with a
 * trailing calendar icon, opening the platform date picker.
 *
 * [minDate]/[maxDate] bound what the picker will let the Sakhi choose (both inclusive, both
 * optional). This is prevention only — a caller that bounds the picker must still validate the
 * stored value, since a draft saved by an older build or an answer restored from the backend never
 * passed through this dialog. See [org.armman.sakhi.data.forms.FormDateRuleset].
 */
@Composable
fun AppDateField(
  label: String,
  placeholder: String,
  value: LocalDate?,
  onDateSelected: (LocalDate) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  required: Boolean = false,
  minDate: LocalDate? = null,
  maxDate: LocalDate? = null,
  /** Every other date field in the app shares the default "dd MMM yyyy" — pass a different
   * pattern only for a field that's been explicitly asked to look different (e.g. the Visit
   * Form's "Date of visit", which wants dd-mm-yyyy; bharath, 2026-08-07). */
  displayPattern: String = "dd MMM yyyy",
) {
  val context = LocalContext.current
  val formatter = remember(displayPattern) { DateTimeFormatter.ofPattern(displayPattern, Locale.getDefault()) }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val openPicker = {
    // See the matching note on AppDropdownField: clear focus off any still-focused text field
    // above this one so the keyboard doesn't reappear once the dialog closes.
    focusManager.clearFocus()
    keyboardController?.hide()
    // Clamp the seed into range so the dialog never opens on a month the bounds forbid (which
    // reads as a broken picker: every day greyed out).
    val seed = (value ?: maxDate ?: LocalDate.now()).coerceIntoRange(minDate, maxDate)
    val dialog = DatePickerDialog(
      context,
      { _, year, month, day -> onDateSelected(LocalDate.of(year, month + 1, day)) },
      seed.year,
      seed.monthValue - 1,
      seed.dayOfMonth,
    )
    // Bounds must be applied before show(). Android's DatePicker takes epoch millis, and a bound of
    // "today" has to include all of today — hence start-of-day for min and end-of-day for max.
    minDate?.let { dialog.datePicker.minDate = it.startOfDayMillis() }
    maxDate?.let { dialog.datePicker.maxDate = it.endOfDayMillis() }
    dialog.show()
  }
  FieldFrame(label = label, errorText = errorText, modifier = modifier, required = required) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.SmallButtonHeight)
        .clip(FieldShape)
        .background(White)
        .border(1.dp, fieldBorder(errorText != null), FieldShape)
        .clickable(onClick = openPicker)
        .padding(horizontal = Dimens.ItemSpacing),
    ) {
      Text(
        text = value?.format(formatter) ?: placeholder,
        style = MaterialTheme.typography.bodyLarge,
        color = if (value != null) NeutralG400 else NeutralG100,
        modifier = Modifier.weight(1f),
      )
      Icon(
        painter = painterResource(R.drawable.ic_calendar_blank),
        contentDescription = null,
        tint = NeutralG400,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/**
 * Formats [time] per the platform's "hh:mm am/pm" convention (lowercase am/pm, 12-hour clock) —
 * e.g. `LocalTime.of(14, 45)` -> `"02:45 pm"`. Backend does not validate the `TIME` input_type's
 * answer format server-side; this is purely a client-side consistency choice.
 *
 * `DateTimeFormatter`'s `"a"` pattern letter renders "AM"/"PM" (uppercase) in essentially every
 * locale, so the formatted string is explicitly lowercased afterwards rather than relying on any
 * locale trick to produce lowercase directly.
 */
private val TIME_OF_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)

/** Parses the "hh:mm am/pm" string [formatTimeOfDay] produces, back into a [LocalTime] — needed
 * because the stored answer's am/pm marker is lowercase, but [DateTimeFormatter]'s `"a"` pattern
 * letter is case-sensitive by default (it only matches "AM"/"PM" as-is). Case-insensitive parsing
 * is opt-in via [java.time.format.DateTimeFormatterBuilder.parseCaseInsensitive], applied here
 * only to the parser, not [TIME_OF_DAY_FORMATTER] itself (which must keep producing uppercase
 * before the explicit `.lowercase()` in [formatTimeOfDay] — a case-insensitive formatter would
 * still render using its locale's normal case). */
private val TIME_OF_DAY_PARSER: DateTimeFormatter =
  java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("hh:mm a").toFormatter(Locale.US)

fun formatTimeOfDay(time: LocalTime): String = time.format(TIME_OF_DAY_FORMATTER).lowercase(Locale.US)

fun parseTimeOfDayOrNull(text: String): LocalTime? = runCatching { LocalTime.parse(text, TIME_OF_DAY_PARSER) }.getOrNull()

/**
 * Time field mirroring [AppDateField]'s outlined-box-with-trailing-icon pattern, opening the
 * platform time picker instead of the date picker. No [FormDateRuleset]-equivalent bounds
 * checking exists for time fields, so unlike [AppDateField] this has no min/max — the schema's
 * only TIME field so far (`maternal_death_time`) carries no such constraint.
 */
@Composable
fun AppTimeField(
  label: String,
  placeholder: String,
  value: LocalTime?,
  onTimeSelected: (LocalTime) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  required: Boolean = false,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val openPicker = {
    // See the matching note on AppDateField/AppDropdownField: clear focus off any still-focused
    // text field above this one so the keyboard doesn't reappear once the dialog closes.
    focusManager.clearFocus()
    keyboardController?.hide()
    val seed = value ?: LocalTime.now()
    val dialog = TimePickerDialog(
      context,
      { _, hour, minute -> onTimeSelected(LocalTime.of(hour, minute)) },
      seed.hour,
      seed.minute,
      false,
    )
    dialog.show()
  }
  FieldFrame(label = label, errorText = errorText, modifier = modifier, required = required) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.SmallButtonHeight)
        .clip(FieldShape)
        .background(White)
        .border(1.dp, fieldBorder(errorText != null), FieldShape)
        .clickable(onClick = openPicker)
        .padding(horizontal = Dimens.ItemSpacing),
    ) {
      Text(
        text = value?.let { formatTimeOfDay(it) } ?: placeholder,
        style = MaterialTheme.typography.bodyLarge,
        color = if (value != null) NeutralG400 else NeutralG100,
        modifier = Modifier.weight(1f),
      )
      Icon(
        painter = painterResource(R.drawable.ic_calendar_blank),
        contentDescription = null,
        tint = NeutralG400,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/**
 * Mobile number field per the design: one outline containing a "+91" prefix
 * cell, a vertical divider, and the number input.
 */
@Composable
fun AppPhoneField(
  label: String,
  placeholder: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  required: Boolean = false,
) {
  FieldFrame(label = label, errorText = errorText, modifier = modifier, required = required) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.SmallButtonHeight)
        .clip(FieldShape)
        .background(White)
        .border(1.dp, fieldBorder(errorText != null), FieldShape),
    ) {
      Text(
        text = stringResource(R.string.enrollment_phone_prefix),
        style = MaterialTheme.typography.bodyLarge,
        color = NeutralG400,
        modifier = Modifier.padding(horizontal = Dimens.ChipSpacing),
      )
      VerticalDivider(
        thickness = 1.dp,
        color = NeutralG75,
        modifier = Modifier.fillMaxHeight(),
      )
      BasicTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter(Char::isDigit).take(10)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = NeutralG400),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        decorationBox = { inner ->
          Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
              Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = NeutralG100,
              )
            }
            inner()
          }
        },
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = Dimens.ChipSpacing),
      )
    }
  }
}

/**
 * Free-text/number entry in the same outlined-box style as [AppPhoneField], minus the fixed
 * prefix — used by CR-018's generic dynamic-form renderer for `text`/`text_geo`/`number` fields,
 * where the label, placeholder, and keyboard type all come from the schema rather than being
 * hardcoded per field like the rest of this file's components.
 */
@Composable
fun AppTextInputField(
  label: String,
  placeholder: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  keyboardType: KeyboardType = KeyboardType.Text,
  required: Boolean = false,
) {
  FieldFrame(label = label, errorText = errorText, modifier = modifier, required = required) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.SmallButtonHeight)
        .clip(FieldShape)
        .background(White)
        .border(1.dp, fieldBorder(errorText != null), FieldShape)
        .padding(horizontal = Dimens.ChipSpacing),
      contentAlignment = Alignment.CenterStart,
    ) {
      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = NeutralG400),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        decorationBox = { inner ->
          if (value.isEmpty()) {
            Text(text = placeholder, style = MaterialTheme.typography.bodyLarge, color = NeutralG100)
          }
          inner()
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * Radio group in the enrollment field style: label + a set of single-select
 * options. [horizontal] lays Yes/No side by side; otherwise options stack.
 * [selectedIndex] is 0-based (callers convert to/from 1-based codes).
 */
@Composable
fun AppRadioGroup(
  label: String,
  options: List<String>,
  selectedIndex: Int?,
  onSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  horizontal: Boolean = false,
  required: Boolean = false,
) {
  FieldFrame(label = label, errorText = errorText, modifier = modifier, required = required) {
    if (horizontal) {
      Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding)) {
        options.forEachIndexed { index, option ->
          SelectableRow(
            label = option,
            selected = selectedIndex == index,
            iconSelected = R.drawable.ic_radio_selected,
            iconUnselected = R.drawable.ic_radio_unselected,
            onClick = { onSelected(index) },
          )
        }
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing)) {
        options.forEachIndexed { index, option ->
          SelectableRow(
            label = option,
            selected = selectedIndex == index,
            iconSelected = R.drawable.ic_radio_selected,
            iconUnselected = R.drawable.ic_radio_unselected,
            onClick = { onSelected(index) },
          )
        }
      }
    }
  }
}

/**
 * Multi-select checkbox group. [checkedIndices] are 0-based; [onToggle] is
 * called with the tapped index and the caller (ViewModel) applies any
 * mutual-exclusion rules. [enabled] can grey out options blocked by an
 * exclusive selection.
 */
@Composable
fun AppCheckboxGroup(
  label: String,
  options: List<String>,
  checkedIndices: Set<Int>,
  onToggle: (Int) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  enabled: (Int) -> Boolean = { true },
  required: Boolean = false,
) {
  FieldFrame(
    label = label,
    errorText = errorText,
    modifier = modifier,
    required = required,
    errorBelowLabel = true,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing)) {
      options.forEachIndexed { index, option ->
        SelectableRow(
          label = option,
          selected = index in checkedIndices,
          iconSelected = R.drawable.ic_checkbox_selected,
          iconUnselected = R.drawable.ic_checkbox,
          onClick = { onToggle(index) },
          enabled = enabled(index),
        )
      }
    }
  }
}

/**
 * A single standalone checkbox (box on the left, label on the right) — for a yes/no affirmation
 * the design shows as one checkbox rather than a Yes/No pair (e.g. the Consent tab's "Ensure that
 * the beneficiary" items). [checked] drives the box; [onCheckedChange] reports the new state.
 */
@Composable
fun AppSingleCheckbox(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  required: Boolean = false,
) {
  Row(modifier = modifier.fillMaxWidth()) {
    SelectableRow(
      label = label,
      selected = checked,
      iconSelected = R.drawable.ic_checkbox_selected,
      iconUnselected = R.drawable.ic_checkbox,
      onClick = { onCheckedChange(!checked) },
      required = required,
    )
  }
}

/** A single radio/checkbox row — icon + label, purple when selected. */
@Composable
private fun SelectableRow(
  label: String,
  selected: Boolean,
  iconSelected: Int,
  iconUnselected: Int,
  onClick: () -> Unit,
  enabled: Boolean = true,
  /** Only ever true for a standalone [AppSingleCheckbox] that *is* a required field. Options inside
   * a radio/checkbox group are never individually required — the group's own label carries the
   * marker — so this stays false there. */
  required: Boolean = false,
) {
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val labelColor = when {
    !enabled -> NeutralG75
    selected -> MaterialTheme.colorScheme.primary
    else -> NeutralG400
  }
  val errorColor = MaterialTheme.colorScheme.error
  val labelText = remember(label, required, errorColor) {
    buildAnnotatedString {
      append(label)
      if (required) {
        withStyle(SpanStyle(color = errorColor)) { append(RequiredMarker) }
      }
    }
  }
  val description = if (required) {
    stringResource(R.string.field_required_content_description, label)
  } else {
    null
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier.clickable(enabled = enabled) {
      // See the matching note on AppDropdownField/AppDateField: this checkbox/radio row isn't a
      // text input, but tapping it doesn't by itself take Compose focus away from a still-focused
      // AppTextField above it, so the keyboard can otherwise pop back up for no visible reason.
      focusManager.clearFocus()
      keyboardController?.hide()
      onClick()
    },
  ) {
    Icon(
      painter = painterResource(if (selected) iconSelected else iconUnselected),
      contentDescription = null,
      tint = when {
        !enabled -> NeutralG75
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Unspecified
      },
      modifier = Modifier.size(24.dp),
    )
    Text(
      text = labelText,
      style = MaterialTheme.typography.bodyLarge,
      color = labelColor,
      modifier = if (description != null) {
        Modifier.semantics { contentDescription = description }
      } else {
        Modifier
      },
    )
  }
}

/** Read-only derived value (EDD, gestational age) in the field style. [required] shows the red
 * `*` marker next to the label — used by `GeographyField` for a single-option geography field,
 * which is still mandatory even though there's nothing left for the Sakhi to pick. */
@Composable
fun AppReadOnlyField(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  required: Boolean = false,
) {
  FieldFrame(label = label, errorText = null, modifier = modifier, required = required) {
    Box(
      contentAlignment = Alignment.CenterStart,
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.SmallButtonHeight)
        .clip(FieldShape)
        .background(White)
        .border(1.dp, NeutralG75, FieldShape)
        .padding(horizontal = Dimens.ItemSpacing),
    ) {
      Text(text = value, style = MaterialTheme.typography.bodyLarge, color = NeutralG400)
    }
  }
}
