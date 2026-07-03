package org.armman.sakhi.ui.beneficiaries

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.RiskBadge
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG10
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskLow
import org.armman.sakhi.ui.theme.RiskMild
import org.armman.sakhi.ui.theme.RiskModerate
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Beneficiary list card: risk accent strip, risk + days-remaining badges,
 * avatar + name, location/schedule/visit meta row, Call + See Profile actions.
 */
@Composable
fun BeneficiaryCard(
  beneficiary: Beneficiary,
  onCall: (Beneficiary) -> Unit,
  onSeeProfile: (Beneficiary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(Dimens.CardRadius)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(shape)
      .background(White),
  ) {
    // Accent strip colored by risk level (design: full-width bar on card top).
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.CardAccentHeight)
        .background(beneficiary.riskLevel.accentColor()),
    )
    Column(modifier = Modifier.padding(Dimens.ItemSpacing)) {
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        RiskBadge(riskLevel = beneficiary.riskLevel)
        DaysRemainingBadge(days = beneficiary.daysRemaining)
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      ) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(Dimens.AvatarSize)
            .background(NeutralG10, CircleShape),
        ) {
          Icon(
            painter = painterResource(
              if (beneficiary.type == BeneficiaryType.MOTHER) {
                R.drawable.ic_woman
              } else {
                R.drawable.ic_baby
              },
            ),
            contentDescription = null,
            tint = NeutralG400,
            modifier = Modifier.size(24.dp),
          )
        }
        Text(
          text = beneficiary.name,
          style = MaterialTheme.typography.titleLarge,
          color = NeutralG400,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = Dimens.ItemSpacing),
        )
      }
      val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
      if (isTablet) {
        // Tablet: meta stacked left (location, then ANC | Sch Date), compact
        // buttons bottom-right aligned with the meta block.
        Row(
          verticalAlignment = Alignment.Bottom,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            MetaItem(
              icon = painterResource(R.drawable.ic_location),
              text = beneficiary.pada,
              modifier = Modifier.padding(top = Dimens.SmallSpacing),
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(top = Dimens.SmallSpacing),
            ) {
              MetaItem(
                icon = painterResource(R.drawable.ic_anc),
                text = beneficiary.visitLabel,
              )
              Text(
                text = "|",
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralG200,
                modifier = Modifier.padding(horizontal = Dimens.SmallSpacing),
              )
              MetaItem(
                icon = painterResource(R.drawable.ic_calendar_dots),
                text = stringResource(R.string.beneficiaries_sch_date, schedule(beneficiary)),
              )
            }
          }
          SecondaryButton(
            text = stringResource(R.string.beneficiaries_call),
            onClick = { onCall(beneficiary) },
            trailingIcon = painterResource(R.drawable.ic_phone_call),
            height = Dimens.SmallButtonHeight,
          )
          PrimaryButton(
            text = stringResource(R.string.beneficiaries_see_profile),
            onClick = { onSeeProfile(beneficiary) },
            trailingIcon = painterResource(R.drawable.ic_arrow_right),
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
            modifier = Modifier.padding(start = Dimens.ItemSpacing),
          )
        }
      } else {
        MetaRow(beneficiary)
        Row(
          horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
          modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
        ) {
          SecondaryButton(
            text = stringResource(R.string.beneficiaries_call),
            onClick = { onCall(beneficiary) },
            trailingIcon = painterResource(R.drawable.ic_phone_call),
            height = Dimens.SmallButtonHeight,
            modifier = Modifier.weight(1f),
          )
          PrimaryButton(
            text = stringResource(R.string.beneficiaries_see_profile),
            onClick = { onSeeProfile(beneficiary) },
            trailingIcon = painterResource(R.drawable.ic_arrow_right),
            height = Dimens.SmallButtonHeight,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

/** Localized short schedule date ("24 April"). */
private fun schedule(beneficiary: Beneficiary): String =
  beneficiary.scheduleDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))

/** Mobile meta row: location + schedule date + visit label with small grey icons. */
@Composable
private fun MetaRow(beneficiary: Beneficiary) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = Modifier.padding(top = Dimens.ItemSpacing),
  ) {
    MetaItem(icon = painterResource(R.drawable.ic_location), text = beneficiary.pada)
    MetaItem(
      icon = painterResource(R.drawable.ic_calendar_dots),
      text = stringResource(R.string.beneficiaries_schedule_date, schedule(beneficiary)),
    )
    MetaItem(icon = painterResource(R.drawable.ic_anc), text = beneficiary.visitLabel)
  }
}

@Composable
private fun MetaItem(
  icon: Painter,
  text: String,
  modifier: Modifier = Modifier,
) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Icon(
      painter = icon,
      contentDescription = null,
      tint = NeutralG200,
      modifier = Modifier.size(16.dp),
    )
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG400,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 4.dp),
    )
  }
}

/** "N days remaining" highlight badge (lavender surface, primary text). */
@Composable
private fun DaysRemainingBadge(days: Int) {
  Text(
    text = stringResource(R.string.beneficiaries_days_remaining, days),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier
      .background(PrimarySurface, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  )
}

private fun RiskLevel.accentColor() = when (this) {
  RiskLevel.HIGH -> RiskHigh
  RiskLevel.MODERATE -> RiskModerate
  RiskLevel.MILD -> RiskMild
  RiskLevel.LOW -> RiskLow
}
