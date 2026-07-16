package org.armman.sakhi.ui.enrollment.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.armman.sakhi.ui.enrollment.HealthFieldError
import org.armman.sakhi.ui.enrollment.HealthHistoryState
import org.armman.sakhi.ui.enrollment.components.AppCheckboxGroup
import org.armman.sakhi.ui.enrollment.components.AppDateField
import org.armman.sakhi.ui.enrollment.components.AppDropdownField
import org.armman.sakhi.ui.enrollment.components.AppRadioGroup
import org.armman.sakhi.ui.enrollment.components.AppReadOnlyField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import java.time.LocalDate

/** Callbacks for the Health History step — flat, mirroring the VM API. */
data class HealthHistoryActions(
  val onPlannedPregnancy: (Int) -> Unit,
  val onTookTreatment: (Boolean) -> Unit,
  val onTreatmentType: (Int) -> Unit,
  val onRchStatus: (Int) -> Unit,
  val onRchNumber: (String) -> Unit,
  val onAncStatus: (Int) -> Unit,
  val onAnc1Date: (LocalDate) -> Unit,
  val onAncCondition: (Int) -> Unit,
  val onTdNone: (Boolean) -> Unit,
  val onTd1: (Boolean) -> Unit,
  val onTd1Date: (LocalDate) -> Unit,
  val onTd2: (Boolean) -> Unit,
  val onTd2Date: (LocalDate) -> Unit,
  val onTdBooster: (Boolean) -> Unit,
  val onTdBoosterDate: (LocalDate) -> Unit,
  val onGravida: (String) -> Unit,
  val onPara: (String) -> Unit,
  val onLivingChildren: (String) -> Unit,
  val onAbortions: (String) -> Unit,
  val onStillBirths: (String) -> Unit,
  val onDeadChildren: (String) -> Unit,
  val onLastPregnancyWhen: (Int) -> Unit,
  val onDeliveryComplication: (Int) -> Unit,
  val onLastDeliveryDuration: (Int) -> Unit,
  val onLastDeliveryType: (Int) -> Unit,
  val onLastDeliveryPlace: (Int) -> Unit,
  val onLastDeliveryOutcome: (Int) -> Unit,
  val onBirthWeight: (Int) -> Unit,
  val onSelfCondition: (Int) -> Unit,
  val onLongTermMed: (Int) -> Unit,
  val onSickleCell: (Int) -> Unit,
  val onSubstanceUse: (Int) -> Unit,
  val onFamilyHistory: (Boolean) -> Unit,
  val onFamilyCondition: (Int) -> Unit,
  val onMalnutrition: (Int) -> Unit,
  val onRemarks: (String) -> Unit,
)

/**
 * Health History step (Excel Q35–65). No Figma frame exists — this follows the
 * Personal Info field patterns and spacing tokens. Conditional fields appear
 * inline; risk tagging / referral actions are out of scope.
 */
