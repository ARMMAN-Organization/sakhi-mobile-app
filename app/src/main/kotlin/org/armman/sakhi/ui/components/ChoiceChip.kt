package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.White

/**
 * Sub-tab pill chip: selected = lavender surface + primary text (no border);
 * unselected = white with a hairline border.
 */
@Composable
fun ChoiceChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val background = if (selected) PrimarySurface else White
  val border = if (selected) PrimarySurface else NeutralG50
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .height(Dimens.ChipHeight)
      .clip(CircleShape)
      .background(background)
      .border(1.dp, border, CircleShape)
      .clickable(onClick = onClick),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.titleMedium,
      color = if (selected) MaterialTheme.colorScheme.primary else NeutralG400,
      modifier = Modifier.padding(horizontal = Dimens.ItemSpacing),
    )
  }
}
