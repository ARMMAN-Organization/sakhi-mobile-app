package org.armman.sakhi.ui.enrollment.components

import android.app.DatePickerDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FieldShape = RoundedCornerShape(8.dp)

/** Shared label + optional error scaffolding for enrollment form fields. */
@Composable
private fun FieldFrame(
  label: String,
  errorText: String?,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG400,
      modifier = Modifier.padding(bottom = 4.dp),
    )
    content()
    if (errorText != null) {
      Text(
        text = errorText,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Composable
private fun fieldBorder(isError: Boolean) =
  if (isError) MaterialTheme.colorScheme.error else NeutralG75

/**
 * Dropdown selector styled like the design's "Select …" fields: outlined box,
 * placeholder, trailing chevron, options in a DropdownMenu.
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
) {
  var expanded by remember { mutableStateOf(false) }
  FieldFrame(label = label, errorText = errorText, modifier = modifier) {
    Box {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .height(Dimens.SmallButtonHeight)
          .clip(FieldShape)
          .background(White)
          .border(1.dp, fieldBorder(errorText != null), FieldShape)
          .clickable(enabled = enabled && options.isNotEmpty()) { expanded = true }
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
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

/**
 * Date field per the design's "Enter LMP" pattern: outlined box with a
 * trailing calendar icon, opening the platform date picker.
 */
@Composable
fun AppDateField(
  label: String,
  placeholder: String,
  value: LocalDate?,
  onDateSelected: (LocalDate) -> Unit,
  modifier: Modifier = Modifier,
  errorText: String? = null,
) {
  val context = LocalContext.current
  val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }
  val openPicker = {
    val seed = value ?: LocalDate.now()
    DatePickerDialog(
      context,
      { _, year, month, day -> onDateSelected(LocalDate.of(year, month + 1, day)) },
      seed.year,
      seed.monthValue - 1,
      seed.dayOfMonth,
    ).show()
  }
  FieldFrame(label = label, errorText = errorText, modifier = modifier) {
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
) {
  FieldFrame(label = label, errorText = errorText, modifier = modifier) {
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
) {
  FieldFrame(label = label, errorText = errorText, modifier = modifier) {
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
) {
  FieldFrame(label = label, errorText = errorText, modifier = modifier) {
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

/** A single radio/checkbox row — icon + label, purple when selected. */
@Composable
private fun SelectableRow(
  label: String,
  selected: Boolean,
  iconSelected: Int,
  iconUnselected: Int,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
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
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = when {
        !enabled -> NeutralG75
        selected -> MaterialTheme.colorScheme.primary
        else -> NeutralG400
      },
    )
  }
}

/** Read-only derived value (EDD, gestational age) in the field style. */
@Composable
fun AppReadOnlyField(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  FieldFrame(label = label, errorText = null, modifier = modifier) {
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
