package org.armman.sakhi.ui.visittracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppIcons
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SearchBar
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Visit Tracker — pada selection per Figma p63 (mobile) / p65 (tablet):
 * title + search, "Select Padas for Today's Visit (N)", one aggregate card
 * per pada with a "See Visits" action.
 */
@Composable
fun PadaSelectionScreen(
  onBack: () -> Unit,
  onSeeVisits: (padaId: String, padaName: String) -> Unit,
  onProfile: () -> Unit = {},
  viewModel: PadaSelectionViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  // Bug fix (2026-08-21): PadaSelectionViewModel only loaded its pada counts once, in `init` —
  // this screen's own ViewModel instance (and back-stack entry) survives every popBackStack() on
  // the way back from a pada's visits / a beneficiary profile / a completed visit form, so the
  // counts stayed frozen at first-load even after a visit was just submitted. Mirrors
  // HomeScreen's own `LaunchedEffect(Unit) { viewModel.loadSummary() }`, which reruns on every
  // (re)composition of this screen — including returning via back-navigation, not just the first
  // time it's ever opened.
  LaunchedEffect(Unit) { viewModel.loadVisits() }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.beneficiaries_back_title),
        subtitle = today,
        onBack = onBack,
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        Column(modifier = Modifier.fillMaxSize()) {
          TrackerHeader(
            isTablet = isTablet,
            searchQuery = state.searchQuery,
            onSearchChanged = viewModel::onSearchQueryChanged,
          )
          when {
            state.isLoading -> Centered { CircularProgressIndicator() }
            state.hasError -> LoadError(onRetry = viewModel::loadVisits)
            else -> PadaList(state, isTablet, onSeeVisits)
          }
        }
      }
    }
  }
}

/** Serif title with the search bar beside it (tablet) or below it (mobile). */
@Composable
internal fun TrackerHeader(
  isTablet: Boolean,
  searchQuery: String,
  onSearchChanged: (String) -> Unit,
) {
  val micIcon: @Composable () -> Unit = {
    Icon(
      imageVector = AppIcons.Mic,
      contentDescription = stringResource(R.string.beneficiaries_voice_search),
      tint = NeutralG400,
      modifier = Modifier.size(20.dp),
    )
  }
  if (isTablet) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      // No bottom padding — the list's own top inset provides the (small) gap.
      modifier = Modifier
        .fillMaxWidth()
        .padding(
          start = Dimens.ItemSpacing,
          end = Dimens.ItemSpacing,
          top = Dimens.ScreenPadding,
        ),
    ) {
      Text(
        text = stringResource(R.string.visit_tracker_title),
        style = SerifTitleLarge,
        color = NeutralG400,
      )
      SearchBar(
        value = searchQuery,
        onValueChange = onSearchChanged,
        placeholder = stringResource(R.string.beneficiaries_search_hint),
        trailingIcon = micIcon,
        modifier = Modifier.width(Dimens.SearchBarWidthTablet),
      )
    }
  } else {
    Column(modifier = Modifier.padding(horizontal = Dimens.ItemSpacing)) {
      Text(
        text = stringResource(R.string.visit_tracker_title),
        style = SerifTitleLarge,
        color = NeutralG400,
        modifier = Modifier.padding(top = Dimens.ScreenPadding),
      )
      SearchBar(
        value = searchQuery,
        onValueChange = onSearchChanged,
        placeholder = stringResource(R.string.beneficiaries_search_hint),
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
        trailingIcon = micIcon,
      )
    }
  }
}

@Composable
private fun PadaList(
  state: PadaSelectionUiState,
  isTablet: Boolean,
  onSeeVisits: (padaId: String, padaName: String) -> Unit,
) {
  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    contentPadding = PaddingValues(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Text(
        text = stringResource(R.string.visit_tracker_select_padas, state.totalPadas),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
    }
    if (state.padaCards.isEmpty()) {
      item {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        ) {
          Text(
            text = stringResource(R.string.visit_tracker_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = NeutralG200,
          )
        }
      }
    }
    items(state.padaCards, key = { it.padaId }) { summary ->
      PadaCard(summary = summary, isTablet = isTablet, onSeeVisits = onSeeVisits)
    }
  }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
}

@Composable
internal fun LoadError(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.visit_tracker_error_load),
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
    )
    PrimaryButton(
      text = stringResource(R.string.home_retry),
      onClick = onRetry,
      fullWidth = false,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
  }
}
