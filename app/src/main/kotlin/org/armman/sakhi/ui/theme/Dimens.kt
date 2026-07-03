package org.armman.sakhi.ui.theme

import androidx.compose.ui.unit.dp

/** Standardized spacing and sizing tokens from the Arogya Sakhi style guide. */
object Dimens {
  val ScreenPadding = 24.dp
  val ItemSpacing = 16.dp
  val SmallSpacing = 8.dp
  val ButtonHeight = 48.dp
  val CardRadius = 16.dp
  val TileRadius = 12.dp
  val TilePadding = 20.dp
  val SheetRadius = 40.dp
  val PillButtonPaddingH = 16.dp

  // Tablet button proportions (taller pill, wider inner padding).
  val ButtonHeightTablet = 48.dp
  val PillButtonPaddingHTablet = 48.dp
  val SearchBarWidthTablet = 320.dp

  /** Width breakpoint (dp) at or above which the tablet layout applies. */
  const val TabletMinWidthDp = 600

  // List-screen tokens measured from the My Beneficiaries designs (150dpi).
  val SearchBarHeight = 52.dp
  val ChipHeight = 40.dp
  val ChipSpacing = 12.dp
  val TabIndicatorHeight = 4.dp
  val CardAccentHeight = 6.dp
  val AvatarSize = 44.dp
  val SmallButtonHeight = 44.dp
}
