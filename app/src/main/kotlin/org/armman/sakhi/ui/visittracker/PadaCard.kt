package org.armman.sakhi.ui.visittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/**
 * Pada aggregate card (p63 mobile / p65 tablet): pada name, visits-remaining
 * badge, Open + Referral Follow-up rows with Women/Child counts, See Visits.
 * Tablet renders the rows as a 3-column table with dividers.
 */
@Composable
fun PadaCard(
  summary: PadaVisitSummary,
  isTablet: Boolean,
  onSeeVisits: (String) -> Unit,
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
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = summary.pada,
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
      Text(
        text = pluralStringResource(
          R.plurals.visit_tracker_visits_remaining,
          summary.remainingVisits,
          summary.remainingVisits,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = Primary,
        modifier = Modifier
          .background(PrimarySurface, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    if (isTablet) {
      TabletRows(summary)
    } else {
      MobileRows(summary)
    }
    PrimaryButton(
      text = stringResource(R.string.visit_tracker_see_visits),
      onClick = { onSeeVisits(summary.pada) },
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
      fullWidth = false,
      height = if (isTablet) Dimens.ChipHeight else Dimens.SmallButtonHeight,
      modifier = Modifier
        .align(Alignment.End)
        .padding(top = if (isTablet) Dimens.SmallSpacing else Dimens.ItemSpacing),
    )
  }
}

/** Mobile: compact label + counts rows (p63). */
@Composable
private fun MobileRows(summary: PadaVisitSummary) {
  CountRow(
    label = stringResource(R.string.visit_tracker_open),
    women = summary.openWomen,
    womenEnding = summary.openWomenEnding,
    children = summary.openChildren,
    modifier = Modifier.padding(top = Dimens.ItemSpacing),
  )
  CountRow(
    label = stringResource(R.string.visit_tracker_referral_follow_up),
    women = summary.referralWomen,
    womenEnding = 0,
    children = summary.referralChildren,
    modifier = Modifier.padding(top = Dimens.SmallSpacing),
  )
}

/** Tablet: 3-column table with vertical + horizontal dividers (p65). */
@Composable
private fun TabletRows(summary: PadaVisitSummary) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = Dimens.SmallSpacing)) {
    TabletRow(
      label = stringResource(R.string.visit_tracker_open),
      women = summary.openWomen,
      womenEnding = summary.openWomenEnding,
      children = summary.openChildren,
    )
    HorizontalDivider(thickness = 1.dp, color = NeutralG50)
    TabletRow(
      label = stringResource(R.string.visit_tracker_referral_follow_up),
      women = summary.referralWomen,
      womenEnding = 0,
      children = summary.referralChildren,
    )
  }
}

@Composable
private fun TabletRow(label: String, women: Int, womenEnding: Int, children: Int) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG200,
      modifier = Modifier.weight(1f).padding(vertical = Dimens.ChipSpacing),
    )
    VerticalDivider(thickness = 1.dp, color = NeutralG50, modifier = Modifier.fillMaxHeight())
    CountCell(
      icon = R.drawable.ic_woman,
      label = stringResource(R.string.visit_tracker_women),
      count = women,
      ending = womenEnding,
      modifier = Modifier.weight(1.2f),
    )
    VerticalDivider(thickness = 1.dp, color = NeutralG50, modifier = Modifier.fillMaxHeight())
    CountCell(
      icon = R.drawable.ic_baby,
      label = stringResource(R.string.visit_tracker_child),
      count = children,
      ending = 0,
      modifier = Modifier.weight(1f),
    )
  }
}

/** Mobile row: label left, Women and Child groups to the right. */
@Composable
private fun CountRow(
  label: String,
  women: Int,
  womenEnding: Int,
  children: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
      modifier = Modifier.weight(1f),
    )
    CountCell(
      icon = R.drawable.ic_woman,
      label = stringResource(R.string.visit_tracker_women),
      count = women,
      ending = womenEnding,
      modifier = Modifier.weight(1.2f),
    )
    CountCell(
      icon = R.drawable.ic_baby,
      label = stringResource(R.string.visit_tracker_child),
      count = children,
      ending = 0,
      modifier = Modifier.weight(0.8f),
    )
  }
}

/**
 * Icon + type label + count, ending count in purple parentheses.
 * Alignment intent (per design): the content block is CENTERED in the cell,
 * but has a fixed width so its left edge lines up across rows.
 */
@Composable
private fun CountCell(
  icon: Int,
  label: String,
  count: Int,
  ending: Int,
  modifier: Modifier = Modifier,
) {
  Box(contentAlignment = Alignment.Center, modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.width(Dimens.PadaCountCellWidth),
    ) {
      Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = NeutralG400,
        modifier = Modifier.size(18.dp),
      )
      // Design: only the first (row-label) column is grey; these stay dark.
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = NeutralG400,
        modifier = Modifier.padding(start = 4.dp, end = Dimens.SmallSpacing),
      )
      Text(
        text = buildAnnotatedString {
          append(count.toString())
          if (ending > 0) {
            withStyle(SpanStyle(color = Primary)) { append("($ending)") }
          }
        },
        // Numbers are Bold in the design, heavier than the labels.
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
    }
  }
}
