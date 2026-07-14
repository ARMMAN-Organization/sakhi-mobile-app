package org.armman.sakhi.ui.enrollment.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.White

/**
 * One "Register New Beneficiary as" option card: leading icon in a light
 * circle, label, trailing radio — purple-stroked when selected (design:
 * Enrollment form, entry frame).
 */
@Composable
fun BeneficiaryTypeOption(
  label: String,
  iconRes: Int,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(Dimens.TileRadius)
  val borderColor = if (selected) MaterialTheme.colorScheme.primary else NeutralG50
  // Alignment intent: icon circle, label and radio share the card's center axis.
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .height(Dimens.EnrollmentOptionHeight)
      .clip(shape)
      .background(White)
      .border(1.dp, borderColor, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = Dimens.ItemSpacing),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(Dimens.EnrollmentOptionIconCircle)
        // Same grey as beneficiary-card avatar circles (QA 2026-07-13).
        .background(NeutralG50, CircleShape),
    ) {
      Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = NeutralG400,
        modifier = Modifier.size(20.dp),
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
      modifier = Modifier
        .padding(start = Dimens.ChipSpacing)
        .weight(1f),
    )
    Icon(
      painter = painterResource(
        if (selected) R.drawable.ic_radio_selected else R.drawable.ic_radio_unselected,
      ),
      contentDescription = null,
      tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
      modifier = Modifier.size(24.dp),
    )
  }
}
