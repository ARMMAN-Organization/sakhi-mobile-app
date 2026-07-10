package org.armman.sakhi.ui.beneficiaryprofile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiaryprofile.VitalStat
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.SerifTitle
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/**
 * "Last Visit Stats" card: red top accent bar, title, and a two-column grid of
 * vital tiles. Abnormal tiles get a red border + red value/caption per the design.
 * Renders nothing when [stats] is empty.
 */
@Composable
fun LastVisitStatsCard(
  stats: List<VitalStat>,
  modifier: Modifier = Modifier,
) {
  if (stats.isEmpty()) return
  val shape = RoundedCornerShape(Dimens.CardRadius)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(shape)
      .background(White),
  ) {
    // Red accent bar across the top of the card (design cue for vitals).
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(Dimens.CardAccentHeight)
        .background(RiskHigh),
    )
    Column(modifier = Modifier.padding(Dimens.ItemSpacing)) {
      Text(
        text = stringResource(R.string.beneficiary_profile_last_visit_stats),
        style = SerifTitle,
        color = NeutralG400,
      )
      // Two-column grid — pair the stats into rows.
      stats.chunked(2).forEach { rowStats ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
          modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
        ) {
          rowStats.forEach { stat ->
            StatTile(stat = stat, modifier = Modifier.weight(1f))
          }
          // Keep a lone tile at half width so it aligns with the grid above.
          if (rowStats.size == 1) Box(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun StatTile(stat: VitalStat, modifier: Modifier = Modifier) {
  val borderColor = if (stat.abnormal) RiskHigh else NeutralG50
  val contentColor = if (stat.abnormal) RiskHigh else NeutralG400
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .border(1.dp, borderColor, RoundedCornerShape(Dimens.TileRadius))
      .padding(vertical = Dimens.ItemSpacing, horizontal = Dimens.SmallSpacing),
  ) {
    Text(
      text = stat.value,
      style = MaterialTheme.typography.titleLarge,
      color = contentColor,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stat.caption,
      style = MaterialTheme.typography.labelLarge,
      color = if (stat.abnormal) RiskHigh else NeutralG200,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
