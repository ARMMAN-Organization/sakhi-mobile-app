package org.armman.sakhi.ui.components

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens

/**
 * App-local primary action button following the style guide (pill, lavender).
 * When [loading] is true the label is replaced by a spinner and clicks are ignored.
 */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
  trailingIcon: ImageVector? = null,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    modifier = modifier.fillMaxWidth().height(Dimens.ButtonHeight),
  ) {
    if (loading) {
      CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    } else {
      Text(text)
      if (trailingIcon != null) {
        Icon(
          imageVector = trailingIcon,
          contentDescription = null,
          modifier = Modifier.padding(start = Dimens.SmallSpacing).size(18.dp),
        )
      }
    }
  }
}
