package org.armman.sakhi.ui.enrollment.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.White

/**
 * Success state per the Enrollment form design (purple mobile frame):
 * green check circle, "Enrollment Complete!", full-width inset CTA —
 * block vertically centered on the sheet.
 */
@Composable
fun EnrollmentCompleteContent(
  onStartVisitForm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(Dimens.CompleteBadgeSize)
          .background(StatusSuccess, CircleShape),
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_check),
          contentDescription = null,
          tint = White,
          modifier = Modifier.size(28.dp),
        )
      }
      Text(
        text = stringResource(R.string.enrollment_complete_title),
        style = MaterialTheme.typography.headlineMedium,
        color = NeutralG400,
        modifier = Modifier.padding(top = Dimens.ScreenPadding),
      )
      Spacer(modifier = Modifier.height(Dimens.CompleteCtaSpacing))
      PrimaryButton(
        text = stringResource(R.string.enrollment_start_visit_form),
        onClick = onStartVisitForm,
        trailingIcon = painterResource(R.drawable.ic_arrow_right),
        height = Dimens.SmallButtonHeight,
        modifier = Modifier
          .widthIn(max = Dimens.EnrollmentFormMaxWidth)
          .fillMaxWidth()
          .padding(horizontal = Dimens.CompleteCtaPaddingH),
      )
    }
  }
}
