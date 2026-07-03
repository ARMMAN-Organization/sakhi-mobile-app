package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG50

/**
 * Style-guide tab row: selected tab in primary with a rounded indicator,
 * unselected in grey; hairline divider across the full width underneath.
 */
@Composable
fun AppTabRow(
  tabs: List<String>,
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    // Alignment intent: tabs left-aligned with even gaps; each tab's text is
    // centered over its own indicator, which hugs the text width.
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
      modifier = Modifier.fillMaxWidth(),
    ) {
      tabs.forEachIndexed { index, title ->
        val selected = index == selectedIndex
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .width(IntrinsicSize.Max)
            .clickable { onTabSelected(index) }
            .padding(horizontal = 2.dp),
        ) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else NeutralG200,
            modifier = Modifier.padding(vertical = Dimens.SmallSpacing),
          )
          if (selected) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.TabIndicatorHeight)
                .background(
                  MaterialTheme.colorScheme.primary,
                  RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                ),
            ) { /* indicator only */ }
          }
        }
      }
    }
    HorizontalDivider(thickness = 1.dp, color = NeutralG50)
  }
}
