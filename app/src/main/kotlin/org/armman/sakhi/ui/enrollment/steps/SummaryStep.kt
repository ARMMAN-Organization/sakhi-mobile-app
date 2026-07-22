package org.armman.sakhi.ui.enrollment.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.geography.GeographyUnit
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.HealthHistoryState
import org.armman.sakhi.ui.enrollment.PersonalInfoState
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Summary step (CR-015d) — "Review Details" card per the Enrollment form
 * board's purple Summary frame: Personal Info section with an Edit pill and
 * label/value rows. The Health History card joins when CR-015c lands.
 */
@Composable
fun SummaryStep(
  personalInfo: PersonalInfoState,
  healthHistory: HealthHistoryState,
  submitFailed: Boolean,
  submitErrorMessage: String? = null,
  onEditPersonalInfo: () -> Unit,
  onEditHealthHistory: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(R.string.enrollment_summary_title),
      style = MaterialTheme.typography.titleLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    ReviewCard(
      title = stringResource(R.string.enrollment_summary_personal_info),
      onEdit = onEditPersonalInfo,
    ) {
      PersonalInfoRows(personalInfo)
    }
    ReviewCard(
      title = stringResource(R.string.enrollment_summary_health_history),
      onEdit = onEditHealthHistory,
    ) {
      HealthHistoryRows(healthHistory)
    }
    if (submitFailed) {
      SubmitErrorBanner(message = submitErrorMessage)
    }
  }
}

/** White bordered card: section header + Edit pill, then label/value rows. */
@Composable
private fun ReviewCard(
  title: String,
  onEdit: () -> Unit,
  rows: @Composable () -> Unit,
) {
  val shape = RoundedCornerShape(Dimens.CardRadius)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(White)
      .border(1.dp, NeutralG50, shape)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      SecondaryButton(
        text = stringResource(R.string.enrollment_summary_edit),
        onClick = onEdit,
        trailingIcon = painterResource(R.drawable.ic_pencil_simple),
        height = Dimens.SmallButtonHeight,
      )
    }
    rows()
  }
}

@Composable
private fun PersonalInfoRows(info: PersonalInfoState) {
  val education = stringArrayResource(R.array.enrollment_education_options)
  val phoneOwners = stringArrayResource(R.array.enrollment_phone_owner_options)
  val networks = stringArrayResource(R.array.enrollment_network_options)
  val occupations = stringArrayResource(R.array.enrollment_occupation_options)
  val migrations = stringArrayResource(R.array.enrollment_migration_options)
  val incomes = stringArrayResource(R.array.enrollment_income_options)
  val religions = stringArrayResource(R.array.enrollment_religion_options)
  val categories = stringArrayResource(R.array.enrollment_category_options)

  // Q5–34 in form order; codes resolve to their display labels (SM-UI-2).
  val rows = listOf(
    R.string.enrollment_pi_first_name to info.firstName,
    R.string.enrollment_pi_middle_name to info.middleName,
    R.string.enrollment_pi_last_name to info.lastName,
    R.string.enrollment_pi_dob to info.dob?.formatted(),
    R.string.enrollment_pi_age to info.ageYears?.toString(),
    R.string.enrollment_pi_education to education.label(info.educationSelf),
    R.string.enrollment_pi_address to info.address,
    R.string.enrollment_pi_mobile to info.mobileNumber,
    R.string.enrollment_pi_phone_owner to phoneOwners.label(info.phoneOwner),
    R.string.enrollment_pi_network to networks.label(info.networkAvailability),
    R.string.enrollment_pi_partner_education to education.label(info.educationPartner),
    R.string.enrollment_pi_partner_occupation to occupations.label(info.partnerOccupation),
    R.string.enrollment_pi_years_in_village to info.yearsInVillage,
    R.string.enrollment_pi_migration to migrations.label(info.migrationPattern),
    R.string.enrollment_pi_income to incomes.label(info.incomeBand),
    R.string.enrollment_pi_religion to religions.label(info.religion),
    R.string.enrollment_pi_category to categories.label(info.category),
    R.string.enrollment_pi_household to info.householdMembers,
    R.string.enrollment_pi_children_under5 to info.childrenUnderFive,
    R.string.enrollment_pi_state to info.states.name(info.stateId),
    R.string.enrollment_pi_district to info.districts.name(info.districtId),
    R.string.enrollment_pi_block to info.blocks.name(info.blockId),
    R.string.enrollment_pi_village to info.villages.name(info.villageId),
    R.string.enrollment_pi_pada to info.padas.name(info.padaId),
    R.string.enrollment_pi_phc to info.phcs.name(info.phcId),
    R.string.enrollment_pi_sub_centre to info.subCentres.name(info.subCentreId),
    R.string.enrollment_pi_lmp to info.lmp?.formatted(),
    R.string.enrollment_pi_edd to info.edd?.formatted(),
    R.string.enrollment_pi_ga to info.gestationalAgeWeeks?.toString(),
  )

  rows.forEach { (labelRes, value) ->
    if (!value.isNullOrBlank()) {
      ReviewRow(label = stringResource(labelRes), value = value)
    }
  }
}

