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
import org.armman.sakhi.data.geography.GeographyUnit
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.enrollment.FieldError
import org.armman.sakhi.ui.enrollment.PersonalInfoState
import org.armman.sakhi.ui.enrollment.components.AppDateField
import org.armman.sakhi.ui.enrollment.components.AppDropdownField
import org.armman.sakhi.ui.enrollment.components.AppPhoneField
import org.armman.sakhi.ui.enrollment.components.AppReadOnlyField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Callbacks for every Personal Info field — kept flat to mirror the VM API. */
data class PersonalInfoActions(
  val onFirstName: (String) -> Unit,
  val onMiddleName: (String) -> Unit,
  val onLastName: (String) -> Unit,
  val onDob: (LocalDate) -> Unit,
  val onAge: (String) -> Unit,
  val onEducationSelf: (Int) -> Unit,
  val onAddress: (String) -> Unit,
  val onMobile: (String) -> Unit,
  val onPhoneOwner: (Int) -> Unit,
  val onNetwork: (Int) -> Unit,
  val onEducationPartner: (Int) -> Unit,
  val onPartnerOccupation: (Int) -> Unit,
  val onYearsInVillage: (String) -> Unit,
  val onMigration: (Int) -> Unit,
  val onIncome: (Int) -> Unit,
  val onReligion: (Int) -> Unit,
  val onCategory: (Int) -> Unit,
  val onHousehold: (String) -> Unit,
  val onChildrenUnderFive: (String) -> Unit,
  val onState: (String) -> Unit,
  val onDistrict: (String) -> Unit,
  val onBlock: (String) -> Unit,
  val onVillage: (String) -> Unit,
  val onPada: (String) -> Unit,
  val onPhc: (String) -> Unit,
  val onSubCentre: (String) -> Unit,
  val onLmpKnown: (Boolean) -> Unit,
  val onLmp: (LocalDate) -> Unit,
)

/**
 * Personal Info step (Excel Q5–34) per the design's Personal Info frame:
 * labelled outlined fields, inline errors, red banner when Next is blocked.
 * Fields beyond the frame reuse its dropdown/date/text patterns (plan-agreed).
 */
