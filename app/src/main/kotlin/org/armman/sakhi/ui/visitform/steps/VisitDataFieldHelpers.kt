package org.armman.sakhi.ui.visitform.steps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.enrollment.components.AppCheckboxGroup
import org.armman.sakhi.ui.enrollment.components.AppDropdownField
import org.armman.sakhi.ui.enrollment.components.AppRadioGroup
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.visitform.VisitDataFieldError

/** Section header shared by all three Visit Data sub-tabs. */
@Composable
internal fun VisitSectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleLarge,
    color = NeutralG400,
    modifier = Modifier.padding(top = Dimens.SmallSpacing),
  )
}

@Composable
internal fun VisitCountField(
  label: String,
  value: String,
  onChange: (String) -> Unit,
  error: VisitDataFieldError?,
  modifier: Modifier = Modifier,
) {
  AppTextField(
    value = value,
    onValueChange = onChange,
    label = label,
    placeholder = stringResource(R.string.visit_form_vd_count_placeholder),
    modifier = modifier,
    errorText = visitErrorText(error),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
  )
}

@Composable
internal fun VisitDropdownField(
  label: String,
  arrayRes: Int,
  code: Int?,
  onSelected: (Int) -> Unit,
  banner: Boolean,
  modifier: Modifier = Modifier,
) {
  AppDropdownField(
    label = label,
    placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
    options = stringArrayResource(arrayRes).toList(),
    selectedIndex = code?.minus(1),
    onSelected = { onSelected(it + 1) },
    modifier = modifier,
    errorText = visitRequiredText(code == null, banner),
  )
}

@Composable
internal fun VisitRadioField(
  label: String,
  arrayRes: Int,
  code: Int?,
  onSelected: (Int) -> Unit,
  banner: Boolean,
  modifier: Modifier = Modifier,
) {
  AppRadioGroup(
    label = label,
    options = stringArrayResource(arrayRes).toList(),
    selectedIndex = code?.minus(1),
    onSelected = { onSelected(it + 1) },
    modifier = modifier,
    errorText = visitRequiredText(code == null, banner),
  )
}

@Composable
internal fun VisitYesNoField(
  label: String,
  value: Boolean?,
  onSelected: (Boolean) -> Unit,
  banner: Boolean,
  modifier: Modifier = Modifier,
) {
  AppRadioGroup(
    label = label,
    options = stringArrayResource(R.array.enrollment_yesno_options).toList(),
    selectedIndex = value?.let { if (it) 0 else 1 },
    onSelected = { onSelected(it == 0) },
    modifier = modifier,
    errorText = visitRequiredText(value == null, banner),
    horizontal = true,
  )
}

/** Single standalone checkbox row (e.g. Td dose flags) — no option array. */
@Composable
internal fun VisitCheckRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) },
  ) {
    Icon(
      painter = painterResource(
        if (checked) R.drawable.ic_checkbox_selected else R.drawable.ic_checkbox,
      ),
      contentDescription = null,
      tint = if (checked) MaterialTheme.colorScheme.primary else Color.Unspecified,
      modifier = Modifier.size(24.dp),
    )
    Text(text = label, style = MaterialTheme.typography.bodyLarge, color = NeutralG400)
  }
}

@Composable
internal fun VisitCheckboxField(
  label: String,
  arrayRes: Int,
  codes: Set<Int>,
  onToggle: (Int) -> Unit,
  error: String?,
  modifier: Modifier = Modifier,
) {
  AppCheckboxGroup(
    label = label,
    options = stringArrayResource(arrayRes).toList(),
    checkedIndices = codes.map { it - 1 }.toSet(),
    onToggle = { onToggle(it + 1) },
    modifier = modifier,
    errorText = error,
  )
}

/**
 * Coordinates "scroll to the first invalid field" per sub-tab: only the
 * first field to claim it for a given validation failure actually scrolls —
 * later fields with `missing = true` no-op, so the user lands on the very
 * first thing blocking them rather than the last.
 */
internal class MissingFieldScrollCoordinator {
  private var claimed = false
  fun reset() { claimed = false }
  fun tryClaim(): Boolean {
    if (claimed) return false
    claimed = true
    return true
  }
}

/**
 * One coordinator per sub-tab composable, re-armed every time a blocked
 * Next/Summary tap raises [banner] — call this once, before any fields, so
 * its reset effect registers ahead of the fields' claim effects below it.
 */
@Composable
internal fun rememberMissingFieldScrollCoordinator(banner: Boolean): MissingFieldScrollCoordinator {
  val coordinator = remember { MissingFieldScrollCoordinator() }
  LaunchedEffect(banner) { if (banner) coordinator.reset() }
  return coordinator
}

/**
 * Scrolls this field into view the moment [missing] and [banner] are both
 * true, but only for the first field in composition order to claim
 * [coordinator] — see [rememberMissingFieldScrollCoordinator].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.scrollToIfMissing(
  missing: Boolean,
  banner: Boolean,
  coordinator: MissingFieldScrollCoordinator,
): Modifier {
  val requester = remember { BringIntoViewRequester() }
  LaunchedEffect(banner, missing) {
    if (banner && missing && coordinator.tryClaim()) {
      requester.bringIntoView()
    }
  }
  return this.bringIntoViewRequester(requester)
}

@Composable
internal fun visitErrorText(error: VisitDataFieldError?): String? = when (error) {
  null -> null
  VisitDataFieldError.REQUIRED -> stringResource(R.string.visit_form_error_required)
  VisitDataFieldError.RANGE -> stringResource(R.string.visit_form_error_range)
  VisitDataFieldError.DATE_FUTURE -> stringResource(R.string.visit_form_error_date_future)
  VisitDataFieldError.DATE_TOO_EARLY -> stringResource(R.string.visit_form_error_date_too_early)
  VisitDataFieldError.HB_CONFIRM_NEEDED -> stringResource(R.string.visit_form_hb_confirm_prompt)
}

@Composable
internal fun visitRequiredText(missing: Boolean, banner: Boolean): String? =
  if (missing && banner) stringResource(R.string.visit_form_error_required) else null

/** Red "Complete all necessary fields" banner, shared across sub-tabs. */
@Composable
internal fun VisitValidationBanner() {
  val shape = RoundedCornerShape(Dimens.TileRadius)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier
      .background(RiskHighSurface, shape)
      .border(1.dp, RiskHigh, shape)
      .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ChipSpacing),
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_warning_circle),
      contentDescription = null,
      tint = RiskHigh,
      modifier = Modifier.size(20.dp),
    )
    Text(
      text = stringResource(R.string.visit_form_vd_banner),
      style = MaterialTheme.typography.titleMedium,
      color = RiskHigh,
    )
  }
}
