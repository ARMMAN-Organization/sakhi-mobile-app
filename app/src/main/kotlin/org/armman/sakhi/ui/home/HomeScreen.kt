package org.armman.sakhi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppLogo
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home dashboard per the "Arogya Sakhi - Revamp" Figma (p21/p23):
 * welcome header, Sakhi row with Data Upload pill, Active Visits and
 * Active Beneficiaries cards, fixed bottom action bar.
 * Actions that lead to unbuilt screens are intentionally no-ops for now.
 */
@Composable
fun HomeScreen(
  onAllBeneficiaries: () -> Unit = {},
  onSeeVisitTracker: () -> Unit = {},
  onProfile: () -> Unit = {},
  onRegisterNew: () -> Unit = {},
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val uploadModalState by viewModel.uploadModalState.collectAsStateWithLifecycle()
  val pendingUploadCount by viewModel.pendingUploadCount.collectAsStateWithLifecycle()

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      HomeHeader(onProfile = onProfile)
      // White rounded-top sheet — all content below the header sits on it (per design).
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when (val state = uiState) {
          is HomeUiState.Loading -> HomeLoading()
          is HomeUiState.Error -> HomeError(onRetry = viewModel::loadSummary)
          is HomeUiState.Success -> HomeContent(
            summary = state.summary,
            pendingUploadCount = pendingUploadCount,
            onAllBeneficiaries = onAllBeneficiaries,
            onSeeVisitTracker = onSeeVisitTracker,
            onRegisterNew = onRegisterNew,
            onDataUploadClick = viewModel::onDataUploadClicked,
          )
        }
      }
    }
  }

  if (uploadModalState.isVisible) {
    FormsUploadedModal(
      records = uploadModalState.records,
      onDismiss = viewModel::onDismissUploadModal,
    )
  }
}

/** Lavender header: welcome title + today's date, profile avatar on the right. */
@Composable
private fun HomeHeader(onProfile: () -> Unit) {
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ScreenPadding),
  ) {
    Column {
      Text(
        text = stringResource(R.string.home_welcome),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
      Text(
        text = today,
        style = MaterialTheme.typography.labelSmall,
        color = NeutralG200,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
    AppLogo(
      contentDescription = stringResource(R.string.home_profile_content_description),
      onClick = onProfile,
    )
  }
}

@Composable
private fun HomeLoading() {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
    CircularProgressIndicator()
  }
}

@Composable
private fun HomeError(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.home_error_load),
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
    )
    PrimaryButton(
      text = stringResource(R.string.home_retry),
      onClick = onRetry,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
  }
}
