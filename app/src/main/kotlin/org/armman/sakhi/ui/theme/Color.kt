package org.armman.sakhi.ui.theme

import androidx.compose.ui.graphics.Color

// Brand + status colours from the Arogya Sakhi style guide.
val Primary = Color(0xFF7C4DFF)
val BackgroundLavender = Color(0xFFF1EDF9)

// Light lavender surface for selected chips and highlight badges.
val PrimarySurface = Color(0xFFEDE7FB)
val RiskHigh = Color(0xFFD32F2F)

/**
 * Softer, less saturated red for a required/invalid field's outline (e.g. AppTextField,
 * AppDropdownField in FormFields.kt) — the full-strength [RiskHigh] read as too heavy/alarming for
 * a 1dp field border across a whole form (2026-08 design feedback). Kept distinct from RiskHigh so
 * risk badges/severity indicators elsewhere are untouched.
 */
val ErrorBorderSoft = Color(0xFFE57373)
val RiskModerate = Color(0xFFF57C00)
val RiskMild = Color(0xFFFBC02D)
val RiskLow = Color(0xFF2E7D32)
val NeutralG10 = Color(0xFFF7F9FC)
val NeutralG50 = Color(0xFFE6E6E6)
val NeutralG75 = Color(0xFFB3B3B3)
val NeutralG100 = Color(0xFF999999)
val NeutralG200 = Color(0xFF656565)
val NeutralG400 = Color(0xFF333333)
val White = Color(0xFFFFFFFF)
val StatusSuccess = Color(0xFF2E7D32)

// Derived surface tint for success banners (StatusSuccess on a near-white wash).
val StatusSuccessSurface = Color(0xFFF1F8F2)

// Informational (blue) — used for the "Active" state chip on the beneficiary profile.
val Information = Color(0xFF1D79E5)
val InformationSurface = Color(0xFFE8F1FC)

// Light red wash for high-risk diagnosis chips / abnormal stat tiles.
val RiskHighSurface = Color(0xFFFCE9E9)

// Moderate/mild/low risk surface washes — Pre-Visit Health History risk-factor
// cards (CR-016a) use these behind their colored header bar per risk level.
val RiskModerateSurface = Color(0xFFFDF1E6)
val RiskMildSurface = Color(0xFFFEF9E3)
val RiskLowSurface = Color(0xFFEBF5EC)

// Even lighter red wash — the "Last Visit Stats" card background per the board.
val RiskHighSurfaceSubtle = Color(0xFFFDF4F1)

// Light grey wash of the consent video placeholder (Enrollment form design).
val VideoPlaceholderSurface = Color(0xFFEEEEEE)

// Soft shadow tint for cards/bars (black at ~15% opacity, per design shadows).
val ShadowTint = Color(0x26000000)
