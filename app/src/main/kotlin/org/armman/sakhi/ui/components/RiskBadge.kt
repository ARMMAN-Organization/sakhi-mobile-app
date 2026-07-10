package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Colour + icon + label mapping for a risk level, per the style guide badges. */
private data class RiskStyle(
  val container: Color,
  val content: Color,
  val icon: ImageVector,
  val labelRes: Int,
)

private fun RiskLevel.style(): RiskStyle = when (this) {
  RiskLevel.HIGH -> RiskStyle(RiskHigh, White, Icons.Filled.Warning, R.string.risk_high)
  RiskLevel.MODERATE -> RiskStyle(RiskModerate, White, Icons.Filled.Warning, R.string.risk_moderate)
  RiskLevel.MILD -> RiskStyle(RiskMild, NeutralG400, Icons.Filled.Warning, R.string.risk_mild)
  RiskLevel.LOW -> RiskStyle(RiskLow, White, Icons.Filled.Check, R.string.risk_low)
}

/** Risk badge: colored container with warning/check icon and label. */
@Composable
fun RiskBadge(riskLevel: RiskLevel, modifier: Modifier = Modifier) {
  val style = riskLevel.style()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .background(style.container, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Icon(
      imageVector = style.icon,
      contentDescription = null,
      tint = style.content,
      modifier = Modifier.size(16.dp),
    )
    Text(
      text = stringResource(style.labelRes),
      style = MaterialTheme.typography.labelLarge,
      color = style.content,
      modifier = Modifier.padding(start = 6.dp),
    )
  }
}
