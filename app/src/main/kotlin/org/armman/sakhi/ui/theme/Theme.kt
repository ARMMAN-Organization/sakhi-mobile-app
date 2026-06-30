package org.armman.sakhi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ArogyaColors = lightColorScheme(
  primary = Primary,
  onPrimary = White,
  background = BackgroundLavender,
  error = RiskHigh,
)

@Composable
fun ArogyaTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = ArogyaColors, typography = ArogyaTypography, content = content)
}