@Composable
fun HealthHistoryStep(
  state: HealthHistoryState,
  actions: HealthHistoryActions,
  modifier: Modifier = Modifier,
) {
  val banner = state.showValidationBanner
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(R.string.enrollment_hh_instruction),
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )

    // --- Current Pregnancy (Q35–44) ---
    SectionHeader(stringResource(R.string.enrollment_hh_section_current))
    AppReadOnlyField(
      label = stringResource(R.string.enrollment_hh_trimester),
      value = trimesterLabel(state.trimester),
    )
    RadioField(
      label = stringResource(R.string.enrollment_hh_planned),
      arrayRes = R.array.enrollment_planned_options,
      code = state.plannedPregnancy,
      onSelected = actions.onPlannedPregnancy,
      banner = banner,
    )
    YesNoField(
      label = stringResource(R.string.enrollment_hh_took_treatment),
      value = state.tookTreatment,
      onSelected = actions.onTookTreatment,
      banner = banner,
    )
    if (state.showTreatmentType) {
      DropdownField(
        label = stringResource(R.string.enrollment_hh_treatment_type),
        arrayRes = R.array.enrollment_treatment_type_options,
        code = state.treatmentType,
        onSelected = actions.onTreatmentType,
        banner = banner,
      )
    }
    DropdownField(
      label = stringResource(R.string.enrollment_hh_rch_status),
      arrayRes = R.array.enrollment_rch_options,
      code = state.rchStatus,
      onSelected = actions.onRchStatus,
      banner = banner,
    )
    if (state.showRchNumber) {
      AppTextField(
        value = state.rchNumber,
        onValueChange = actions.onRchNumber,
        label = stringResource(R.string.enrollment_hh_rch_number),
        placeholder = stringResource(R.string.enrollment_hh_rch_number_placeholder),
        errorText = requiredText(state.rchNumber.isBlank(), banner),
      )
    }
    RadioField(
      label = stringResource(R.string.enrollment_hh_anc_status),
      arrayRes = R.array.enrollment_anc_status_options,
      code = state.ancStatus,
      onSelected = actions.onAncStatus,
      banner = banner,
    )
    if (state.showAnc1Details) {
      AppDateField(
        label = stringResource(R.string.enrollment_hh_anc1_date),
        placeholder = stringResource(R.string.enrollment_hh_anc1_date_placeholder),
        value = state.anc1Date,
        onDateSelected = actions.onAnc1Date,
        errorText = errorText(state.anc1DateError),
      )
      CheckboxField(
        label = stringResource(R.string.enrollment_hh_anc_conditions),
        arrayRes = R.array.enrollment_anc_condition_options,
        codes = state.ancConditions,
        onToggle = actions.onAncCondition,
        error = requiredText(state.ancConditions.isEmpty(), banner),
      )
    }
    TdDoses(state = state, actions = actions)

    // --- Past Obstetric History (Q45–50) ---
    SectionHeader(stringResource(R.string.enrollment_hh_section_obstetric))
    CountField(
      label = stringResource(R.string.enrollment_hh_gravida),
      value = state.gravida,
      onChange = actions.onGravida,
      error = errorText(state.gravidaError) ?: errorText(state.gravidaTotalError),
    )
    CountField(stringResource(R.string.enrollment_hh_para), state.para, actions.onPara, errorText(state.paraError))
    CountField(
      stringResource(R.string.enrollment_hh_living_children),
      state.livingChildren,
      actions.onLivingChildren,
      errorText(state.livingChildrenError),
    )
    CountField(stringResource(R.string.enrollment_hh_abortions), state.abortions, actions.onAbortions, errorText(state.abortionsError))
    CountField(stringResource(R.string.enrollment_hh_still_births), state.stillBirths, actions.onStillBirths, errorText(state.stillBirthsError))
    CountField(stringResource(R.string.enrollment_hh_dead_children), state.deadChildren, actions.onDeadChildren, errorText(state.deadChildrenError))

    // --- Last Pregnancy (Q51–57), only if Gravida > 1 ---
    if (state.showLastPregnancy) {
      SectionHeader(stringResource(R.string.enrollment_hh_section_last_pregnancy))
      RadioField(
        stringResource(R.string.enrollment_hh_last_pregnancy_when),
        R.array.enrollment_last_pregnancy_when_options,
        state.lastPregnancyWhen, actions.onLastPregnancyWhen, banner,
      )
      CheckboxField(
        label = stringResource(R.string.enrollment_hh_delivery_complications),
        arrayRes = R.array.enrollment_delivery_complication_options,
        codes = state.deliveryComplications,
        onToggle = actions.onDeliveryComplication,
        error = requiredText(state.deliveryComplications.isEmpty(), banner),
      )
      DropdownField(
        stringResource(R.string.enrollment_hh_delivery_duration),
        R.array.enrollment_delivery_duration_options,
        state.lastDeliveryDuration, actions.onLastDeliveryDuration, banner,
      )
      DropdownField(
        stringResource(R.string.enrollment_hh_delivery_type),
        R.array.enrollment_delivery_type_options,
        state.lastDeliveryType, actions.onLastDeliveryType, banner,
      )
      DropdownField(
        stringResource(R.string.enrollment_hh_delivery_place),
        R.array.enrollment_delivery_place_options,
        state.lastDeliveryPlace, actions.onLastDeliveryPlace, banner,
      )
      RadioField(
        stringResource(R.string.enrollment_hh_delivery_outcome),
        R.array.enrollment_delivery_outcome_options,
        state.lastDeliveryOutcome, actions.onLastDeliveryOutcome, banner,
      )
      if (state.showBirthWeight) {
        RadioField(
          stringResource(R.string.enrollment_hh_birth_weight),
          R.array.enrollment_birth_weight_options,
          state.birthWeight, actions.onBirthWeight, banner,
        )
      }
    }

    // --- Self & Family Medical History (Q58–65) ---
    SectionHeader(stringResource(R.string.enrollment_hh_section_medical))
    CheckboxField(
      label = stringResource(R.string.enrollment_hh_self_conditions),
      arrayRes = R.array.enrollment_self_condition_options,
      codes = state.selfConditions,
      onToggle = actions.onSelfCondition,
      error = requiredText(state.selfConditions.isEmpty(), banner),
    )
    CheckboxField(
      label = stringResource(R.string.enrollment_hh_long_term_meds),
      arrayRes = R.array.enrollment_long_term_med_options,
      codes = state.longTermMeds,
      onToggle = actions.onLongTermMed,
      error = requiredText(state.longTermMeds.isEmpty(), banner),
    )
    DropdownField(
      stringResource(R.string.enrollment_hh_sickle_cell),
      R.array.enrollment_sickle_cell_options,
      state.sickleCell, actions.onSickleCell, banner,
    )
    CheckboxField(
      label = stringResource(R.string.enrollment_hh_substance_use),
      arrayRes = R.array.enrollment_substance_use_options,
      codes = state.substanceUse,
      onToggle = actions.onSubstanceUse,
      error = requiredText(state.substanceUse.isEmpty(), banner),
    )
    YesNoField(
      label = stringResource(R.string.enrollment_hh_family_history),
      value = state.familyHistory,
      onSelected = actions.onFamilyHistory,
      banner = banner,
    )
    if (state.showFamilyConditions) {
      CheckboxField(
        label = stringResource(R.string.enrollment_hh_family_conditions),
        arrayRes = R.array.enrollment_family_condition_options,
        codes = state.familyConditions,
        onToggle = actions.onFamilyCondition,
        error = requiredText(state.familyConditions.isEmpty(), banner),
      )
    }
    // Q64 malnutrition is optional (no "Mandatory" in Excel) → no banner gating.
    DropdownField(
      stringResource(R.string.enrollment_hh_malnutrition),
      R.array.enrollment_malnutrition_options,
      state.malnutrition, actions.onMalnutrition, banner = false,
    )
    AppTextField(
      value = state.remarks,
      onValueChange = actions.onRemarks,
      label = stringResource(R.string.enrollment_hh_remarks),
      placeholder = stringResource(R.string.enrollment_hh_remarks_placeholder),
      singleLine = false,
      minLines = 3,
    )

    if (banner) HealthValidationBanner()
  }
}