@Composable
fun PersonalInfoStep(
  state: PersonalInfoState,
  actions: PersonalInfoActions,
  modifier: Modifier = Modifier,
) {
  val fieldGap = Dimens.ItemSpacing
  Column(
    verticalArrangement = Arrangement.spacedBy(fieldGap),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(R.string.enrollment_pi_instruction),
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )

    // --- Identity (Q19–20) ---
    AppTextField(
      value = state.firstName,
      onValueChange = actions.onFirstName,
      label = stringResource(R.string.enrollment_pi_first_name),
      placeholder = stringResource(R.string.enrollment_pi_name_placeholder),
      errorText = errorText(state.firstNameError),
    )
    AppTextField(
      value = state.middleName,
      onValueChange = actions.onMiddleName,
      label = stringResource(R.string.enrollment_pi_middle_name),
      placeholder = stringResource(R.string.enrollment_pi_name_placeholder),
      errorText = errorText(state.middleNameError),
    )
    AppTextField(
      value = state.lastName,
      onValueChange = actions.onLastName,
      label = stringResource(R.string.enrollment_pi_last_name),
      placeholder = stringResource(R.string.enrollment_pi_name_placeholder),
      errorText = errorText(state.lastNameError),
    )
    AppDateField(
      label = stringResource(R.string.enrollment_pi_dob),
      placeholder = stringResource(R.string.enrollment_pi_dob_placeholder),
      value = state.dob,
      onDateSelected = actions.onDob,
    )
    AppTextField(
      value = state.ageYears?.toString().orEmpty(),
      onValueChange = actions.onAge,
      label = stringResource(R.string.enrollment_pi_age),
      placeholder = stringResource(R.string.enrollment_pi_age_placeholder),
      errorText = errorText(state.ageError),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    // --- Education / address / contact (Q21–25) ---
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_education),
      placeholder = stringResource(R.string.enrollment_pi_education_placeholder),
      options = stringArrayResource(R.array.enrollment_education_options).toList(),
      selectedIndex = state.educationSelf?.minus(1),
      onSelected = { actions.onEducationSelf(it + 1) },
      errorText = requiredIfMissing(state.educationSelf, state.showValidationBanner),
    )
    AppTextField(
      value = state.address,
      onValueChange = actions.onAddress,
      label = stringResource(R.string.enrollment_pi_address),
      placeholder = stringResource(R.string.enrollment_pi_address_placeholder),
      errorText = errorText(state.addressError),
      singleLine = false,
      minLines = 4,
    )
    AppPhoneField(
      label = stringResource(R.string.enrollment_pi_mobile),
      placeholder = stringResource(R.string.enrollment_pi_mobile_placeholder),
      value = state.mobileNumber,
      onValueChange = actions.onMobile,
      errorText = errorText(state.mobileError),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_phone_owner),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_phone_owner_options).toList(),
      selectedIndex = state.phoneOwner?.minus(1),
      onSelected = { actions.onPhoneOwner(it + 1) },
      errorText = requiredIfMissing(state.phoneOwner, state.showValidationBanner),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_network),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_network_options).toList(),
      selectedIndex = state.networkAvailability?.minus(1),
      onSelected = { actions.onNetwork(it + 1) },
      errorText = requiredIfMissing(state.networkAvailability, state.showValidationBanner),
    )

    // --- Partner & household demographics (Q26–34) ---
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_partner_education),
      placeholder = stringResource(R.string.enrollment_pi_education_placeholder),
      options = stringArrayResource(R.array.enrollment_education_options).toList(),
      selectedIndex = state.educationPartner?.minus(1),
      onSelected = { actions.onEducationPartner(it + 1) },
      errorText = requiredIfMissing(state.educationPartner, state.showValidationBanner),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_partner_occupation),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_occupation_options).toList(),
      selectedIndex = state.partnerOccupation?.minus(1),
      onSelected = { actions.onPartnerOccupation(it + 1) },
      errorText = requiredIfMissing(state.partnerOccupation, state.showValidationBanner),
    )
    AppTextField(
      value = state.yearsInVillage,
      onValueChange = actions.onYearsInVillage,
      label = stringResource(R.string.enrollment_pi_years_in_village),
      placeholder = stringResource(R.string.enrollment_pi_years_placeholder),
      errorText = errorText(state.yearsInVillageError),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_migration),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_migration_options).toList(),
      selectedIndex = state.migrationPattern?.minus(1),
      onSelected = { actions.onMigration(it + 1) },
      errorText = requiredIfMissing(state.migrationPattern, state.showValidationBanner),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_income),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_income_options).toList(),
      selectedIndex = state.incomeBand?.minus(1),
      onSelected = { actions.onIncome(it + 1) },
      errorText = requiredIfMissing(state.incomeBand, state.showValidationBanner),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_religion),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_religion_options).toList(),
      selectedIndex = state.religion?.minus(1),
      onSelected = { actions.onReligion(it + 1) },
      errorText = requiredIfMissing(state.religion, state.showValidationBanner),
    )
    AppDropdownField(
      label = stringResource(R.string.enrollment_pi_category),
      placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
      options = stringArrayResource(R.array.enrollment_category_options).toList(),
      selectedIndex = state.category?.minus(1),
      onSelected = { actions.onCategory(it + 1) },
      errorText = requiredIfMissing(state.category, state.showValidationBanner),
    )
    AppTextField(
      value = state.householdMembers,
      onValueChange = actions.onHousehold,
      label = stringResource(R.string.enrollment_pi_household),
      placeholder = stringResource(R.string.enrollment_pi_household_placeholder),
      errorText = errorText(state.householdMembersError),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    AppTextField(
      value = state.childrenUnderFive,
      onValueChange = actions.onChildrenUnderFive,
      label = stringResource(R.string.enrollment_pi_children_under5),
      placeholder = stringResource(R.string.enrollment_pi_children_placeholder),
      errorText = errorText(state.childrenUnderFiveError),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    // --- Geography cascade (Q12–18) ---
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_state),
      units = state.states, selectedId = state.stateId,
      onSelected = actions.onState, showBanner = state.showValidationBanner,
    )
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_district),
      units = state.districts, selectedId = state.districtId,
      onSelected = actions.onDistrict, showBanner = state.showValidationBanner,
    )
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_block),
      units = state.blocks, selectedId = state.blockId,
      onSelected = actions.onBlock, showBanner = state.showValidationBanner,
    )
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_village),
      units = state.villages, selectedId = state.villageId,
      onSelected = actions.onVillage, showBanner = state.showValidationBanner,
    )
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_pada),
      units = state.padas, selectedId = state.padaId,
      onSelected = actions.onPada, showBanner = state.showValidationBanner,
    )
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_phc),
      units = state.phcs, selectedId = state.phcId,
      onSelected = actions.onPhc, showBanner = state.showValidationBanner,
    )
    GeographyDropdown(
      label = stringResource(R.string.enrollment_pi_sub_centre),
      units = state.subCentres, selectedId = state.subCentreId,
      onSelected = actions.onSubCentre, showBanner = state.showValidationBanner,
    )

    // --- LMP block (Q5–7) ---
    LmpKnownRow(lmpKnown = state.lmpKnown, onLmpKnown = actions.onLmpKnown)
    AppDateField(
      label = stringResource(R.string.enrollment_pi_lmp),
      placeholder = stringResource(R.string.enrollment_pi_lmp_placeholder),
      value = state.lmp,
      onDateSelected = actions.onLmp,
      errorText = errorText(state.lmpError),
    )
    if (state.edd != null) {
      val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
      AppReadOnlyField(
        label = stringResource(R.string.enrollment_pi_edd),
        value = state.edd!!.format(formatter),
      )
      AppReadOnlyField(
        label = stringResource(R.string.enrollment_pi_ga),
        value = state.gestationalAgeWeeks?.toString().orEmpty(),
      )
    }

    if (state.showValidationBanner) {
      ValidationBanner()
    }
  }
}

