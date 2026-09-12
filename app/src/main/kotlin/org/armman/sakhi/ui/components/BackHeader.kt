package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400

/**
 * Lavender header with back arrow + origin title + date, and the profile
 * avatar on the right (used by all screens reached from a parent screen).
 */
@Composable
fun BackHeader(
  title: String,
  subtitle: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  onAvatarClick: (() -> Unit)? = null,
) {
  Row(
    // Top, not CenterVertically: the outer row now holds a two-line Column (icon+title row,
    // then indented subtitle) whose total height differs from AppLogo's — Top keeps the logo
    // pinned to the title's line instead of drifting to the vertical center of both lines.
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ScreenPadding),
  ) {
    Column {
      // Icon and title share one row so CenterVertically aligns the arrow with the title's
      // own line only (not title+subtitle combined) — the bug was the arrow centering against
      // the whole two-line block and landing visibly below the title's center.
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = NeutralG400,
          )
        }
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          color = NeutralG400,
        )
      }
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = NeutralG200,
        // Indented by the back arrow's touch target (IconButton's default size matches
        // Dimens.ButtonHeight, 48dp) so the subtitle lines up under the title, not the arrow.
        modifier = Modifier.padding(start = Dimens.ButtonHeight, top = 4.dp),
      )
    }
    AppLogo(
      contentDescription =
        if (onAvatarClick != null) {
          stringResource(R.string.home_profile_content_description)
        } else {
          stringResource(R.string.app_logo_content_description)
        },
      onClick = onAvatarClick,
    )
  }
}
