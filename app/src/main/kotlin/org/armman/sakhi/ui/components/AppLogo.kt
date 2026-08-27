package org.armman.sakhi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens

/**
 * The Arogya Sakhi logo lockup (illustration + wordmark + "Pregnancy and Child Care" tagline).
 *
 * The source asset bleeds to all four edges — it carries no internal margin — so it must never be
 * corner-clipped or forced into a square box, otherwise the tagline's outer letters get cut off.
 * [contentPadding] supplies the breathing room the asset itself lacks, so the wordmark never sits
 * flush against a neighbouring element or the screen edge.
 *
 * This composable is the single place that enforces those rules; call it instead of drawing
 * [R.drawable.logo_arogya_sakhi] directly.
 *
 * @param height total laid-out height, inclusive of [contentPadding]; the artwork itself occupies
 *   `height - 2 * contentPadding` and its width follows [AppLogoDefaults.ASPECT_RATIO].
 * @param contentPadding inset between the laid-out bounds and the artwork.
 */
@Composable
fun AppLogo(
  modifier: Modifier = Modifier,
  height: Dp = Dimens.HeaderLogoHeight,
  contentPadding: Dp = Dimens.LogoContentPadding,
  contentDescription: String = stringResource(R.string.app_logo_content_description),
  onClick: (() -> Unit)? = null,
) {
  Image(
    painter = painterResource(R.drawable.logo_arogya_sakhi),
    contentDescription = contentDescription,
    contentScale = ContentScale.Fit,
    modifier = modifier
      .height(height)
      // Height is the fixed dimension; width is derived from it, never the other way round.
      .aspectRatio(AppLogoDefaults.ASPECT_RATIO, matchHeightConstraintsFirst = true)
      // Applied before the padding so the whole footprint, gap included, is the tap target.
      .then(
        if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
      )
      .padding(contentPadding),
  )
}

object AppLogoDefaults {
  /** Intrinsic width of `logo_arogya_sakhi.png`, in px. */
  const val INTRINSIC_WIDTH_PX = 553f

  /** Intrinsic height of `logo_arogya_sakhi.png`, in px. */
  const val INTRINSIC_HEIGHT_PX = 615f

  /** Width : height of the logo asset. Sizing to anything else letterboxes or crops the lockup. */
  const val ASPECT_RATIO = INTRINSIC_WIDTH_PX / INTRINSIC_HEIGHT_PX
}