@Composable
private fun HealthHistoryRows(hh: HealthHistoryState) {
  val yesNo = stringArrayResource(R.array.enrollment_yesno_options)
  val planned = stringArrayResource(R.array.enrollment_planned_options)
  val treatment = stringArrayResource(R.array.enrollment_treatment_type_options)
  val rch = stringArrayResource(R.array.enrollment_rch_options)
  val ancStatus = stringArrayResource(R.array.enrollment_anc_status_options)
  val ancConds = stringArrayResource(R.array.enrollment_anc_condition_options)
  val whenOpts = stringArrayResource(R.array.enrollment_last_pregnancy_when_options)
  val complications = stringArrayResource(R.array.enrollment_delivery_complication_options)
  val durations = stringArrayResource(R.array.enrollment_delivery_duration_options)
  val types = stringArrayResource(R.array.enrollment_delivery_type_options)
  val places = stringArrayResource(R.array.enrollment_delivery_place_options)
  val outcomes = stringArrayResource(R.array.enrollment_delivery_outcome_options)
  val weights = stringArrayResource(R.array.enrollment_birth_weight_options)
  val selfConds = stringArrayResource(R.array.enrollment_self_condition_options)
  val meds = stringArrayResource(R.array.enrollment_long_term_med_options)
  val sickle = stringArrayResource(R.array.enrollment_sickle_cell_options)
  val substances = stringArrayResource(R.array.enrollment_substance_use_options)
  val familyConds = stringArrayResource(R.array.enrollment_family_condition_options)
  val malnutrition = stringArrayResource(R.array.enrollment_malnutrition_options)
  val trimester = trimesterText(hh.trimester)

  val rows = buildList {
    add(R.string.enrollment_hh_trimester to trimester)
    add(R.string.enrollment_hh_planned to planned.label(hh.plannedPregnancy))
    add(R.string.enrollment_hh_took_treatment to yesNo.boolLabel(hh.tookTreatment))
    if (hh.showTreatmentType) add(R.string.enrollment_hh_treatment_type to treatment.label(hh.treatmentType))
    add(R.string.enrollment_hh_rch_status to rch.label(hh.rchStatus))
    if (hh.showRchNumber) add(R.string.enrollment_hh_rch_number to hh.rchNumber)
    add(R.string.enrollment_hh_anc_status to ancStatus.label(hh.ancStatus))
    if (hh.showAnc1Details) {
      add(R.string.enrollment_hh_anc1_date to hh.anc1Date?.formatted())
      add(R.string.enrollment_hh_anc_conditions to ancConds.join(hh.ancConditions))
    }
    add(R.string.enrollment_hh_gravida to hh.gravida)
    add(R.string.enrollment_hh_para to hh.para)
    add(R.string.enrollment_hh_living_children to hh.livingChildren)
    add(R.string.enrollment_hh_abortions to hh.abortions)
    add(R.string.enrollment_hh_still_births to hh.stillBirths)
    add(R.string.enrollment_hh_dead_children to hh.deadChildren)
    if (hh.showLastPregnancy) {
      add(R.string.enrollment_hh_last_pregnancy_when to whenOpts.label(hh.lastPregnancyWhen))
      add(R.string.enrollment_hh_delivery_complications to complications.join(hh.deliveryComplications))
      add(R.string.enrollment_hh_delivery_duration to durations.label(hh.lastDeliveryDuration))
      add(R.string.enrollment_hh_delivery_type to types.label(hh.lastDeliveryType))
      add(R.string.enrollment_hh_delivery_place to places.label(hh.lastDeliveryPlace))
      add(R.string.enrollment_hh_delivery_outcome to outcomes.label(hh.lastDeliveryOutcome))
      if (hh.showBirthWeight) add(R.string.enrollment_hh_birth_weight to weights.label(hh.birthWeight))
    }
    add(R.string.enrollment_hh_self_conditions to selfConds.join(hh.selfConditions))
    add(R.string.enrollment_hh_long_term_meds to meds.join(hh.longTermMeds))
    add(R.string.enrollment_hh_sickle_cell to sickle.label(hh.sickleCell))
    add(R.string.enrollment_hh_substance_use to substances.join(hh.substanceUse))
    add(R.string.enrollment_hh_family_history to yesNo.boolLabel(hh.familyHistory))
    if (hh.showFamilyConditions) add(R.string.enrollment_hh_family_conditions to familyConds.join(hh.familyConditions))
    add(R.string.enrollment_hh_malnutrition to malnutrition.label(hh.malnutrition))
    add(R.string.enrollment_hh_remarks to hh.remarks)
  }

  rows.forEach { (labelRes, value) ->
    if (!value.isNullOrBlank()) {
      ReviewRow(label = stringResource(labelRes), value = value)
    }
  }
}

