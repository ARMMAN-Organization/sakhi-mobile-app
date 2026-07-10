package org.armman.sakhi.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-wide soft box shadow for cards, sheets and bars — single source of truth;
 * never draw shadows directly in screens/components.
 *
 * Uses a native blurred shadow layer instead of Material elevation because
 * elevation shadows render too faintly to match the design's diffuse shadows.
 */
fun Modifier.softShadow(
  cornerRadius: Dp = 0.dp,
  blur: Dp = 20.dp,
  offsetY: Dp = 4.dp,
): Modifier = drawBehind {
  drawIntoCanvas { canvas ->
    val paint = Paint()
    paint.asFrameworkPaint().apply {
      color = android.graphics.Color.TRANSPARENT
      setShadowLayer(blur.toPx(), 0f, offsetY.toPx(), ShadowTint.toArgb())
    }
    canvas.drawRoundRect(
      0f,
      0f,
      size.width,
      size.height,
      cornerRadius.toPx(),
      cornerRadius.toPx(),
      paint,
    )
  }
}
