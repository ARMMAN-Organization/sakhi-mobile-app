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

  /** Fixed content width of a pada-card count cell (centers as a block, left-aligns inside). */
  val PadaCountCellWidth = 150.dp

  /** Width breakpoint (dp) at or above which the tablet layout applies. */
  const val TabletMinWidthDp = 600

  // List-screen tokens measured from the My Beneficiaries designs (150dpi).
  val SearchBarHeight = 52.dp
  val ChipHeight = 40.dp
  val ChipSpacing = 12.dp
  val TabIndicatorHeight = 4.dp
  val CardAccentHeight = 6.dp
  val AvatarSize = 44.dp

  /** Height of the Arogya Sakhi logo lockup in a screen header (width follows the asset ratio). */
  val HeaderLogoHeight = 52.dp

  /** Height of the logo lockup on the Login screen. */
  val LoginLogoHeight = 156.dp

  /**
   * Breathing room between the logo lockup's laid-out bounds and the artwork. The PNG bleeds to all
   * four edges, so without this inset the wordmark sits flush against neighbouring content.
   */
  val LogoContentPadding = 4.dp
  val SmallButtonHeight = 44.dp

  /** Horizontal content inset on tablet screens (measured from tablet designs). */
  val ScreenPaddingTablet = 48.dp

  // Enrollment tokens measured from the Enrollment form designs (150dpi).
  /** Height of a "Register New Beneficiary as" option card. */
  val EnrollmentOptionHeight = 52.dp

  /** Leading icon circle inside an option card. */
  val EnrollmentOptionIconCircle = 32.dp

  /** Consent video placeholder height (mobile frame, 150dpi measure). */
  val ConsentVideoHeight = 216.dp

  /** Purple play badge diameter centered on the video placeholder. */
  val ConsentPlayBadge = 32.dp

  /** Square consent checkbox size. */
  val ConsentCheckboxSize = 20.dp

  /** Vertical gap between consent checklist rows. */
  val ConsentCheckRowSpacing = 20.dp

  /** Success check-circle diameter on the completion screen. */
  val CompleteBadgeSize = 56.dp

  /** Horizontal inset of the completion screen CTA (measured 150dpi). */
  val CompleteCtaPaddingH = 56.dp

  /** Gap between the completion title and its CTA. */
  val CompleteCtaSpacing = 56.dp

  /** Max content width of stepper forms on tablet (style-guide modal width). */
  val EnrollmentFormMaxWidth = 640.dp

  /** Bottom offset of the green "Data has been saved" pill (Summary frame). */
  val SavedToastBottomPadding = 96.dp

  /** Micro gap between a review row's label and its value (Summary frame). */
  val LabelValueGap = 2.dp

  // "Forms Uploaded" sync-status modal (Home screen Data Upload pill).
  /** Status icon/spinner diameter per row. */
  val UploadModalStatusIconSize = 24.dp

  /** Caps the record list's height so a long draft history scrolls inside the modal instead of
   * pushing it off-screen. */
  val UploadModalListMaxHeight = 360.dp

  /** Diameter of the circular close (X) button overlapping the modal's top-right corner — kept
   * small (matches FilterPopup's close button precedent), not Material3's default 48dp. */
  val UploadModalCloseButtonSize = 28.dp

  /** X glyph size inside [UploadModalCloseButtonSize] — smaller than Icon's own 24dp default so
   * it doesn't crowd the smaller circle. */
  val UploadModalCloseIconSize = 16.dp

  /** Height of a category card's sync-progress bar (thicker + rounded, per the Figma reference). */
  val UploadModalProgressBarHeight = 8.dp
}
