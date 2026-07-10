package org.armman.sakhi.ui.beneficiaryprofile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.ui.components.RiskBadge
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.Information
import org.armman.sakhi.ui.theme.InformationSurface
import org.armman.sakhi.ui.theme.NeutralG10
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.StatusSuccessSurface
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/** A (label, value) pair; [purple] renders the value in the brand colour (LMP/EDD). */
private data class LabelValue(val label: String, val value: String, val purple: Boolean = false)

/**
 * Identity + clinical-summary card on the beneficiary profile. Header row
 * (avatar, name|age, state chip, Edit) above a labelled grid — 3 columns on
 * tablet, 2 on mobile — followed by Risk Status and Diagnosis.
 */
@Composable
fun IdentityCard(
  profile: BeneficiaryProfile,
  isTablet: Boolean,
  onEdit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(White)
      .padding(Dimens.ItemSpacing),
  ) {
    HeaderRow(profile, onEdit)
    FieldGrid(
      fields = profile.detailFields(),
      columns = if (isTablet) 3 else 2,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    RiskAndDiagnosis(profile)
  }
}

/** Avatar + "Name | age" + state chip on the left; Edit pill on the right. */
@Composable
private fun HeaderRow(profile: BeneficiaryProfile, onEdit: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(Dimens.AvatarSize).background(NeutralG50, CircleShape),
      ) {
        Icon(
          painter = painterResource(
            if (profile.type == BeneficiaryType.MOTHER) R.drawable.ic_woman else R.drawable.ic_baby,
          ),
          contentDescription = null,
          tint = NeutralG400,
          modifier = Modifier.size(24.dp),
        )
      }
      Text(
        text = stringResource(R.string.beneficiary_profile_name_age, profile.name, profile.ageLabel),
        style = SerifTitleLarge,
        color = NeutralG400,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).padding(horizontal = Dimens.SmallSpacing),
      )
      StateChip(profile.status)
    }
    SecondaryButton(
      text = stringResource(R.string.beneficiary_profile_edit),
      onClick = onEdit,
      trailingIcon = painterResource(R.drawable.ic_pencil_simple),
      height = Dimens.SmallButtonHeight,
    )
  }
}

@Composable
private fun StateChip(status: BeneficiaryStatus) {
  val (label, content, container) = when (status) {
    BeneficiaryStatus.ACTIVE ->
      Triple(R.string.beneficiary_profile_state_active, Information, InformationSurface)
    BeneficiaryStatus.JOURNEY_COMPLETE ->
      Triple(R.string.beneficiary_profile_state_journey_complete, StatusSuccess, StatusSuccessSurface)
    BeneficiaryStatus.CLOSED ->
      Triple(R.string.beneficiary_profile_state_closed, NeutralG200, NeutralG10)
  }
  Text(
    text = stringResource(label),
    style = MaterialTheme.typography.labelLarge,
    color = content,
    modifier = Modifier
      .background(container, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

/** Renders the detail (label, value) pairs in a fixed-column grid. */
@Composable
private fun FieldGrid(fields: List<LabelValue>, columns: Int, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
    fields.chunked(columns).forEach { rowFields ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        modifier = Modifier.fillMaxWidth(),
      ) {
        rowFields.forEach { field ->
          LabeledField(field, modifier = Modifier.weight(1f))
        }
        // Pad short final rows so columns stay aligned.
        repeat(columns - rowFields.size) { Box(modifier = Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun LabeledField(field: LabelValue, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Text(
      text = field.label,
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
    )
    Text(
      text = field.value,
      style = MaterialTheme.typography.titleMedium,
      color = if (field.purple) MaterialTheme.colorScheme.primary else NeutralG400,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

/** Risk Status (badge) + Diagnosis (chips); Diagnosis hidden when empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RiskAndDiagnosis(profile: BeneficiaryProfile) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.beneficiary_profile_label_risk),
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
      )
      RiskBadge(riskLevel = profile.riskLevel, modifier = Modifier.padding(top = 4.dp))
    }
    Column(modifier = Modifier.weight(1f)) {
      if (profile.diagnoses.isNotEmpty()) {
        Text(
          text = stringResource(R.string.beneficiary_profile_label_diagnosis),
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
          verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
          modifier = Modifier.padding(top = 4.dp),
        ) {
          profile.diagnoses.forEach { DiagnosisChip(it) }
        }
      }
    }
  }
}

@Composable
private fun DiagnosisChip(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = RiskHigh,
    modifier = Modifier
      .background(RiskHighSurface, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

/** Variant-aware detail pairs: MOTHER shows LMP/EDD, CHILD shows DOB/Weight. */
@Composable
private fun BeneficiaryProfile.detailFields(): List<LabelValue> {
  val village = LabelValue(stringResource(R.string.beneficiary_profile_label_village), village)
  val pada = LabelValue(stringResource(R.string.beneficiary_profile_label_pada), pada)
  val husband = LabelValue(stringResource(R.string.beneficiary_profile_label_husband), husbandName)
  val mobile = LabelValue(stringResource(R.string.beneficiary_profile_label_mobile), mobileNumber)
  return if (type == BeneficiaryType.MOTHER) {
    listOf(
      village,
      pada,
      LabelValue(stringResource(R.string.beneficiary_profile_label_lmp), lmp.orEmpty(), purple = true),
      husband,
      mobile,
      LabelValue(stringResource(R.string.beneficiary_profile_label_edd), edd.orEmpty(), purple = true),
    )
  } else {
    listOf(
      village,
      pada,
      LabelValue(stringResource(R.string.beneficiary_profile_label_dob), dob.orEmpty()),
      husband,
      mobile,
      LabelValue(stringResource(R.string.beneficiary_profile_label_weight), weight.orEmpty()),
    )
  }
}
