package org.armman.sakhi.ui.visitform.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.components.AppDateField
import org.armman.sakhi.ui.enrollment.components.AppReadOnlyField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.visitform.VisitDataState

/**
 * History sub-tab: Visit Tracking (Q1–4), Pregnancy Dating (Q5–11), Td doses
 * (Q33), Supplements & Adherence (Q34–39), and Birth Preparedness / Mental
 * Health / USG / Counselling (Q40–56). The largest of the three sub-tabs —
 * grouped into its own section headers matching the Excel/Figma ordering.
 */
@Composable
fun HistorySubTab(
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
    // --- Visit Tracking (Q1–4) ---------------------------------------------
    VisitSectionHeader(stringResource(R.string.visit_form_vd_section_tracking))
    AppDateField(
      label = stringResource(R.string.visit_form_vd_visit_date),
      placeholder = stringResource(R.string.visit_form_vd_td_date_placeholder),
      value = state.visitDate,
      onDateSelected = actions.onVisitDate,
      modifier = Modifier.scrollToIfMissing(state.visitDateError != null, banner, scrollCoordinator),
      errorText = visitErrorText(state.visitDateError),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_met_beneficiary),
      value = state.metBeneficiary,
      onSelected = actions.onMetBeneficiary,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.metBeneficiary == null, banner, scrollCoordinator),
    )
    if (state.showNotMetReason) {
      VisitDropdownField(
        label = stringResource(R.string.visit_form_vd_not_met_reason),
        arrayRes = R.array.visit_form_not_met_reason_options,
        code = state.notMetReason,
        onSelected = actions.onNotMetReason,
        banner = banner,
        modifier = Modifier.scrollToIfMissing(state.notMetReason == null, banner, scrollCoordinator),
      )
    }

    // --- Pregnancy Dating (Q5–11) -------------------------------------------
    VisitSectionHeader(stringResource(R.string.visit_form_vd_section_dating))
    AppTextField(
      value = state.rchNumber,
      onValueChange = actions.onRchNumber,
      label = stringResource(R.string.visit_form_vd_rch_number),
      placeholder = stringResource(R.string.visit_form_vd_count_placeholder),
      modifier = Modifier.scrollToIfMissing(state.rchNumber.isBlank(), banner, scrollCoordinator),
      errorText = visitRequiredText(state.rchNumber.isBlank(), banner),
    )
    AppDateField(
      label = stringResource(R.string.visit_form_vd_lmp),
      placeholder = stringResource(R.string.visit_form_vd_td_date_placeholder),
      value = state.lmp,
      onDateSelected = actions.onLmp,
      modifier = Modifier.scrollToIfMissing(state.lmp == null, banner, scrollCoordinator),
      errorText = visitRequiredText(state.lmp == null, banner),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_has_sonography),
      value = state.hasSonographyReport,
      onSelected = actions.onHasSonographyReport,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.hasSonographyReport == null, banner, scrollCoordinator),
    )
    if (state.showSonographyUpload) {
      SecondaryButton(
        text = stringResource(R.string.visit_form_vd_sonography_upload),
        onClick = actions.onSonographyPhoto,
        trailingIcon = painterResource(R.drawable.ic_camera),
        height = Dimens.SmallButtonHeight,
        modifier = Modifier.scrollToIfMissing(state.sonographyPhotoUri == null, banner, scrollCoordinator),
      )
      if (state.sonographyPhotoUri != null) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_check_circle_small),
            contentDescription = null,
            tint = StatusSuccess,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = stringResource(R.string.visit_form_vd_sonography_upload),
            style = MaterialTheme.typography.labelLarge,
            color = StatusSuccess,
          )
        }
      }
    }
    AppReadOnlyField(
      label = stringResource(R.string.visit_form_vd_gestational_age),
      value = state.gestationalAgeWeeks?.toString() ?: "",
    )
    AppReadOnlyField(
      label = stringResource(R.string.visit_form_vd_edd),
      value = state.edd?.toString() ?: "",
    )

    // --- Td doses (Q33) ------------------------------------------------------
    VisitSectionHeader(stringResource(R.string.visit_form_vd_td))
    VisitCheckRow(
      label = stringResource(R.string.visit_form_vd_td_none),
      checked = state.tdNone,
      onCheckedChange = actions.onTdNone,
      modifier = Modifier.scrollToIfMissing(
        state.tdSelectionMissing || state.tdDateError != null,
        banner,
        scrollCoordinator,
      ),
    )
    if (!state.tdNone) {
      TdDoseRow(
        label = stringResource(R.string.visit_form_vd_td1),
        checked = state.td1,
        date = state.td1Date,
        onChecked = actions.onTd1,
        onDate = actions.onTd1Date,
      )
      TdDoseRow(
        label = stringResource(R.string.visit_form_vd_td2),
        checked = state.td2,
        date = state.td2Date,
        onChecked = actions.onTd2,
        onDate = actions.onTd2Date,
      )
      TdDoseRow(
        label = stringResource(R.string.visit_form_vd_td_booster),
        checked = state.tdBooster,
        date = state.tdBoosterDate,
        onChecked = actions.onTdBooster,
        onDate = actions.onTdBoosterDate,
      )
      if (state.tdDateError != null) VisitValidationBanner()
    }

    // --- Supplements & Adherence (Q34–39) ------------------------------------
    VisitSectionHeader(stringResource(R.string.visit_form_vd_section_supplements))
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_food_consumed),
      arrayRes = R.array.visit_form_food_options,
      codes = state.foodConsumed24h,
      onToggle = actions.onFoodConsumed,
      error = visitRequiredText(state.foodConsumed24h.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.foodConsumed24h.isEmpty(), banner, scrollCoordinator),
    )
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_food_avoided),
      arrayRes = R.array.visit_form_food_options,
      codes = state.foodAvoided,
      onToggle = actions.onFoodAvoided,
      error = visitRequiredText(state.foodAvoided.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.foodAvoided.isEmpty(), banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_taking_ifa),
      value = state.takingIfa,
      onSelected = actions.onTakingIfa,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.takingIfa == null, banner, scrollCoordinator),
    )
    if (state.showTreatmentIfaCount) {
      VisitCountField(
        label = stringResource(R.string.visit_form_vd_ifa_tablets),
        value = state.ifaTabletsConsumed,
        onChange = actions.onIfaTablets,
        error = state.ifaTabletsError,
        modifier = Modifier.scrollToIfMissing(state.ifaTabletsError != null, banner, scrollCoordinator),
      )
    }
    if (state.showIfaNonConsumptionReasons) {
      VisitCheckboxField(
        label = stringResource(R.string.visit_form_vd_ifa_reasons),
        arrayRes = R.array.visit_form_ifa_reason_options,
        codes = state.ifaNonConsumptionReasons,
        onToggle = actions.onIfaReason,
        error = visitRequiredText(state.ifaNonConsumptionReasons.isEmpty(), banner),
        modifier = Modifier.scrollToIfMissing(state.ifaNonConsumptionReasons.isEmpty(), banner, scrollCoordinator),
      )
    }
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_calcium),
      value = state.calciumTaken,
      onSelected = actions.onCalcium,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.calciumTaken == null, banner, scrollCoordinator),
    )

    // --- Birth Preparedness & Mental Health (Q40–55) -------------------------
    VisitSectionHeader(stringResource(R.string.visit_form_vd_section_birth_prep))
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_visited_facility),
      value = state.visitedFacilitySinceLastVisit,
      onSelected = actions.onVisitedFacility,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(
        state.visitedFacilitySinceLastVisit == null,
        banner,
        scrollCoordinator,
      ),
    )
    if (state.showLastAncVisitDate) {
      AppDateField(
        label = stringResource(R.string.visit_form_vd_last_anc_date),
        placeholder = stringResource(R.string.visit_form_vd_td_date_placeholder),
        value = state.lastAncVisitDate,
        onDateSelected = actions.onLastAncDate,
        modifier = Modifier.scrollToIfMissing(state.lastAncVisitDateError != null, banner, scrollCoordinator),
        errorText = visitErrorText(state.lastAncVisitDateError),
      )
    }
    VisitDropdownField(
      label = stringResource(R.string.visit_form_vd_delivery_place),
      arrayRes = R.array.visit_form_delivery_place_options,
      code = state.advisedDeliveryPlace,
      onSelected = actions.onDeliveryPlace,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.advisedDeliveryPlace == null, banner, scrollCoordinator),
    )
    VisitDropdownField(
      label = stringResource(R.string.visit_form_vd_sickle_cell),
      arrayRes = R.array.visit_form_sickle_cell_options,
      code = state.sickleCell,
      onSelected = actions.onSickleCell,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.sickleCell == null, banner, scrollCoordinator),
    )
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_family_planning),
      arrayRes = R.array.visit_form_family_planning_options,
      codes = state.familyPlanningMethods,
      onToggle = actions.onFamilyPlanning,
      error = visitRequiredText(state.familyPlanningMethods.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.familyPlanningMethods.isEmpty(), banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_feeling_stressed),
      value = state.feelingStressed,
      onSelected = actions.onFeelingStressed,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.feelingStressed == null, banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_family_support),
      value = state.adequateFamilySupport,
      onSelected = actions.onFamilySupport,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.adequateFamilySupport == null, banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_planning_migration),
      value = state.planningMigration,
      onSelected = actions.onPlanningMigration,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.planningMigration == null, banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_usg_done),
      value = state.usgDone,
      onSelected = actions.onUsgDone,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.usgDone == null, banner, scrollCoordinator),
    )
    if (state.showUsgDetails) {
      AppDateField(
        label = stringResource(R.string.visit_form_vd_usg_date),
        placeholder = stringResource(R.string.visit_form_vd_td_date_placeholder),
        value = state.usgDate,
        onDateSelected = actions.onUsgDate,
        modifier = Modifier.scrollToIfMissing(state.usgDateError != null, banner, scrollCoordinator),
        errorText = visitErrorText(state.usgDateError),
      )
      VisitDropdownField(
        label = stringResource(R.string.visit_form_vd_usg_type),
        arrayRes = R.array.visit_form_usg_type_options,
        code = state.usgType,
        onSelected = actions.onUsgType,
        banner = banner,
        modifier = Modifier.scrollToIfMissing(state.usgType == null, banner, scrollCoordinator),
      )
      VisitDropdownField(
        label = stringResource(R.string.visit_form_vd_usg_finding),
        arrayRes = R.array.visit_form_usg_finding_options,
        code = state.usgFinding,
        onSelected = actions.onUsgFinding,
        banner = banner,
        modifier = Modifier.scrollToIfMissing(state.usgFinding == null, banner, scrollCoordinator),
      )
    }
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_transport_shared),
      value = state.transportContactShared,
      onSelected = actions.onTransportShared,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.transportContactShared == null, banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_funds_arranged),
      value = state.fundsArranged,
      onSelected = actions.onFundsArranged,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.fundsArranged == null, banner, scrollCoordinator),
    )
    VisitYesNoField(
      label = stringResource(R.string.visit_form_vd_birth_companion),
      value = state.birthCompanionIdentified,
      onSelected = actions.onBirthCompanion,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.birthCompanionIdentified == null, banner, scrollCoordinator),
    )
    AppTextField(
      value = state.remarks,
      onValueChange = actions.onRemarks,
      label = stringResource(R.string.visit_form_vd_remarks),
      placeholder = stringResource(R.string.visit_form_vd_remarks_placeholder),
      singleLine = false,
      minLines = 3,
    )

    // --- Counselling Checklist (Q56, advisory only) --------------------------
    VisitSectionHeader(stringResource(R.string.visit_form_vd_section_counselling))
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_counselling),
      arrayRes = R.array.visit_form_counselling_options,
      codes = state.counsellingTopics,
      onToggle = actions.onCounsellingTopic,
      error = null,
    )

    if (banner) VisitValidationBanner()
  }
}

/** One Td dose row: checkbox + its date field, shown only once checked. */
@Composable
private fun TdDoseRow(
  label: String,
  checked: Boolean,
  date: java.time.LocalDate?,
  onChecked: (Boolean) -> Unit,
  onDate: (java.time.LocalDate) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing)) {
    VisitCheckRow(
      label = label,
      checked = checked,
      onCheckedChange = onChecked,
    )
    if (checked) {
      AppDateField(
        label = label,
        placeholder = stringResource(R.string.visit_form_vd_td_date_placeholder),
        value = date,
        onDateSelected = onDate,
        errorText = null,
      )
    }
  }
}
