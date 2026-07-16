package org.armman.sakhi.ui.enrollment.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.ConsentState
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.VideoPlaceholderSurface
import org.armman.sakhi.ui.theme.White

/**
 * Consent step per the Enrollment form design (purple mobile frame):
 * instruction, welcome title, video placeholder with purple play badge,
 * "Ensure that the beneficiary" checklist, two outlined pill actions.
 */
@Composable
fun ConsentStep(
  consent: ConsentState,
  onWillingPersonalInfo: (Boolean) -> Unit,
  onWillingHealthHistory: (Boolean) -> Unit,
  onWillingDiagnosticTests: (Boolean) -> Unit,
  onUnderstandsReferral: (Boolean) -> Unit,
  onPlayVideo: () -> Unit,
  onPlayGuidelines: () -> Unit,
  onTakePhoto: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.enrollment_consent_instruction),
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    Text(
      text = stringResource(R.string.enrollment_consent_welcome),
      style = MaterialTheme.typography.titleLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.ScreenPadding),
    )
    // Video placeholder: light grey rounded box with a centered purple play badge.
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .padding(top = Dimens.ItemSpacing)
        .fillMaxWidth()
        .height(Dimens.ConsentVideoHeight)
        .background(VideoPlaceholderSurface, RoundedCornerShape(Dimens.TileRadius))
        .border(1.dp, NeutralG50, RoundedCornerShape(Dimens.TileRadius))
        .clickable(onClick = onPlayVideo),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(Dimens.ConsentPlayBadge)
          .background(MaterialTheme.colorScheme.primary, CircleShape),
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_play_circle),
          contentDescription = stringResource(R.string.enrollment_consent_play_video),
          tint = White,
          modifier = Modifier.size(18.dp),
        )
      }
    }
    Text(
      text = stringResource(R.string.enrollment_consent_ensure),
      style = MaterialTheme.typography.titleLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.ScreenPadding),
    )
    ConsentCheckRow(
      label = stringResource(R.string.enrollment_consent_share_personal),
      checked = consent.willingPersonalInfo,
      onCheckedChange = onWillingPersonalInfo,
    )
    ConsentCheckRow(
      label = stringResource(R.string.enrollment_consent_share_health),
      checked = consent.willingHealthHistory,
      onCheckedChange = onWillingHealthHistory,
    )
    ConsentCheckRow(
      label = stringResource(R.string.enrollment_consent_diagnostic),
      checked = consent.willingDiagnosticTests,
      onCheckedChange = onWillingDiagnosticTests,
    )
    ConsentCheckRow(
      label = stringResource(R.string.enrollment_consent_referral),
      checked = consent.understandsReferral,
      onCheckedChange = onUnderstandsReferral,
    )
    if (consent.consentRefused) {
      Text(
        text = stringResource(R.string.enrollment_consent_refused),
        style = MaterialTheme.typography.labelLarge,
        color = RiskHigh,
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      )
    }
    SecondaryButton(
      text = stringResource(R.string.enrollment_consent_play_guidelines),
      onClick = onPlayGuidelines,
      trailingIcon = painterResource(R.drawable.ic_play_circle),
      height = Dimens.SmallButtonHeight,
      modifier = Modifier.padding(top = Dimens.ScreenPadding),
    )
    SecondaryButton(
      text = stringResource(
        // Same pill retakes the photo after a successful capture.
        if (consent.photoTaken) R.string.enrollment_consent_retake_photo
        else R.string.enrollment_consent_take_photo,
      ),
      onClick = onTakePhoto,
      trailingIcon = painterResource(R.drawable.ic_camera),
      height = Dimens.SmallButtonHeight,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    if (consent.photoTaken) {
      // Captured confirmation — not on the design frame (no captured state
      // exists there); flagged as an intentional deviation.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_check_circle_small),
          contentDescription = null,
          tint = StatusSuccess,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = stringResource(R.string.enrollment_consent_photo_captured),
          style = MaterialTheme.typography.labelSmall,
          color = StatusSuccess,
        )
      }
    }
  }
}

/**
 * Checklist row: square outlined checkbox + body text, matching the design's
 * unchecked grey boxes / checked purple boxes.
 */
@Composable
private fun ConsentCheckRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  // Alignment intent: checkbox centered to the first text line's height.
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimens.ChipSpacing),
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = Dimens.ConsentCheckRowSpacing)
      .clickable { onCheckedChange(!checked) }
      .padding(start = Dimens.SmallSpacing),
  ) {
    Icon(
      painter = painterResource(
        if (checked) R.drawable.ic_checkbox_selected else R.drawable.ic_checkbox,
      ),
      contentDescription = null,
      // Unchecked keeps the drawable's own colors (white fill, grey border);
      // tinting it would flood the inside grey (QA 2026-07-13).
      tint = if (checked) MaterialTheme.colorScheme.primary else Color.Unspecified,
      modifier = Modifier.size(Dimens.ConsentCheckboxSize),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
    )
  }
}
