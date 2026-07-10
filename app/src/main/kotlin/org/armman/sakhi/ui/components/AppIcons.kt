package org.armman.sakhi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom vector icons the Material core set lacks. Paths use the 24x24
 * Material grid so they compose consistently with built-in icons.
 */
object AppIcons {

  /** Outlined microphone (Material "mic_none" path) — voice input. */
  val Mic: ImageVector by lazy {
    ImageVector.Builder(
      name = "AppIcons.Mic",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    ).apply {
      path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.NonZero,
      ) {
        moveTo(12f, 14f)
        curveTo(13.66f, 14f, 14.99f, 12.66f, 14.99f, 11f)
        lineTo(15f, 5f)
        curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
        reflectiveCurveTo(9f, 3.34f, 9f, 5f)
        verticalLineTo(11f)
        curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
        close()
        moveTo(10.8f, 4.9f)
        curveTo(10.8f, 4.24f, 11.34f, 3.7f, 12f, 3.7f)
        reflectiveCurveTo(13.2f, 4.24f, 13.2f, 4.9f)
        lineTo(13.19f, 11.1f)
        curveTo(13.19f, 11.76f, 12.66f, 12.3f, 12f, 12.3f)
        reflectiveCurveTo(10.8f, 11.76f, 10.8f, 11.1f)
        verticalLineTo(4.9f)
        close()
        moveTo(17.3f, 11f)
        curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
        reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
        horizontalLineTo(5f)
        curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
        verticalLineTo(21f)
        horizontalLineTo(13f)
        verticalLineTo(17.72f)
        curveTo(16.28f, 17.24f, 19f, 14.42f, 19f, 11f)
        horizontalLineTo(17.3f)
        close()
      }
    }.build()
  }
}
