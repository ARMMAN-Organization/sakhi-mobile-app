package org.armman.sakhi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.theme.Dimens

/** Landing screen — handles idle, syncing, success and error states. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  Scaffold(
    topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(Dimens.ScreenPadding),
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
      Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)

      when (val state = uiState) {
        is HomeUiState.Syncing -> CircularProgressIndicator()
        is HomeUiState.Synced -> Text(stringResource(R.string.sync_complete))
        is HomeUiState.Error -> Text(text = state.message, color = MaterialTheme.colorScheme.error)
        is HomeUiState.Idle -> Unit
      }

      PrimaryButton(text = stringResource(R.string.sync_now), onClick = viewModel::onSyncClicked)
    }
  }
}
