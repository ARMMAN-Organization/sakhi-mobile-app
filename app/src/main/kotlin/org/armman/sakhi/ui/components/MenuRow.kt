package org.armman.sakhi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50

/**
 * Settings/menu list row: leading icon, title + subtitle, trailing chevron,
 * hairline divider underneath (Profile screen and future menu lists).
 */
@Composable
fun MenuRow(
  icon: Painter,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ItemSpacing),
    ) {
      Icon(
        painter = icon,
        contentDescription = null,
        tint = NeutralG200,
        modifier = Modifier.size(32.dp),
      )
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = Dimens.ItemSpacing),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = NeutralG400,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
      // Chevron (not the full arrow) per design; Material's matches exactly.
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = NeutralG400,
        modifier = Modifier.size(24.dp),
      )
    }
    HorizontalDivider(thickness = 1.dp, color = NeutralG50)
  }
}
