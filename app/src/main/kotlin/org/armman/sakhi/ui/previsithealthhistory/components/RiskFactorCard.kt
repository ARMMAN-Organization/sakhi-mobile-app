package org.armman.sakhi.ui.previsithealthhistory.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.previsithealth.RiskFactorTrend
import org.armman.sakhi.ui.components.RiskBadge
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.RiskLow
import org.armman.sakhi.ui.theme.RiskLowSurface
import org.armman.sakhi.ui.theme.RiskMild
import org.armman.sakhi.ui.theme.RiskMildSurface
import org.armman.sakhi.ui.theme.RiskModerate
import org.armman.sakhi.ui.theme.RiskModerateSurface
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/** [container]/[accent] wash + bar colors for a risk-factor card, by risk level. */
private fun RiskLevel.cardColors(): Pair<Color, Color> = when (this) {
  RiskLevel.HIGH -> RiskHighSurface to RiskHigh
  RiskLevel.MODERATE -> RiskModerateSurface to RiskModerate
  RiskLevel.MILD -> RiskMildSurface to RiskMild
  RiskLevel.LOW -> RiskLowSurface to RiskLow
}

/** Header bar text resource per level — mirrors [RiskBadge]'s own label mapping. */
private fun RiskLevel.headerLabelRes(): Int = when (this) {
  RiskLevel.HIGH -> R.string.risk_high
  RiskLevel.MODERATE -> R.string.risk_moderate
  RiskLevel.MILD -> R.string.risk_mild
  RiskLevel.LOW -> R.string.risk_low
}

/**
 * One Pre-Visit Health History trend card (FR-S-4.6): [factor] with a risk
 * level renders a colored header bar + wash background and a [RiskBadge];
 * a null risk level (plain vitals) renders as a neutral white tile with no
 * risk pill. Either way, the body shows up to 3 dated value slots.
 */
@Composable
fun RiskFactorCard(factor: RiskFactorTrend, modifier: Modifier = Modifier) {
  val risk = factor.riskLevel
  val (container, accent) = risk?.cardColors() ?: (White to NeutralG50)

  Column(
    modifier = modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(container),
  ) {
    if (risk != null) {
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
          .fillMaxWidth()
          .background(accent)
          .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.SmallSpacing),
      ) {
        Text(
          text = stringResource(
            R.string.previsit_risk_factor_title,
            stringResource(risk.headerLabelRes()),
            factor.factorName,
          ),
          style = MaterialTheme.typography.titleMedium,
          color = White,
        )
        RiskBadge(riskLevel = risk, compact = false)
      }
    } else {
      Text(
        text = factor.measureLabel,
        style = MaterialTheme.typography.titleMedium,
        color = NeutralG400,
        modifier = Modifier.padding(
          horizontal = Dimens.ItemSpacing,
          vertical = Dimens.SmallSpacing,
        ),
      )
    }
    if (risk != null) {
      Text(
        text = factor.measureLabel,
        style = MaterialTheme.typography.labelLarge,
        color = NeutralG400,
        modifier = Modifier.padding(
          start = Dimens.ItemSpacing,
          top = Dimens.SmallSpacing,
          end = Dimens.ItemSpacing,
        ),
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
      modifier = Modifier.fillMaxWidth().padding(Dimens.ItemSpacing),
    ) {
      factor.values.forEach { trend ->
        Column(
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(Dimens.TileRadius))
            .background(White)
            .padding(Dimens.SmallSpacing),
        ) {
          Text(
            text = trend.label,
            style = MaterialTheme.typography.labelSmall,
            color = NeutralG400,
          )
          Text(
            text = trend.value,
            style = MaterialTheme.typography.titleMedium,
            color = if (trend.abnormal) RiskHigh else NeutralG400,
          )
        }
      }
    }
  }
}