/** "Does the woman know her LMP?" — Yes/No radios; No is a deliberate no-op. */
@Composable
private fun LmpKnownRow(lmpKnown: Boolean, onLmpKnown: (Boolean) -> Unit) {
  Column {
    Text(
      text = stringResource(R.string.enrollment_pi_lmp_known),
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG400,
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
      modifier = Modifier.padding(top = Dimens.SmallSpacing),
    ) {
      RadioOption(
        label = stringResource(R.string.enrollment_pi_yes),
        selected = lmpKnown,
        onClick = { onLmpKnown(true) },
      )
      RadioOption(
        label = stringResource(R.string.enrollment_pi_no),
        selected = false,
        onClick = { onLmpKnown(false) },
      )
    }
  }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Icon(
      painter = painterResource(
        if (selected) R.drawable.ic_radio_selected else R.drawable.ic_radio_unselected,
      ),
      contentDescription = null,
      tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
      modifier = Modifier.size(24.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      color = if (selected) MaterialTheme.colorScheme.primary else NeutralG400,
    )
  }
}

/** Red "Complete all necessary fields" banner per the design frame. */
@Composable
private fun ValidationBanner() {
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

@Composable
private fun GeographyDropdown(
  label: String,
  units: List<GeographyUnit>,
  selectedId: String?,
  onSelected: (String) -> Unit,
  showBanner: Boolean,
) {
  AppDropdownField(
    label = label,
    placeholder = stringResource(R.string.enrollment_pi_select_placeholder),
    options = units.map(GeographyUnit::name),
    selectedIndex = units.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 },
    onSelected = { index -> onSelected(units[index].id) },
    enabled = units.isNotEmpty(),
    errorText = if (showBanner && selectedId == null) {
      stringResource(R.string.enrollment_error_required)
    } else {
      null
    },
  )
}

/** Maps a [FieldError] to its localized message. */
@Composable
private fun errorText(error: FieldError?): String? = when (error) {
  null -> null
  FieldError.REQUIRED -> stringResource(R.string.enrollment_error_required)
  FieldError.LMP_FUTURE -> stringResource(R.string.enrollment_error_lmp_future)
  FieldError.LMP_TOO_RECENT -> stringResource(R.string.enrollment_error_lmp_recent)
  FieldError.LMP_TOO_OLD -> stringResource(R.string.enrollment_error_lmp_old)
  FieldError.NAME_SPECIAL_CHARS -> stringResource(R.string.enrollment_error_name_chars)
  FieldError.AGE_OUT_OF_RANGE -> stringResource(R.string.enrollment_error_age_range)
  FieldError.MOBILE_LENGTH -> stringResource(R.string.enrollment_error_mobile)
  FieldError.HOUSEHOLD_RANGE -> stringResource(R.string.enrollment_error_household_range)
  FieldError.CHILDREN_EXCEED_HOUSEHOLD ->
    stringResource(R.string.enrollment_error_children_exceed)
  FieldError.YEARS_INVALID -> stringResource(R.string.enrollment_error_years)
}

@Composable
private fun requiredIfMissing(value: Int?, showBanner: Boolean): String? =
  if (showBanner && value == null) stringResource(R.string.enrollment_error_required) else null
