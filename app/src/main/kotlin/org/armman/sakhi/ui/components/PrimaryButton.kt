package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.ButtonTextTablet
import org.armman.sakhi.ui.theme.Dimens

/**
 * App-local primary action button following the style guide (pill, lavender).
 * When [loading] is true the label is replaced by a spinner and clicks are ignored.
 * [fullWidth] false renders a content-width pill (tablet layouts).
 */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
  trailingIcon: Painter? = null,
  fullWidth: Boolean = true,
  height: Dp = Dimens.ButtonHeight,
  contentPaddingH: Dp = Dimens.ScreenPadding,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    // Zero vertical content padding — the fixed height alone controls the pill size.
    contentPadding = PaddingValues(horizontal = contentPaddingH, vertical = 0.dp),
    modifier = modifier
      .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
      .height(height),
  ) {
    if (loading) {
      CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    } else {
      // Style guide: large buttons use Body 1 (16sp); tablet designs use 20sp.
      val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
      Text(
        text,
        style = if (isTablet) ButtonTextTablet else MaterialTheme.typography.titleMedium,
      )
      if (trailingIcon != null) {
        Icon(
          painter = trailingIcon,
          contentDescription = null,
          modifier = Modifier
            .padding(start = Dimens.SmallSpacing)
            .size(if (isTablet) 22.dp else 18.dp),
        )
      }
    }
  }
}
