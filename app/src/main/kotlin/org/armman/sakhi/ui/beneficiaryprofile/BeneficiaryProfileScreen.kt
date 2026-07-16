package org.armman.sakhi.ui.beneficiaryprofile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisit
import org.armman.sakhi.data.beneficiaryprofile.ProfileVisitAction
import org.armman.sakhi.ui.beneficiaryprofile.components.IdentityCard
import org.armman.sakhi.ui.beneficiaryprofile.components.LastVisitStatsCard
import org.armman.sakhi.ui.beneficiaryprofile.components.VisitHistoryCard
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.SerifTitle
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Beneficiary Profile detail per the "Beneficiary Profile Page" Figma board
 * (Mother + Child variants, mobile + tablet). CR-014a: identity card, state,
 * diagnosis and Last Visit Stats. The See Visits list + form actions land in
 * CR-014b; Edit is a stub ("coming soon") until the edit flow exists.
 */
@Composable
fun BeneficiaryProfileScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  onStartVisit: (beneficiaryId: String, visit: ProfileVisit) -> Unit = { _, _ -> },
  viewModel: BeneficiaryProfileViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val context = LocalContext.current
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }
  val comingSoon = stringResource(R.string.beneficiary_profile_coming_soon)
  val onComingSoon = { Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show() }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.beneficiary_profile_back_title),
        subtitle = today,
        onBack = onBack,
        onAvatarClick = onProfile,
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> LoadError(onRetry = viewModel::loadProfile)
          else -> state.profile?.let { profile ->
            ProfileContent(
              profile = profile,
              isTablet = isTablet,
              onComingSoon = { onComingSoon() },
              onStartVisit = { visit -> onStartVisit(profile.id, visit) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProfileContent(
  profile: BeneficiaryProfile,
  isTablet: Boolean,
  onComingSoon: () -> Unit,
  onStartVisit: (ProfileVisit) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    // Scrollable body; the Delivery/Closure footer stays pinned below it.
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(Dimens.ItemSpacing),
    ) {
      IdentityCard(profile = profile, isTablet = isTablet, onEdit = onComingSoon)
      LastVisitStatsCard(
        stats = profile.lastVisitStats,
        isTablet = isTablet,
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      )
      if (profile.visits.isNotEmpty()) {
        Text(
          text = stringResource(R.string.beneficiary_profile_see_visits),
          style = SerifTitle,
          color = NeutralG400,
          modifier = Modifier.padding(top = Dimens.ScreenPadding),
        )
        profile.visits.forEach { visit ->
          VisitHistoryCard(
            visit = visit,
            isTablet = isTablet,
            // CR-016: Start Visit / Fill Form now open the Visit Form flow;
            // See Data / Referral remain stubbed until their own CRs land.
            onAction = {
              if (visit.action == ProfileVisitAction.START_VISIT ||
                visit.action == ProfileVisitAction.FILL_FORM
              ) {
                onStartVisit(visit)
              } else {
                onComingSoon()
              }
            },
            modifier = Modifier.padding(top = Dimens.ItemSpacing),
          )
        }
      }
    }
    Footer(onComingSoon = onComingSoon)
  }
}

/** Pinned Delivery Form / Closure Form actions (stubbed until those flows exist). */
@Composable
private fun Footer(onComingSoon: () -> Unit) {
  Column {
    HorizontalDivider(color = NeutralG50)
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.fillMaxWidth().padding(Dimens.ItemSpacing),
    ) {
      SecondaryButton(
        text = stringResource(R.string.beneficiary_profile_delivery_form),
        onClick = onComingSoon,
        modifier = Modifier.weight(1f),
      )
      SecondaryButton(
        text = stringResource(R.string.beneficiary_profile_closure_form),
        onClick = onComingSoon,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
}

@Composable
private fun LoadError(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.beneficiary_profile_error_load),
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
