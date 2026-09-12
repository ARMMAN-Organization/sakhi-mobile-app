package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskLow
import org.armman.sakhi.ui.theme.RiskMild
import org.armman.sakhi.ui.theme.RiskModerate
import org.armman.sakhi.ui.theme.White

/**
 * Colour + icon + label mapping for a risk level, per the style guide badges.
 * [icon] covers the Warning-triangle levels (Material's bundled core icon set already has it);
 * Low Risk instead uses a custom drawable ([iconRes], a gauge/speedometer) since a "low reading"
 * dial isn't part of that bundled set — see `ic_gauge.xml`. Exactly one of the two is non-null.
 */
private data class RiskStyle(
  val container: Color,
  val content: Color,
  val icon: ImageVector?,
  val iconRes: Int?,
  val labelRes: Int,
  val shortLabelRes: Int,
)

private fun RiskLevel.style(): RiskStyle = when (this) {
  RiskLevel.HIGH ->
    RiskStyle(RiskHigh, White, Icons.Filled.Warning, null, R.string.risk_high, R.string.risk_high_short)
  RiskLevel.MODERATE ->
    // Dark text on the orange container, same treatment as MILD below — orange (like MILD's
    // yellow) doesn't carry white text as legibly as HIGH's red does (CR: Visit Tracker UI
    // issues, 2026-09-10).
    RiskStyle(
      RiskModerate, NeutralG400, Icons.Filled.Warning, null, R.string.risk_moderate, R.string.risk_moderate_short,
    )
  RiskLevel.MILD ->
    RiskStyle(RiskMild, NeutralG400, Icons.Filled.Warning, null, R.string.risk_mild, R.string.risk_mild_short)
  RiskLevel.LOW ->
    RiskStyle(RiskLow, White, null, R.drawable.ic_gauge, R.string.risk_low, R.string.risk_low_short)
}

/**
 * Risk badge: colored container with warning/check icon and label.
 * [compact] drops the "Risk" suffix (e.g. "High") for tight layouts like the
 * profile stat strip.
 */
@Composable
fun RiskBadge(riskLevel: RiskLevel, modifier: Modifier = Modifier, compact: Boolean = false) {
  val style = riskLevel.style()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .background(style.container, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    if (style.icon != null) {
      Icon(
        imageVector = style.icon,
        contentDescription = null,
        tint = style.content,
        modifier = Modifier.size(16.dp),
      )
    } else {
      Icon(
        painter = painterResource(style.iconRes!!),
        contentDescription = null,
        tint = style.content,
        modifier = Modifier.size(16.dp),
      )
    }
    Text(
      text = stringResource(if (compact) style.shortLabelRes else style.labelRes),
      style = MaterialTheme.typography.labelLarge,
      color = style.content,
      modifier = Modifier.padding(start = 6.dp),
    )
  }
}
