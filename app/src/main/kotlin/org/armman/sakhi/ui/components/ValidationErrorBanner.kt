package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface

/**
 * Shared "fix the following before continuing" banner for enrollment steps (Personal Info,
 * Health History). Replaces two near-identical private banners (`ValidationBanner` in
 * `PersonalInfoStep.kt`, `HealthValidationBanner` in `HealthHistoryStep.kt`) that only rendered a
 * single generic "Complete all necessary fields" line.
 *
 * That generic line meant a blocked Next tap gave no clue which of a step's 20+ fields was the
 * actual blocker — e.g. the Health History Td-dose checkbox group (at least one dose, or "None",
 * must be ticked) or the Gravida cross-total rule (`livingChildren + stillBirths + abortions` must
 * equal `gravida`) have no visible per-field red mark, so the Next button just looked disabled
 * with no way to tell why. This banner lists every currently-failing rule by name instead.
 *
 * @param errors human-readable descriptions of every failing rule, in field order. If empty,
 * nothing is rendered — callers should already gate this behind `showValidationBanner`, but an
 * empty list is a safe no-op either way rather than an empty box.
 */
@Composable
fun ValidationErrorBanner(errors: List<String>, modifier: Modifier = Modifier) {
  if (errors.isEmpty()) return
  val shape = RoundedCornerShape(Dimens.TileRadius)
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = modifier
      .fillMaxWidth()
      .background(RiskHighSurface, shape)
      .border(1.dp, RiskHigh, shape)
      .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ChipSpacing),
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_warning_circle),
      contentDescription = null,
      tint = RiskHigh,
      modifier = Modifier.size(20.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ChipSpacing)) {
      Text(
        text = stringResource(R.string.enrollment_validation_banner_title),
        style = MaterialTheme.typography.titleMedium,
        color = RiskHigh,
      )
      errors.forEach { message ->
        Text(
          text = "• $message",
          style = MaterialTheme.typography.bodyMedium,
          color = RiskHigh,
        )
      }
    }
  }
}
