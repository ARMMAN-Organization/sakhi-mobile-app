package org.armman.sakhi.ui.beneficiaryprofile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisit
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisitAction
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisitState
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/**
 * One "See Visits" history card. Header = visit label + right-side status chip
 * (days-remaining for OPEN, "Referral Followup Incomplete" for a pending
 * referral). Body = state + date meta on the left and the primary action on the
 * right. All actions are stubbed via [onAction] until their screens exist.
 */
@Composable
fun VisitHistoryCard(
  visit: ProfileVisit,
  onAction: () -> Unit,
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
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = visit.label,
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
      StatusChip(visit)
    }
    HorizontalDivider(
      color = NeutralG50,
      modifier = Modifier.padding(vertical = Dimens.SmallSpacing),
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      MetaColumn(visit, modifier = Modifier.weight(1f))
      ActionButton(action = visit.action, enabled = visit.startable(), onClick = onAction)
    }
  }
}

@Composable
private fun StatusChip(visit: ProfileVisit) {
  when {
    visit.state == ProfileVisitState.OPEN && visit.daysRemaining != null -> Text(
      text = stringResource(R.string.beneficiaries_days_remaining, visit.daysRemaining),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier
        .background(PrimarySurface, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    visit.referralIncomplete -> Text(
      text = stringResource(R.string.beneficiary_profile_referral_incomplete),
      style = MaterialTheme.typography.labelLarge,
      color = RiskHigh,
      modifier = Modifier
        .background(RiskHighSurface, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun MetaColumn(visit: ProfileVisit, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      val stateLabel = if (visit.state == ProfileVisitState.OPEN) {
        R.string.beneficiary_profile_visit_open
      } else {
        R.string.beneficiary_profile_visit_complete
      }
      Text(
        text = stringResource(stateLabel),
        style = MaterialTheme.typography.titleMedium,
        color = NeutralG400,
      )
      Text(
        text = "|",
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
        modifier = Modifier.padding(horizontal = Dimens.SmallSpacing),
      )
      Icon(
        painter = painterResource(R.drawable.ic_calendar_dots),
        contentDescription = null,
        tint = NeutralG200,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = if (visit.state == ProfileVisitState.OPEN) {
          stringResource(R.string.beneficiaries_sch_date, visit.dateLabel)
        } else {
          visit.dateLabel
        },
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG400,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    if (visit.riskLabel != null) {
      Text(
        text = visit.riskLabel,
        style = MaterialTheme.typography.labelLarge,
        color = RiskHigh,
        modifier = Modifier
          .padding(top = Dimens.SmallSpacing)
          .background(RiskHighSurface, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 4.dp),
      )
    }
  }
}

@Composable
private fun ActionButton(action: ProfileVisitAction, enabled: Boolean, onClick: () -> Unit) {
  when (action) {
    ProfileVisitAction.START_VISIT -> PrimaryButton(
      text = stringResource(R.string.beneficiary_profile_start_visit),
      onClick = onClick,
      enabled = enabled,
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
      fullWidth = false,
      height = Dimens.SmallButtonHeight,
    )
    ProfileVisitAction.FILL_FORM -> PrimaryButton(
      text = stringResource(R.string.beneficiary_profile_fill_form),
      onClick = onClick,
      fullWidth = false,
      height = Dimens.SmallButtonHeight,
    )
    ProfileVisitAction.REFERRAL -> PrimaryButton(
      text = stringResource(R.string.beneficiary_profile_referral),
      onClick = onClick,
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
      fullWidth = false,
      height = Dimens.SmallButtonHeight,
    )
    ProfileVisitAction.SEE_DATA -> SecondaryButton(
      text = stringResource(R.string.beneficiary_profile_see_data),
      onClick = onClick,
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
      height = Dimens.SmallButtonHeight,
    )
  }
}

/** Start Visit is only tappable once the visit is due; other actions are always tappable. */
private fun ProfileVisit.startable(): Boolean =
  action != ProfileVisitAction.START_VISIT || startable
