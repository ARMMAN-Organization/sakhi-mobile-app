package org.armman.sakhi.ui.previsithealthhistory

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistory
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.ConditionChip
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.components.SummaryCard
import org.armman.sakhi.ui.previsithealthhistory.components.RiskFactorCard
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pre-Visit Health History (FR-S-4.6) — read-only clinical reference shown
 * before a Sakhi begins a Visit Form, once at least one prior visit exists.
 * CR-016a.
 */
@Composable
fun PreVisitHealthHistoryScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  onSeeProfile: () -> Unit,
  onStartVisit: () -> Unit,
  viewModel: PreVisitHealthHistoryViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        PreVisitHealthHistoryEvent.NavigateToProfile -> onSeeProfile()
        PreVisitHealthHistoryEvent.NavigateToVisitForm -> onStartVisit()
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.previsit_back_title),
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
          state.hasError -> LoadError(onRetry = viewModel::load)
          state.profile != null && state.history != null -> Content(
            profile = state.profile!!,
            history = state.history!!,
            isTablet = isTablet,
            onSeeProfile = viewModel::onSeeProfile,
            onStartVisit = viewModel::onStartVisit,
          )
        }
      }
    }
  }
}

@Composable
private fun Content(
  profile: BeneficiaryProfile,
  history: PreVisitHealthHistory,
  isTablet: Boolean,
  onSeeProfile: () -> Unit,
  onStartVisit: () -> Unit,
) {
  val hPadding = if (isTablet) Dimens.ScreenPaddingTablet else Dimens.ItemSpacing
  Column(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = hPadding, vertical = Dimens.ItemSpacing),
    ) {
      Header(profile = profile)
      SummaryCard(
        title = stringResource(R.string.previsit_summary_title),
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
          modifier = Modifier.fillMaxWidth().padding(top = Dimens.SmallSpacing),
        ) {
          LabelledTile(
            label = stringResource(R.string.beneficiary_profile_label_lmp),
            value = profile.lmp.orEmpty(),
            modifier = Modifier.weight(1f),
          )
          LabelledTile(
            label = stringResource(R.string.beneficiary_profile_label_edd),
            value = profile.edd.orEmpty(),
            modifier = Modifier.weight(1f),
          )
        }
        if (profile.diagnoses.isNotEmpty()) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
            modifier = Modifier.padding(top = Dimens.SmallSpacing),
          ) {
            profile.diagnoses.forEach { condition -> ConditionChip(condition) }
          }
        }
      }
      history.riskFactors.forEach { factor ->
        RiskFactorCard(factor = factor, modifier = Modifier.padding(top = Dimens.ItemSpacing))
      }
      history.nonRiskVitals.forEach { vital ->
        RiskFactorCard(factor = vital, modifier = Modifier.padding(top = Dimens.ItemSpacing))
      }
    }
    Footer(onSeeProfile = onSeeProfile, onStartVisit = onStartVisit, hPadding = hPadding)
  }
}

@Composable
private fun Header(profile: BeneficiaryProfile) {
  Column {
    Text(
      text = stringResource(R.string.previsit_name_age, profile.name, profile.ageLabel),
      style = SerifTitleLarge,
      color = NeutralG400,
    )
    InfoRow(
      label = stringResource(R.string.previsit_village_pada, profile.village, profile.pada),
      modifier = Modifier.padding(top = Dimens.SmallSpacing),
    )
    InfoRow(stringResource(R.string.previsit_husband_name, profile.husbandName))
    InfoRow(stringResource(R.string.previsit_mobile_no, profile.mobileNumber))
  }
}

@Composable
private fun InfoRow(label: String, modifier: Modifier = Modifier) {
  Text(
    text = label,
    style = MaterialTheme.typography.bodyMedium,
    color = NeutralG200,
    modifier = modifier.padding(top = Dimens.LabelValueGap),
  )
}


@Composable
private fun LabelledTile(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(Dimens.SmallSpacing)) {
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = NeutralG200)
    Text(text = value, style = MaterialTheme.typography.titleMedium, color = NeutralG400)
  }
}

/** Pinned "See Profile" (secondary) + "Start Visit →" (primary) footer. */
@Composable
private fun Footer(onSeeProfile: () -> Unit, onStartVisit: () -> Unit, hPadding: Dp) {
  Column {
    HorizontalDivider(color = NeutralG50)
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = hPadding, vertical = Dimens.ItemSpacing),
    ) {
      SecondaryButton(
        text = stringResource(R.string.previsit_see_profile),
        onClick = onSeeProfile,
        modifier = Modifier.weight(1f),
      )
      PrimaryButton(
        text = stringResource(R.string.previsit_start_visit),
        onClick = onStartVisit,
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
      text = stringResource(R.string.previsit_error_load),
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
