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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG50

/** How far a label-hugging tab's indicator extends past its label on each side. */
private val HUG_TAB_INDICATOR_OVERHANG = 2.dp

/**
 * Style-guide tab row: selected tab in primary with a rounded indicator,
 * unselected in grey; hairline divider across the full width underneath.
 *
 * Two width strategies, chosen by [distributeEvenly]:
 *
 * - **`false` (list screens, e.g. My Beneficiaries):** tabs hug their labels and
 *   sit left-aligned with a fixed gap. Labels there are short and always fit.
 * - **`true` (form steppers, e.g. Enrollment / Visit Form):** every tab gets an
 *   *equal* share of the row width. This is what the design shows — four steps
 *   across the width, longer labels wrapping to two lines at a word boundary
 *   ("Personal / Info", "Health / History") while short ones stay on one line.
 *
 * The equal share matters for correctness, not just looks: sizing stepper tabs
 * to their intrinsic widths overflows a narrow screen, and because a [Row]
 * measures children in order, the leading tabs eat the whole width and the last
 * one ("Summary") is left with almost none — so it hard-wraps one character per
 * line. Equal weights make starvation impossible at any screen width.
 *
 * Rows may mix one- and two-line labels, so every tab is stretched to the row
 * height with its label pushed to the bottom. One-line labels therefore share a
 * baseline with the *last* line of their two-line neighbours (per the design)
 * and the indicator stays anchored on the divider rather than floating under a
 * shorter label.
 */
@Composable
fun AppTabRow(
  tabs: List<String>,
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
  distributeEvenly: Boolean = false,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      horizontalArrangement =
        if (distributeEvenly) Arrangement.Start
        else Arrangement.spacedBy(Dimens.ScreenPadding),
      modifier = Modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min),
    ) {
      tabs.forEachIndexed { index, title ->
        val selected = index == selectedIndex
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .then(
              // Equal share per tab, so no tab can be starved of width; otherwise
              // hug the label, with a small symmetric inset so the indicator
              // overhangs it evenly on both sides.
              if (distributeEvenly) {
                Modifier.weight(1f)
              } else {
                Modifier
                  .width(IntrinsicSize.Max)
                  .padding(horizontal = HUG_TAB_INDICATOR_OVERHANG)
              },
            )
            .fillMaxHeight()
            .clickable { onTabSelected(index) },
        ) {
          // Pushes the label to the bottom of the tab so single-line labels line
          // up with the second line of their two-line neighbours.
          Spacer(modifier = Modifier.weight(1f))
          // `maxLines`/`overflow` are a safety net: a label too long even for its
          // own equal share degrades to a clipped two-line ellipsis instead of a
          // per-character stack. Note the weight above is deliberately on the
          // Spacer, never on the Text — in the `IntrinsicSize.Max` branch a
          // weighted Text reports an intrinsic width of 0 (weight and intrinsic
          // measurement don't compose) and collapses the tab.
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else NeutralG200,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = Dimens.SmallSpacing),
          )
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
