package org.armman.sakhi.ui.visittracker

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiary.VisitState
import org.armman.sakhi.data.visit.Visit
import org.armman.sakhi.data.visit.VisitType
import org.armman.sakhi.ui.beneficiaries.BeneficiaryCard
import org.armman.sakhi.ui.components.AppTabPager
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Visit Tracker — single pada's visits per Figma p64 (mobile) / p66 (tablet):
 * pada heading (mobile only), Open / Referral Follow Up tabs with counts,
 * beneficiary cards.
 */
@Composable
fun PadaVisitsScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  onSeeProfile: (String) -> Unit = {},
  viewModel: PadaVisitsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val context = LocalContext.current
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  // Bug fix (2026-08-21): same stale-counts gap as PadaSelectionScreen — PadaVisitsViewModel only
  // loaded once, in `init`, so returning here via popBackStack() (e.g. after completing a visit
  // from a beneficiary profile opened from this list) left the Open/Referral tabs and beneficiary
  // cards showing pre-visit data. See PadaSelectionScreen's own LaunchedEffect for the same fix.
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
          if (!isTablet) {
            // Mobile design shows the pada heading; tablet goes straight to tabs.
            Text(
              text = stringResource(R.string.visit_tracker_pada_heading, state.pada),
              style = MaterialTheme.typography.titleLarge,
              color = NeutralG400,
              modifier = Modifier.padding(
                start = Dimens.ItemSpacing,
                top = Dimens.ItemSpacing,
              ),
            )
          }
          when {
            state.isLoading -> Centered { CircularProgressIndicator() }
            state.hasError -> LoadError(onRetry = viewModel::loadVisits)
            else -> AppTabPager(
              tabs = listOf(
                stringResource(R.string.visit_tracker_tab_open, state.openCount),
                stringResource(R.string.visit_tracker_tab_referral, state.referralCount),
              ),
              selectedIndex = state.selectedTab.ordinal,
              onTabSelected = { viewModel.onTabSelected(VisitType.entries[it]) },
              tabRowModifier = Modifier.padding(
                start = Dimens.ItemSpacing,
                top = Dimens.ItemSpacing,
              ),
            ) { page ->
              VisitList(
                visits = state.visitsByType[VisitType.entries[page]].orEmpty(),
                onCall = { visit ->
                  // ACTION_DIAL needs no runtime permission and works offline.
                  context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${visit.phoneNumber}")),
                  )
                },
                onSeeProfile = onSeeProfile,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun VisitList(
  visits: List<Visit>,
  onCall: (Visit) -> Unit,
  onSeeProfile: (String) -> Unit,
) {
  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    contentPadding = PaddingValues(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxSize(),
  ) {
    if (visits.isEmpty()) {
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
    // Referral-follow-up rows have no visitId (always null), so the key falls back to
    // beneficiaryId + tab, which is unique within a single status bucket.
    items(visits, key = { "${it.visitType}-${it.beneficiaryId}-${it.id ?: ""}" }) { visit ->
      BeneficiaryCard(
        beneficiary = visit.toCardModel(),
        onCall = { onCall(visit) },
        onSeeProfile = { onSeeProfile(visit.beneficiaryId) },
      )
    }
  }
}

/**
 * Maps a visit to the card's display model. A null [Visit.riskLevel]/[Visit.beneficiaryName]/
 * [Visit.phoneNumber] (lookup failure, or an unassessed `none` grade — see [Visit.riskLevel])
 * degrades to [Beneficiary.isAssessed] = false / a placeholder name / a blank phone number, the
 * same neutral states [BeneficiaryCard] already renders for a beneficiary with no on-device
 * assessment. [BeneficiaryCard] hides/disables the Call action when [Beneficiary.phoneNumber] is
 * blank.
 */
private fun Visit.toCardModel() = Beneficiary(
  id = beneficiaryId,
  name = beneficiaryName ?: "—",
  type = beneficiaryType,
  riskLevel = riskLevel ?: RiskLevel.LOW,
  status = BeneficiaryStatus.ACTIVE,
  visitState = when (visitType) {
    VisitType.OPEN -> VisitState.OPEN
    VisitType.REFERRAL_FOLLOWUP -> VisitState.PENDING_REFERRAL
  },
  pada = pada,
  scheduleDate = scheduleDate,
  visitLabel = visitLabel,
  daysRemaining = daysRemaining,
  phoneNumber = phoneNumber.orEmpty(),
  journeyCompletedIn = null,
  isAssessed = riskLevel != null,
)