@Composable
private fun ReviewRow(label: String, value: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = NeutralG100,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.LabelValueGap, bottom = Dimens.SmallSpacing),
    )
    HorizontalDivider(color = NeutralG50)
  }
}

/**
 * Retryable submit failure (SM-9) — same visual family as the PI banner. Shows the real
 * backend-provided [message] (validation detail or duplicate-conflict text) when available,
 * falling back to a generic retry message otherwise (e.g. an unparseable error body).
 */
@Composable
private fun SubmitErrorBanner(message: String?) {
  val shape = RoundedCornerShape(Dimens.TileRadius)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(RiskHighSurface)
      .padding(Dimens.ItemSpacing),
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_warning_circle),
      contentDescription = null,
      tint = RiskHigh,
    )
    Text(
      text = message ?: stringResource(R.string.enrollment_submit_error),
      style = MaterialTheme.typography.bodyMedium,
      color = RiskHigh,
      modifier = Modifier.padding(start = Dimens.SmallSpacing),
    )
  }
}

// --- Helpers -----------------------------------------------------------------

/** 1-based code → display label; null-safe for unanswered optional rows. */
private fun Array<String>.label(code: Int?): String? =
  code?.let { getOrNull(it - 1) }

/** Yes/No array (index 0 = Yes) from a nullable boolean answer. */
private fun Array<String>.boolLabel(value: Boolean?): String? =
  value?.let { if (it) getOrNull(0) else getOrNull(1) }

/** 1-based code set → comma-joined labels; null when nothing selected. */
private fun Array<String>.join(codes: Set<Int>): String? =
  codes.sorted().mapNotNull { getOrNull(it - 1) }.joinToString(", ").ifBlank { null }

private fun List<GeographyUnit>.name(id: String?): String? =
  id?.let { selected -> firstOrNull { it.id == selected }?.name }

/** Localized trimester label for review (matches the step's read-only value). */
@Composable
private fun trimesterText(trimester: Int?): String? = when (trimester) {
  1 -> stringResource(R.string.enrollment_hh_trimester_first)
  2 -> stringResource(R.string.enrollment_hh_trimester_second)
  3 -> stringResource(R.string.enrollment_hh_trimester_third)
  else -> null
}

/** Design date format: "10 Jan 2026". */
private fun LocalDate.formatted(): String =
  format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
