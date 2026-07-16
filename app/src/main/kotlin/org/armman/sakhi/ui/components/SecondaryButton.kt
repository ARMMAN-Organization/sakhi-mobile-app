package org.armman.sakhi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.ButtonTextTablet
import org.armman.sakhi.ui.theme.Dimens

/** Style-guide secondary action button: outlined lavender pill, optional trailing icon. */
@Composable
fun SecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  trailingIcon: Painter? = null,
  leadingIcon: Painter? = null,
  enabled: Boolean = true,
  height: Dp = Dimens.ButtonHeight,
) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    // Zero vertical content padding — the fixed height alone controls the pill size.
    contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 0.dp),
    modifier = modifier.height(height),
  ) {
    // Style guide: large buttons use Body 1 (16sp); tablet designs use 20sp.
    val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
    if (leadingIcon != null) {
      Icon(
        painter = leadingIcon,
        contentDescription = null,
        modifier = Modifier
          .padding(end = Dimens.SmallSpacing)
          .size(if (isTablet) 20.dp else 16.dp),
      )
    }
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
          .size(if (isTablet) 20.dp else 16.dp),
      )
    }
  }
}
