package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White

/**
 * Two-choice confirmation dialog — title, explanation, cancel + confirm.
 *
 * Same shape and tokens as the enrollment flow's exit-confirmation dialog, extracted so a second
 * caller doesn't fork the styling. No Figma frame covers this pattern yet (the handover boards have
 * no duplicate/confirm dialog), so it deliberately copies the one modal shape already shipped rather
 * than inventing a new one — flagged for design review.
 *
 * Callers pass already-resolved strings so the copy lives in `strings.xml` (English + Marathi) at the
 * call site, not in this component.
 */
@Composable
fun ConfirmationDialog(
  title: String,
  message: String,
  confirmLabel: String,
  cancelLabel: String,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  Dialog(onDismissRequest = onCancel) {
    Surface(shape = RoundedCornerShape(Dimens.CardRadius), color = White) {
      Column(modifier = Modifier.padding(Dimens.ScreenPadding)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = NeutralG400)
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
          modifier = Modifier.padding(top = Dimens.SmallSpacing),
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing, Alignment.End),
          modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
        ) {
          TextButton(onClick = onCancel) {
            Text(text = cancelLabel, style = MaterialTheme.typography.labelLarge)
          }
          SecondaryButton(
            text = confirmLabel,
            onClick = onConfirm,
            height = Dimens.SmallButtonHeight,
          )
        }
      }
    }
  }
}