// --- Section building blocks -------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleLarge,
    color = NeutralG400,
    modifier = Modifier.padding(top = Dimens.SmallSpacing),
  )
}

@Composable
private fun CountField(label: String, value: String, onChange: (String) -> Unit, error: String?) {
  AppTextField(
    value = value,
    onValueChange = onChange,
    label = label,
    placeholder = stringResource(R.string.enrollment_hh_count_placeholder),
    errorText = error,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
  )
}

@Composable
private fun DropdownField(
  label: String,
  arrayRes: Int,
  code: Int?,
  onSelected: (Int) -> Unit,
  banner: Boolean,
) {
  AppDropdownField(
    label = label,
    placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
    options = stringArrayResource(arrayRes).toList(),
    selectedIndex = code?.minus(1),
    onSelected = { onSelected(it + 1) },
    errorText = requiredText(code == null, banner),
  )
}

@Composable
private fun RadioField(
  label: String,
  arrayRes: Int,
  code: Int?,
  onSelected: (Int) -> Unit,
  banner: Boolean,
) {
  AppRadioGroup(
    label = label,
    options = stringArrayResource(arrayRes).toList(),
    selectedIndex = code?.minus(1),
    onSelected = { onSelected(it + 1) },
    errorText = requiredText(code == null, banner),
  )
}

