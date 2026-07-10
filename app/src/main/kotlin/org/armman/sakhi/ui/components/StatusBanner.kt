package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.StatusSuccessSurface

/** Banner variants from the style guide's toast/notification set. */
enum class StatusBannerVariant(
  internal val contentColor: Color,
  internal val containerColor: Color,
  internal val icon: ImageVector,
) {
  Success(StatusSuccess, StatusSuccessSurface, Icons.Filled.CheckCircle),
  Error(RiskHigh, Color(0xFFFDF2F2), Icons.Filled.Warning),
}

/** Inline status banner (e.g. "You have logged out successfully"). */
@Composable
fun StatusBanner(
  message: String,
  variant: StatusBannerVariant,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, variant.contentColor, RoundedCornerShape(8.dp))
      .background(variant.containerColor, RoundedCornerShape(8.dp))
      .padding(horizontal = 12.dp, vertical = 12.dp),
  ) {
    Icon(
      imageVector = variant.icon,
      contentDescription = null,
      tint = variant.contentColor,
      modifier = Modifier.size(20.dp),
    )
    Text(
      text = message,
      style = MaterialTheme.typography.labelLarge,
      color = variant.contentColor,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}
