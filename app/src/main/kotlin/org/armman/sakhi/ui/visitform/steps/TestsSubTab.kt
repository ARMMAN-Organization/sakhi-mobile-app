package org.armman.sakhi.ui.visitform.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.components.AppReadOnlyField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.visitform.VisitDataFieldError
import org.armman.sakhi.ui.visitform.VisitDataState

/**
 * Tests sub-tab (Excel Q12–16, Q23–32): anthropometry & diagnostic
 * measurements. Q14 (BMI) is auto-computed (read-only); Q30–32 only appear
 * from 20 weeks gestation (Category 5).
 */
@Composable
fun TestsSubTab(
  state: VisitDataState,
  actions: VisitDataActions,
  modifier: Modifier = Modifier,
) {
  val banner = state.showValidationBanner
  val scrollCoordinator = rememberMissingFieldScrollCoordinator(banner)

  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = modifier.fillMaxWidth(),
  ) {
    if (state.isHeightEditable) {
      VisitCountField(
        label = stringResource(R.string.visit_form_vd_height),
        value = state.heightCm,
        onChange = actions.onHeight,
        error = state.heightError,
        modifier = Modifier.scrollToIfMissing(state.heightError != null, banner, scrollCoordinator),
      )
    } else {
      AppReadOnlyField(
        label = stringResource(R.string.visit_form_vd_height),
        value = "${state.heightLockedCm} cm",
      )
    }
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_weight),
      value = state.weightKg,
      onChange = actions.onWeight,
      error = state.weightError,
      modifier = Modifier.scrollToIfMissing(state.weightError != null, banner, scrollCoordinator),
    )
    AppReadOnlyField(
      label = stringResource(R.string.visit_form_vd_bmi),
      value = state.bmi?.let { "%.1f".format(it) } ?: "",
    )
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_muac),
      value = state.muacCm,
      onChange = actions.onMuac,
      error = state.muacError,
      modifier = Modifier.scrollToIfMissing(state.muacError != null, banner, scrollCoordinator),
    )
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_bp_systolic),
      value = state.bpSystolic,
      onChange = actions.onBpSystolic,
      error = state.bpSystolicError,
      modifier = Modifier.scrollToIfMissing(state.bpSystolicError != null, banner, scrollCoordinator),
    )
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_bp_diastolic),
      value = state.bpDiastolic,
      onChange = actions.onBpDiastolic,
      error = state.bpDiastolicError,
      modifier = Modifier.scrollToIfMissing(state.bpDiastolicError != null, banner, scrollCoordinator),
    )
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_temperature),
      value = state.temperatureF,
      onChange = actions.onTemperature,
      error = state.temperatureError,
      modifier = Modifier.scrollToIfMissing(state.temperatureError != null, banner, scrollCoordinator),
    )
    AppTextField(
      value = state.hemoglobin,
      onValueChange = actions.onHemoglobin,
      label = stringResource(R.string.visit_form_vd_hemoglobin),
      placeholder = stringResource(R.string.visit_form_vd_count_placeholder),
      modifier = Modifier.scrollToIfMissing(state.hemoglobinError != null, banner, scrollCoordinator),
      errorText = visitErrorText(state.hemoglobinError),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    if (state.hemoglobinError == VisitDataFieldError.HB_CONFIRM_NEEDED) {
      SecondaryButton(
        text = stringResource(R.string.visit_form_hb_confirm_action),
        onClick = actions.onConfirmHemoglobin,
      )
    }
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_last_meal),
      value = state.lastMealHours,
      onChange = actions.onLastMealHours,
      error = null,
    )
    VisitCountField(
      label = stringResource(R.string.visit_form_vd_blood_glucose),
      value = state.bloodGlucose,
      onChange = actions.onBloodGlucose,
      error = state.bloodGlucoseError,
      modifier = Modifier.scrollToIfMissing(state.bloodGlucoseError != null, banner, scrollCoordinator),
    )
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_urine_test),
      arrayRes = R.array.visit_form_urine_test_options,
      codes = state.urineTest,
      onToggle = actions.onUrineTest,
      error = visitRequiredText(state.urineTest.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.urineTest.isEmpty(), banner, scrollCoordinator),
    )
    if (state.showFetalAndFundalFields) {
      VisitRadioField(
        label = stringResource(R.string.visit_form_vd_fetal_movements),
        arrayRes = R.array.visit_form_fetal_movements_options,
        code = state.fetalMovements,
        onSelected = actions.onFetalMovements,
        banner = banner,
        modifier = Modifier.scrollToIfMissing(state.fetalMovements == null, banner, scrollCoordinator),
      )
      VisitCountField(
        label = stringResource(R.string.visit_form_vd_fetal_heart_rate),
        value = state.fetalHeartRate,
        onChange = actions.onFetalHeartRate,
        error = state.fetalHeartRateError,
        modifier = Modifier.scrollToIfMissing(state.fetalHeartRateError != null, banner, scrollCoordinator),
      )
      VisitCountField(
        label = stringResource(R.string.visit_form_vd_fundal_height),
        value = state.fundalHeightCm,
        onChange = actions.onFundalHeight,
        error = state.fundalHeightError,
        modifier = Modifier.scrollToIfMissing(state.fundalHeightError != null, banner, scrollCoordinator),
      )
    }
    if (banner) VisitValidationBanner()
  }
}