@Composable
private fun YesNoField(label: String, value: Boolean?, onSelected: (Boolean) -> Unit, banner: Boolean) {
  AppRadioGroup(
    label = label,
    options = stringArrayResource(R.array.enrollment_yesno_options).toList(),
    selectedIndex = value?.let { if (it) 0 else 1 },
    onSelected = { onSelected(it == 0) },
    errorText = requiredText(value == null, banner),
    horizontal = true,
  )
}

@Composable
private fun CheckboxField(label: String, arrayRes: Int, codes: Set<Int>, onToggle: (Int) -> Unit, error: String?) {
  AppCheckboxGroup(
    label = label,
    options = stringArrayResource(arrayRes).toList(),
    checkedIndices = codes.map { it - 1 }.toSet(),
    onToggle = { onToggle(it + 1) },
    errorText = error,
  )
}

@Composable
private fun TdDoses(state: HealthHistoryState, actions: HealthHistoryActions) {
  Column(verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing)) {
    Text(
      text = stringResource(R.string.enrollment_hh_td),
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG400,
    )
    TdCheckbox(stringResource(R.string.enrollment_hh_td_none), state.tdNone, actions.onTdNone)
    TdDoseRow(stringResource(R.string.enrollment_hh_td1), state.td1, actions.onTd1, state.td1Date, actions.onTd1Date)
    TdDoseRow(stringResource(R.string.enrollment_hh_td2), state.td2, actions.onTd2, state.td2Date, actions.onTd2Date)
    TdDoseRow(
      stringResource(R.string.enrollment_hh_td_booster),
      state.tdBooster, actions.onTdBooster, state.tdBoosterDate, actions.onTdBoosterDate,
    )
    errorText(state.tdDateError)?.let {
      Text(text = it, style = MaterialTheme.typography.labelLarge, color = RiskHigh)
    }
  }
}

@Composable
private fun TdDoseRow(
  label: String,
  checked: Boolean,
  onChecked: (Boolean) -> Unit,
  date: LocalDate?,
  onDate: (LocalDate) -> Unit,
) {
  TdCheckbox(label, checked, onChecked)
  if (checked) {
    AppDateField(
      label = label,
      placeholder = stringResource(R.string.enrollment_hh_td_date_placeholder),
      value = date,
      onDateSelected = onDate,
    )
  }
}

@Composable
private fun TdCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onChecked(!checked) },
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
private fun HealthValidationBanner() {
  val shape = RoundedCornerShape(Dimens.TileRadius)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier
      .fillMaxWidth()
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
      text = stringResource(R.string.enrollment_pi_banner),
      style = MaterialTheme.typography.titleMedium,
      color = RiskHigh,
    )
  }
}

// --- Helpers -----------------------------------------------------------------

@Composable
private fun errorText(error: HealthFieldError?): String? = when (error) {
  null -> null
  HealthFieldError.REQUIRED -> stringResource(R.string.enrollment_error_required)
  HealthFieldError.COUNT_RANGE -> stringResource(R.string.enrollment_hh_error_count)
  HealthFieldError.ANC1_DATE_INVALID -> stringResource(R.string.enrollment_hh_error_anc1_date)
  HealthFieldError.TD_DATE_FUTURE -> stringResource(R.string.enrollment_hh_error_td_future)
  HealthFieldError.TD_DATE_ORDER -> stringResource(R.string.enrollment_hh_error_td_order)
  HealthFieldError.PARA_EXCEEDS_GRAVIDA -> stringResource(R.string.enrollment_hh_error_para)
  HealthFieldError.ABORTIONS_EXCEED_GRAVIDA -> stringResource(R.string.enrollment_hh_error_abortions)
  HealthFieldError.DEAD_EXCEEDS_LIVING -> stringResource(R.string.enrollment_hh_error_dead)
  HealthFieldError.GRAVIDA_TOTAL_MISMATCH -> stringResource(R.string.enrollment_hh_error_gravida_total)
}

@Composable
private fun requiredText(missing: Boolean, banner: Boolean): String? =
  if (missing && banner) stringResource(R.string.enrollment_error_required) else null

@Composable
private fun trimesterLabel(trimester: Int?): String = when (trimester) {
  1 -> stringResource(R.string.enrollment_hh_trimester_first)
  2 -> stringResource(R.string.enrollment_hh_trimester_second)
  3 -> stringResource(R.string.enrollment_hh_trimester_third)
  else -> ""
}
