package org.armman.sakhi.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.KpiNumber
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50

/**
 * Bordered stat tile: icon on top, large (optionally multi-colour) value,
 * caption underneath. Used inside [SummaryCard] rows on dashboards.
 */
@Composable
fun StatTile(
  icon: Painter,
  value: AnnotatedString,
  caption: AnnotatedString,
  modifier: Modifier = Modifier,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .border(1.dp, NeutralG50, RoundedCornerShape(Dimens.TileRadius))
      .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.TilePadding),
  ) {
    Icon(
      painter = icon,
      contentDescription = null,
      tint = NeutralG400,
      modifier = Modifier.size(32.dp),
    )
    Text(
      text = value,
      style = KpiNumber,
      color = NeutralG400,
      maxLines = 1,
      modifier = Modifier.padding(top = Dimens.SmallSpacing),
    )
    Text(
      text = caption,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG200,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
