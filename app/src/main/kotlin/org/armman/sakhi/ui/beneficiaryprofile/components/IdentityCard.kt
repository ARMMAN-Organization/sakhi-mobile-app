package org.armman.sakhi.ui.beneficiaryprofile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
 * Identity + clinical-summary card on the beneficiary profile.
 *
 * Mobile (per the purple Figma board): header (avatar, name|age, Edit), a
 * single-column inline "Label : Value" list (Village/Pada/Mobile),
 * a Status | Risk | DOB stat strip with vertical dividers, then a
 * full-width Diagnosis chip row. Husband's Name and Weight are intentionally
 * omitted from the profile card display.
 *
 * Tablet keeps the wider 3-column grid layout per the tablet frame.
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
    HeaderRow(profile, showStateChip = isTablet, onEdit = onEdit)
    if (isTablet) {
      FieldGrid(
        fields = profile.gridFields(),
        columns = 3,
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      )
      RiskAndDiagnosisRow(profile)
    } else {
      // Indented to align with the name text (past the avatar) per the design.
      InlineFieldList(
        fields = profile.inlineFields(),
        modifier = Modifier.padding(
          top = Dimens.SmallSpacing,
          start = Dimens.AvatarSize + Dimens.SmallSpacing,
        ),
      )
      StatStrip(profile, modifier = Modifier.padding(top = Dimens.ItemSpacing))
      DiagnosisRow(profile, modifier = Modifier.padding(top = Dimens.ItemSpacing))
    }
  }
}

/** Avatar + "Name | age" on the left; optional state chip; Edit pill on the right. */
@Composable
private fun HeaderRow(
  profile: BeneficiaryProfile,
  showStateChip: Boolean,
  onEdit: () -> Unit,
) {
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
        // CR-022g: a locally enrolled beneficiary has no age yet (the mother form does not capture
        // one), and the "%1$s | %2$s" template would leave a dangling separator after her name.
        text = if (profile.ageLabel.isBlank()) {
          profile.name
        } else {
          stringResource(R.string.beneficiary_profile_name_age, profile.name, profile.ageLabel)
        },
        style = SerifTitleLarge,
        color = NeutralG400,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).padding(horizontal = Dimens.SmallSpacing),
      )
      if (showStateChip) StateChip(profile.status)
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

/** Mobile: single-column "Label :  Value" rows per the purple board. */
@Composable
private fun InlineFieldList(fields: List<LabelValue>, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing)) {
    fields.forEach { field ->
      Row {
        Text(
          text = stringResource(R.string.beneficiary_profile_inline_label, field.label),
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
        )
        Text(
          text = field.value,
          style = MaterialTheme.typography.titleMedium,
          color = NeutralG400,
          modifier = Modifier.padding(start = Dimens.SmallSpacing),
        )
      }
    }
  }
}

/**
 * Mobile stat strip: Status | Risk | DOB,
 * separated by thin vertical dividers per the design.
 */
@Composable
private fun StatStrip(profile: BeneficiaryProfile, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    StatColumn(
      label = stringResource(R.string.beneficiary_profile_label_state),
      modifier = Modifier.weight(1f),
    ) {
      StateChip(profile.status)
    }
    StripDivider()
    StatColumn(
      label = stringResource(R.string.beneficiary_profile_label_risk_short),
      modifier = Modifier.weight(1f),
    ) {
      RiskBadge(riskLevel = profile.riskLevel, compact = true)
    }
    StripDivider()
    StatColumn(
      label = stringResource(R.string.beneficiary_profile_label_dob),
      modifier = Modifier.weight(1f),
    ) {
      StatDate(profile.dob.orEmpty())
    }
  }
}

@Composable
private fun StatColumn(
  label: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      color = NeutralG400,
    )
    Box(modifier = Modifier.padding(top = Dimens.SmallSpacing)) { content() }
  }
}

@Composable
private fun StatDate(value: String) {
  Text(
    text = value,
    style = MaterialTheme.typography.titleMedium,
    color = NeutralG400,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun StripDivider() {
  VerticalDivider(
    color = NeutralG50,
    modifier = Modifier.fillMaxHeight().width(1.dp),
  )
}

/** Full-width Diagnosis label + chip row; hidden when there are no diagnoses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosisRow(profile: BeneficiaryProfile, modifier: Modifier = Modifier) {
  if (profile.diagnoses.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth()) {
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

/** Tablet: Risk Status (badge) + Diagnosis (chips) in a two-column row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RiskAndDiagnosisRow(profile: BeneficiaryProfile) {
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

/** Renders the detail (label, value) pairs in a fixed-column grid (tablet). */
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

/** Mobile inline list: Village, Pada, Mobile No — per the board. Husband's Name is hidden here. */
@Composable
private fun BeneficiaryProfile.inlineFields(): List<LabelValue> = listOf(
  LabelValue(stringResource(R.string.beneficiary_profile_label_village), village),
  LabelValue(stringResource(R.string.beneficiary_profile_label_pada), pada),
  LabelValue(stringResource(R.string.beneficiary_profile_label_mobile), mobileNumber),
)

/**
 * Tablet grid pairs: MOTHER shows LMP/EDD, CHILD shows DOB. Husband's Name and Weight
 * are intentionally excluded from the profile card display.
 */
@Composable
private fun BeneficiaryProfile.gridFields(): List<LabelValue> {
  val village = LabelValue(stringResource(R.string.beneficiary_profile_label_village), village)
  val pada = LabelValue(stringResource(R.string.beneficiary_profile_label_pada), pada)
  val mobile = LabelValue(stringResource(R.string.beneficiary_profile_label_mobile), mobileNumber)
  return if (type == BeneficiaryType.MOTHER) {
    listOf(
      village,
      pada,
      LabelValue(stringResource(R.string.beneficiary_profile_label_lmp), lmp.orEmpty(), purple = true),
      mobile,
      LabelValue(stringResource(R.string.beneficiary_profile_label_edd), edd.orEmpty(), purple = true),
    )
  } else {
    listOf(
      village,
      pada,
      LabelValue(stringResource(R.string.beneficiary_profile_label_dob), dob.orEmpty()),
      mobile,
    )
  }
}
