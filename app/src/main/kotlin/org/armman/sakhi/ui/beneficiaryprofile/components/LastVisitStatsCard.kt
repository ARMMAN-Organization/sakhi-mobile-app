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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiaryprofile.VitalStat
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.RiskHighSurfaceSubtle
import org.armman.sakhi.ui.theme.SerifTitle
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/**
 * "Last Visit Stats" card: red top accent bar, title, and a two-column grid of
 * vital tiles. Renders nothing when [stats] is empty.
 *
 * Mobile: light red-shaded card with white tiles (dark values, red captions).
 * Tablet: white card with pink-filled tiles and red values.
 */
@Composable
fun LastVisitStatsCard(
  stats: List<VitalStat>,
  isTablet: Boolean,
  modifier: Modifier = Modifier,
) {
  if (stats.isEmpty()) return
  val shape = RoundedCornerShape(Dimens.CardRadius)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(shape)
      .background(if (isTablet) White else RiskHighSurfaceSubtle),
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
            StatTile(stat = stat, isTablet = isTablet, modifier = Modifier.weight(1f))
          }
          // Keep a lone tile at half width so it aligns with the grid above.
          if (rowStats.size == 1) Box(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun StatTile(stat: VitalStat, isTablet: Boolean, modifier: Modifier = Modifier) {
  // Tablet: pink-filled tile, red border/value when abnormal.
  // Mobile: white tile, grey border, dark value — only the caption goes red.
  val tileColor = if (isTablet && stat.abnormal) RiskHighSurface else White
  val borderColor = if (isTablet && stat.abnormal) RiskHigh else NeutralG50
  val valueColor = if (isTablet && stat.abnormal) RiskHigh else NeutralG400
  val shape = RoundedCornerShape(Dimens.TileRadius)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .background(tileColor, shape)
      .border(1.dp, borderColor, shape)
      .padding(vertical = Dimens.ItemSpacing, horizontal = Dimens.SmallSpacing),
  ) {
    Text(
      // Measured value in the content colour; "(reference)" values in grey.
      text = stat.value.withDimmedReferences(base = valueColor, dim = NeutralG200),
      style = MaterialTheme.typography.titleLarge,
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

/**
 * Colours a stat value per the design: measured readings in [base], the
 * parenthesised reference ranges in [dim] — e.g. "9 (12)" → red "9", grey "(12)".
 */
private fun String.withDimmedReferences(base: Color, dim: Color): AnnotatedString =
  buildAnnotatedString {
    var index = 0
    for (match in REFERENCE_REGEX.findAll(this@withDimmedReferences)) {
      withStyle(SpanStyle(color = base)) { append(substring(index, match.range.first)) }
      withStyle(SpanStyle(color = dim)) { append(match.value) }
      index = match.range.last + 1
    }
    withStyle(SpanStyle(color = base)) { append(substring(index)) }
  }

private val REFERENCE_REGEX = Regex("""\([^)]*\)""")
