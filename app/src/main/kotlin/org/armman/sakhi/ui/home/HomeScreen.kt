package org.armman.sakhi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.PrimaryButton

/** Landing screen — a ready-to-view starting point for the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
  Scaffold(
    topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
      Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
      PrimaryButton(text = stringResource(R.string.sync_now), onClick = { /* TODO: trigger sync */ })
    }
  }
}
