package org.armman.sakhi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.armman.sakhi.R

/**
 * Cabin — primary UI font from the style guide (bundled, latin subset).
 * Glyphs Cabin lacks (e.g. Devanagari for Marathi) automatically fall back
 * to the system font, so EN and MR both render correctly.
 */
val CabinFamily = FontFamily(
  Font(R.font.cabin_regular, FontWeight.Normal),
  Font(R.font.cabin_medium, FontWeight.Medium),
  Font(R.font.cabin_semibold, FontWeight.SemiBold),
  Font(R.font.cabin_bold, FontWeight.Bold),
)

/** Libre Baskerville — serif used for select titles only (style-guide H3/H5). */
val BaskervilleFamily = FontFamily(
  Font(R.font.libre_baskerville_bold, FontWeight.Bold),
)

/** H3 — serif card/section titles (e.g. "Active Visits"). Not part of the M3 scale. */
val SerifTitle = TextStyle(
  fontFamily = BaskervilleFamily,
  fontWeight = FontWeight.Bold,
  fontSize = 18.sp,
)

/** Serif page titles (e.g. "My Beneficiaries") — measured 24px from the designs. */
val SerifTitleLarge = TextStyle(
  fontFamily = BaskervilleFamily,
  fontWeight = FontWeight.Bold,
  fontSize = 24.sp,
)

/** KPI number style measured from the dashboard designs (~40px, Cabin Bold). */
val KpiNumber = TextStyle(
  fontFamily = CabinFamily,
  fontWeight = FontWeight.Bold,
  fontSize = 40.sp,
)

/** Large-button label on tablet — measured ~20px from the tablet designs. */
val ButtonTextTablet = TextStyle(
  fontFamily = CabinFamily,
  fontWeight = FontWeight.SemiBold,
  fontSize = 20.sp,
)

// Style-guide scale mapped onto Material3 tokens.
val ArogyaTypography = Typography(
  headlineLarge = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp), // H2 (KPI)
  headlineMedium = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp), // Screen titles
  headlineSmall = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp), // Prominent names
  titleLarge = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp), // H4
  titleMedium = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp), // Body 1
  bodyLarge = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp), // Body 2
  bodyMedium = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp), // Body 6
  labelLarge = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), // Body 4
  labelMedium = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp), // Body 5
  labelSmall = TextStyle(fontFamily = CabinFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp), // Small
)
