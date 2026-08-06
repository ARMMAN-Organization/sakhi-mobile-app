package org.armman.sakhi.ui.beneficiaries

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.ui.components.AppIcons
import org.armman.sakhi.ui.components.AppTabPager
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.ChoiceChip
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
 * My Beneficiaries per the "Arogya Sakhi - Revamp" Figma (p3-6): tabs,
 * sub-tabs, search, Pada/Risk filters, beneficiary card list.
 */
@Composable
fun BeneficiariesScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  onSeeProfile: (String) -> Unit = {},
  viewModel: BeneficiariesViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

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
        Box(modifier = Modifier.fillMaxSize()) {
          Column(modifier = Modifier.fillMaxSize()) {
            ListHeader(state, viewModel)
            when {
              state.isLoading -> Loading()
              state.hasError -> LoadError(onRetry = viewModel::loadBeneficiaries)
              else -> AppTabPager(
                tabs = listOf(
                  stringResource(R.string.beneficiaries_tab_active),
                  stringResource(R.string.beneficiaries_tab_journey_complete),
                  stringResource(R.string.beneficiaries_tab_closed),
                ),
                selectedIndex = state.selectedTab.ordinal,
                onTabSelected = { viewModel.onTabSelected(BeneficiaryStatus.entries[it]) },
                tabRowModifier = Modifier.padding(
                  start = Dimens.ItemSpacing,
                  top = Dimens.ItemSpacing,
                ),
              ) { page ->
                BeneficiaryList(
                  state = state,
                  status = BeneficiaryStatus.entries[page],
                  onCall = { beneficiary ->
                    // ACTION_DIAL needs no runtime permission and works offline.
                    context.startActivity(
                      Intent(Intent.ACTION_DIAL, Uri.parse("tel:${beneficiary.phoneNumber}")),
                    )
                  },
                  onSeeProfile = onSeeProfile,
                  viewModel = viewModel,
                )
              }
            }
          }
          FilterOverlay(state, viewModel, Modifier.align(Alignment.BottomCenter))
        }
      }
    }
  }
}

/** Serif title + search bar + tabs (always visible above the list). */
@Composable
private fun ListHeader(state: BeneficiariesUiState, viewModel: BeneficiariesViewModel) {
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val micIcon: @Composable () -> Unit = {
    Icon(
      imageVector = AppIcons.Mic,
      contentDescription = stringResource(R.string.beneficiaries_voice_search),
      tint = NeutralG400,
      modifier = Modifier.size(20.dp),
    )
  }
  if (isTablet) {
    // Tablet: title and search share one row — title left, search right.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ScreenPadding),
    ) {
      Text(
        text = stringResource(R.string.beneficiaries_title),
        style = SerifTitleLarge,
        color = NeutralG400,
      )
      SearchBar(
        value = state.searchQuery,
        onValueChange = viewModel::onSearchQueryChanged,
        placeholder = stringResource(R.string.beneficiaries_search_hint),
        trailingIcon = micIcon,
        modifier = Modifier.width(Dimens.SearchBarWidthTablet),
      )
    }
  } else {
    Column(modifier = Modifier.padding(horizontal = Dimens.ItemSpacing)) {
      Text(
        text = stringResource(R.string.beneficiaries_title),
        style = SerifTitleLarge,
        color = NeutralG400,
        modifier = Modifier.padding(top = Dimens.ScreenPadding),
      )
      SearchBar(
        value = state.searchQuery,
        onValueChange = viewModel::onSearchQueryChanged,
        placeholder = stringResource(R.string.beneficiaries_search_hint),
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
        trailingIcon = micIcon,
      )
    }
  }
}

/** One pager page: sub-tabs / month row + that tab's card list + empty state. */
@Composable
private fun BeneficiaryList(
  state: BeneficiariesUiState,
  status: BeneficiaryStatus,
  onCall: (Beneficiary) -> Unit,
  onSeeProfile: (String) -> Unit,
  viewModel: BeneficiariesViewModel,
) {
  val beneficiaries = state.listsByTab[status].orEmpty()
  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    contentPadding = PaddingValues(
      start = Dimens.ItemSpacing,
      end = Dimens.ItemSpacing,
      top = Dimens.ItemSpacing,
      bottom = 96.dp, // Keep the last card clear of the fixed filter bar.
    ),
    modifier = Modifier.fillMaxSize(),
  ) {
    if (status == BeneficiaryStatus.ACTIVE) {
      item { SubTabRow(state, viewModel) }
    }
    if (status == BeneficiaryStatus.JOURNEY_COMPLETE) {
      item { MonthFilterRow(state, viewModel) }
    }
    if (beneficiaries.isEmpty()) {
      item { EmptyState() }
    }
    items(beneficiaries, key = { it.id }) { beneficiary ->
      BeneficiaryCard(
        beneficiary = beneficiary,
        onCall = onCall,
        onSeeProfile = { onSeeProfile(beneficiary.id) },
      )
    }
  }
}

@Composable
private fun SubTabRow(state: BeneficiariesUiState, viewModel: BeneficiariesViewModel) {
  // Alignment intent: chips left-aligned with even 12dp gaps between them.
  Row(
    horizontalArrangement = Arrangement.spacedBy(Dimens.ChipSpacing),
    modifier = Modifier.fillMaxWidth(),
  ) {
    VisitSubTab.entries.forEach { subTab ->
      ChoiceChip(
        text = stringResource(subTab.labelRes()),
        selected = state.selectedSubTab == subTab,
        onClick = { viewModel.onSubTabSelected(subTab) },
      )
    }
  }
}

@Composable
private fun MonthFilterRow(state: BeneficiariesUiState, viewModel: BeneficiariesViewModel) {
  // Alignment intent: "Date" label centered against the dropdown chip, right-aligned row.
  Row(
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(R.string.beneficiaries_date_label),
      style = MaterialTheme.typography.titleMedium,
      color = NeutralG400,
      modifier = Modifier.padding(end = Dimens.SmallSpacing),
    )
    MonthDropdown(
      options = state.monthOptions,
      selected = state.selectedMonth,
      onSelected = viewModel::onMonthSelected,
    )
  }
}

@Composable
private fun Loading() {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
    CircularProgressIndicator()
  }
}

@Composable
private fun LoadError(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.beneficiaries_error_load),
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

@Composable
private fun EmptyState() {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
  ) {
    Text(
      text = stringResource(R.string.beneficiaries_empty),
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG200,
    )
  }
}

private fun VisitSubTab.labelRes(): Int = when (this) {
  VisitSubTab.ALL -> R.string.beneficiaries_subtab_all
  VisitSubTab.OPEN -> R.string.beneficiaries_subtab_open
  VisitSubTab.PENDING_REFERRAL -> R.string.beneficiaries_subtab_pending_referral
  VisitSubTab.MISSED -> R.string.beneficiaries_subtab_missed
}
