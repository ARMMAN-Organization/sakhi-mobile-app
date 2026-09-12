package org.armman.sakhi.ui.beneficiaryprofile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import org.armman.sakhi.data.referral.ReferralStatus
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.StatusSuccessSurface
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/**
 * One "See Visits" history card. Header = visit label + right-side status chip
 * (days-remaining for OPEN, "Referral Followup Incomplete" for a pending
 * referral). All actions are stubbed via [onAction] until their screens exist.
 *
 * Body per the design boards:
 * - Tablet: one line — state | 📅 date | risk chip … action button (right).
 * - Mobile: state | 📅 date, then risk chip (left) + action button (right)
 *   on a second line.
 */
@Composable
fun VisitHistoryCard(
  visit: ProfileVisit,
  isTablet: Boolean,
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
    if (isTablet) {
      // Single line: meta, divider + risk chip, action pinned right.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        MetaRow(visit, isTablet = true)
        if (visit.riskLabel != null) {
          MetaPipe()
          SolidRiskChip(visit.riskLabel)
        }
        Spacer(modifier = Modifier.weight(1f))
        ActionButton(action = visit.action, enabled = visit.startable(), onClick = onAction)
      }
    } else {
      MetaRow(visit, isTablet = false)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.SmallSpacing),
      ) {
        if (visit.riskLabel != null) SolidRiskChip(visit.riskLabel)
        Spacer(modifier = Modifier.weight(1f))
        ActionButton(action = visit.action, enabled = visit.startable(), onClick = onAction)
      }
    }
  }
}

/** Solid red risk chip (warning icon + white label) per the design. */
@Composable
private fun SolidRiskChip(label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .background(RiskHigh, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Icon(
      imageVector = Icons.Filled.Warning,
      contentDescription = null,
      tint = White,
      modifier = Modifier.size(16.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = White,
      modifier = Modifier.padding(start = 6.dp),
    )
  }
}

@Composable
private fun StatusChip(visit: ProfileVisit) {
  when {
    // bharath, 2026-09-10: a saved-but-not-yet-synced submission outranks the days-remaining
    // chip -- the countdown is meaningless (and null anyway, see ProfileVisitMapper) once the
    // Sakhi has already filled the form; she needs to see "not yet synced", not "N days left".
    visit.pendingSync -> Text(
      text = stringResource(R.string.beneficiary_profile_pending_sync_chip),
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG100,
      modifier = Modifier
        .background(NeutralG50, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    visit.state == ProfileVisitState.OPEN && visit.daysRemaining != null -> Text(
      text = stringResource(R.string.beneficiaries_days_remaining, visit.daysRemaining),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier
        .background(PrimarySurface, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    // bharath, 2026-09-10: a Referral Follow-up already submitted (but not yet synced) reads
    // "Referral Followup Incomplete" until ProfileVisitMapper's own doc's fix -- see
    // ProfileVisit.referralPendingSync. Must come before the referralIncomplete branch below,
    // since both are true at once for this row.
    visit.referralPendingSync -> Text(
      text = stringResource(R.string.beneficiary_profile_pending_sync_chip),
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG100,
      modifier = Modifier
        .background(NeutralG50, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    // CR-Referral-01: PENDING_FOLLOWUP keeps the original wording/styling (referralIncomplete is
    // still set exactly for this case — see ProfileVisitMapper); COMPLETED/LAPSED are new chip
    // variants layered on top now that referralStatus carries the full lifecycle. A referral
    // that has moved past PENDING_FOLLOWUP no longer drives the visit's action (only the chip),
    // so this branch is purely informational for those two.
    visit.referralIncomplete -> Text(
      text = stringResource(R.string.beneficiary_profile_referral_incomplete),
      style = MaterialTheme.typography.labelLarge,
      color = RiskHigh,
      modifier = Modifier
        .background(RiskHighSurface, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    visit.referralStatus == ReferralStatus.COMPLETED -> Text(
      text = stringResource(R.string.beneficiary_profile_referral_completed),
      style = MaterialTheme.typography.labelLarge,
      color = StatusSuccess,
      modifier = Modifier
        .background(StatusSuccessSurface, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    visit.referralStatus == ReferralStatus.LAPSED -> Text(
      text = stringResource(R.string.beneficiary_profile_referral_lapsed),
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG100,
      modifier = Modifier
        .background(NeutralG50, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

/** State | 📅 date — calendar icon sits next to the date, per the design. */
@Composable
private fun MetaRow(visit: ProfileVisit, isTablet: Boolean, modifier: Modifier = Modifier) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    val stateLabel = if (visit.pendingSync) {
      R.string.beneficiary_profile_visit_pending_sync
    } else if (visit.state == ProfileVisitState.OPEN) {
      R.string.beneficiary_profile_visit_open
    } else if (isTablet) {
      R.string.beneficiary_profile_visit_complete_short
    } else {
      R.string.beneficiary_profile_visit_complete
    }
    Text(
      text = stringResource(stateLabel),
      style = MaterialTheme.typography.titleMedium,
      color = NeutralG400,
    )
    MetaPipe()
    Icon(
      painter = painterResource(R.drawable.ic_calendar_dots),
      contentDescription = null,
      tint = NeutralG200,
      modifier = Modifier.size(16.dp),
    )
    Text(
      text = when {
        visit.state != ProfileVisitState.OPEN -> visit.dateLabel
        // Tablet board abbreviates the label; mobile spells it out.
        isTablet -> stringResource(R.string.beneficiaries_sch_date, visit.dateLabel)
        else -> stringResource(R.string.beneficiary_profile_schedule_date, visit.dateLabel)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG400,
      modifier = Modifier.padding(start = 4.dp),
    )
  }
}

@Composable
private fun MetaPipe() {
  Text(
    text = "|",
    style = MaterialTheme.typography.bodyMedium,
    color = NeutralG200,
    modifier = Modifier.padding(horizontal = Dimens.SmallSpacing),
  )
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
      enabled = enabled,
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
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

/**
 * Start Visit is only tappable once the visit is due. Fill Form (Referral Follow-up) is only
 * tappable while the follow-up hasn't already been submitted -- bharath, 2026-09-10, see
 * [ProfileVisit.referralPendingSync]'s doc: once submitted (even if not yet synced), re-tapping
 * would open a second follow-up form for the same referral. Every other action is always
 * tappable.
 */
private fun ProfileVisit.startable(): Boolean = when (action) {
  ProfileVisitAction.START_VISIT -> startable
  ProfileVisitAction.FILL_FORM -> !referralPendingSync
  else -> true
}
