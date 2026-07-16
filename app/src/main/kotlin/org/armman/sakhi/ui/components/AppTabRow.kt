package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG50

/**
 * Style-guide tab row: selected tab in primary with a rounded indicator,
 * unselected in grey; hairline divider across the full width underneath.
 *
 * Rows may mix one- and two-line labels (Enrollment stepper on mobile):
 * every tab is stretched to the row height, its label vertically centered,
 * and the indicator anchored to the row bottom so it sits ON the divider —
 * never floating under a shorter label.
 */
@Composable
fun AppTabRow(
  tabs: List<String>,
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
  distributeEvenly: Boolean = false,
  indicatorOverhang: Dp = 2.dp,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    // Alignment intent: default = tabs left-aligned with even gaps;
    // distributeEvenly = tabs spread across the full row width (Enrollment
    // stepper). Each tab's text is centered over its own indicator.
    Row(
      horizontalArrangement =
        if (distributeEvenly) Arrangement.SpaceBetween
        else Arrangement.spacedBy(Dimens.ScreenPadding),
      modifier = Modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min),
    ) {
      tabs.forEachIndexed { index, title ->
        val selected = index == selectedIndex
        // The symmetric padding makes the indicator overhang the label
        // equally on both sides, keeping the text centered over it.
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .width(IntrinsicSize.Max)
            .fillMaxHeight()
            .clickable { onTabSelected(index) }
            .padding(horizontal = indicatorOverhang),
        ) {
          // Label centered in the space above the indicator, so one-line
          // labels align to the middle of two-line neighbours.
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f),
          ) {
            Text(
              text = title,
              style = MaterialTheme.typography.titleMedium,
              color = if (selected) MaterialTheme.colorScheme.primary else NeutralG200,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = Dimens.SmallSpacing),
            )
          }
          if (selected) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.TabIndicatorHeight)
                .background(
                  MaterialTheme.colorScheme.primary,
                  RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                ),
            )
          } else {
            // Keeps all tabs the same height so the divider hugs the row.
            Spacer(modifier = Modifier.height(Dimens.TabIndicatorHeight))
          }
        }
      }
    }
    HorizontalDivider(thickness = 1.dp, color = NeutralG50)
  }
}
