package org.armman.sakhi.ui.visitform

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.visitform.VisitDataSubTab
import org.armman.sakhi.data.visitform.VisitFormStep
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.visitform.steps.HistorySubTab
import org.armman.sakhi.ui.visitform.steps.SummaryStep
import org.armman.sakhi.ui.visitform.steps.SymptomsSubTab
import org.armman.sakhi.ui.visitform.steps.TestsSubTab
import org.armman.sakhi.ui.visitform.steps.VisitDataActions
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ANC Visit Form (CR-016) — 4-tab stepper (Visit Data → Summary → Health Info
 * → Referral). CR-016a scaffolds the shell, tab gating and discard-on-exit
 * (FR-S-4.2); field content lands with 016b/c/d.
 */
@Composable
fun VisitFormScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  viewModel: VisitFormViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val comingSoon = stringResource(R.string.visit_form_coming_soon)
  var showExitDialog by remember { mutableStateOf(false) }
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        VisitFormEvent.ExitForm -> onBack()
        VisitFormEvent.ComingSoon -> Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show()
      }
    }
  }

  // Mid-flow back must confirm (FR-S-4.2: no partial save — draft would be lost).
  BackHandler { showExitDialog = true }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.visit_form_back_title),
        subtitle = today,
        onBack = { showExitDialog = true },
        onAvatarClick = onProfile,
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> LoadError(onRetry = viewModel::loadContext)
          else -> VisitFormBody(
            visitLabel = viewModel.visitLabel,
            state = state,
            viewModel = viewModel,
          )
        }
      }
    }
  }

  if (showExitDialog) {
    ExitConfirmationDialog(
      onConfirm = {
        showExitDialog = false
        viewModel.exitForm()
      },
      onDismiss = { showExitDialog = false },
    )
  }

  // FR-S-4.4 Option B: no confirm dialog — closing this banner immediately
  // discards the draft and exits (VD-UI-5).
  state.criticalCondition?.let { condition ->
    CriticalConditionBanner(
      message = stringResource(condition.messageRes),
      onDismiss = viewModel::dismissCritical,
    )
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
      text = stringResource(R.string.visit_form_error_load),
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

/** Full-width "Immediate Urgency" dialog (FR-S-4.4) — dismiss discards & exits. */
@Composable
private fun CriticalConditionBanner(message: String, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(Dimens.CardRadius), color = White) {
      Column(modifier = Modifier.padding(Dimens.ScreenPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            painter = painterResource(R.drawable.ic_warning_circle),
            contentDescription = null,
            tint = RiskHigh,
            modifier = Modifier.size(24.dp),
          )
          Text(
            text = stringResource(R.string.visit_form_critical_title),
            style = MaterialTheme.typography.titleLarge,
            color = RiskHigh,
            modifier = Modifier.padding(start = Dimens.SmallSpacing),
          )
        }
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG400,
          modifier = Modifier.padding(top = Dimens.SmallSpacing),
        )
        PrimaryButton(
          text = stringResource(R.string.visit_form_critical_close),
          onClick = onDismiss,
          modifier = Modifier.padding(top = Dimens.ItemSpacing),
        )
      }
    }
  }
}

@Composable
private fun VisitFormBody(
  visitLabel: String,
  state: VisitFormUiState,
  viewModel: VisitFormViewModel,
) {
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val stepTabs = listOf(
    stringResource(R.string.visit_form_tab_visit_data),
    stringResource(R.string.visit_form_tab_summary),
    stringResource(R.string.visit_form_tab_health_info),
    stringResource(R.string.visit_form_tab_referral),
  )
  val hPadding = if (isTablet) Dimens.ScreenPaddingTablet else Dimens.ScreenPadding
  val scrollState = rememberScrollState()

  // Always land at the top of the new tab/sub-tab's content — the previous
  // scroll offset (e.g. scrolled down to tap "Symptoms") otherwise carries
  // over verbatim, since this Column and its ScrollState persist across the
  // `when` branch swap below. Validation-failure scrolling (auto-scroll to
  // the first missing field) is separate and only fires on a *blocked*
  // Next tap, which never reaches this — see [scrollToIfMissing].
  LaunchedEffect(state.currentStep, state.visitDataSubTab) {
    scrollState.scrollTo(0)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(scrollState)
        .padding(horizontal = hPadding),
    ) {
      Text(
        text = stringResource(R.string.visit_form_title, visitLabel),
        style = SerifTitleLarge,
        color = NeutralG400,
        modifier = Modifier.padding(top = Dimens.ScreenPadding),
      )
      AppTabRow(
        tabs = stepTabs,
        selectedIndex = state.currentStep.ordinal,
        onTabSelected = { index -> viewModel.goToStep(VisitFormStep.entries[index]) },
        distributeEvenly = true,
        indicatorOverhang = Dimens.TabIndicatorOverhang,
        modifier = Modifier.padding(top = Dimens.ItemSpacing),
      )
      when (state.currentStep) {
        VisitFormStep.VISIT_DATA -> {
          VisitDataSubTabRow(
            selected = state.visitDataSubTab,
            onSelect = viewModel::goToSubTab,
            modifier = Modifier.padding(top = Dimens.ItemSpacing),
          )
          VisitDataStepContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(vertical = Dimens.ScreenPadding),
          )
        }
        VisitFormStep.SUMMARY -> SummaryStep(
          riskLevel = state.summaryRiskLevel,
          comorbidities = state.comorbidities,
          testsFindings = state.summaryTestsFindings,
          symptomsFindings = state.summarySymptomsFindings,
          onEditTests = viewModel::editTests,
          onEditSymptoms = viewModel::editSymptoms,
          modifier = Modifier.padding(vertical = Dimens.ScreenPadding),
        )
        else -> StepPendingPlaceholder(modifier = Modifier.padding(vertical = Dimens.ScreenPadding))
      }
    }
    StepFooter(state = state, viewModel = viewModel, hPadding = hPadding)
  }
}

/** Dispatches to the Tests/Symptoms/History sub-tab bodies (Q1–56, CR-016b). */
@Composable
private fun VisitDataStepContent(
  state: VisitFormUiState,
  viewModel: VisitFormViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  // Live capture into app-private storage (mirrors Enrollment's consent photo —
  // no gallery access). remember{} reuses one target file per composition.
  val photoUri = remember {
    val photoFile = File(File(context.filesDir, "visitform"), "sonography_report.jpg")
      .apply { parentFile?.mkdirs() }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
  }
  val takePictureLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture(),
  ) { success ->
    if (success) viewModel.onSonographyPhotoCaptured(photoUri.toString())
  }

  val actions = VisitDataActions(
    onVisitDate = viewModel::setVisitDate,
    onMetBeneficiary = viewModel::setMetBeneficiary,
    onNotMetReason = viewModel::setNotMetReason,
    onRchNumber = viewModel::setRchNumber,
    onLmp = viewModel::setLmp,
    onHasSonographyReport = viewModel::setHasSonographyReport,
    onSonographyPhoto = { takePictureLauncher.launch(photoUri) },
    onHeight = viewModel::setHeightCm,
    onWeight = viewModel::setWeightKg,
    onMuac = viewModel::setMuacCm,
    onBpSystolic = viewModel::setBpSystolic,
    onBpDiastolic = viewModel::setBpDiastolic,
    onTemperature = viewModel::setTemperatureF,
    onHemoglobin = viewModel::setHemoglobin,
    onConfirmHemoglobin = viewModel::confirmHemoglobin,
    onLastMealHours = viewModel::setLastMealHours,
    onBloodGlucose = viewModel::setBloodGlucose,
    onUrineTest = viewModel::toggleUrineTest,
    onFetalMovements = viewModel::setFetalMovements,
    onFetalHeartRate = viewModel::setFetalHeartRate,
    onFundalHeight = viewModel::setFundalHeightCm,
    onDangerSign = viewModel::toggleDangerSign,
    onPalmNails = viewModel::setPalmNails,
    onSclera = viewModel::setSclera,
    onSkin = viewModel::setSkin,
    onSwelling = viewModel::toggleSwelling,
    onDehydration = viewModel::toggleDehydration,
    onTdNone = viewModel::setTdNone,
    onTd1 = viewModel::setTd1,
    onTd1Date = viewModel::setTd1Date,
    onTd2 = viewModel::setTd2,
    onTd2Date = viewModel::setTd2Date,
    onTdBooster = viewModel::setTdBooster,
    onTdBoosterDate = viewModel::setTdBoosterDate,
    onFoodConsumed = viewModel::toggleFoodConsumed,
    onFoodAvoided = viewModel::toggleFoodAvoided,
    onTakingIfa = viewModel::setTakingIfa,
    onIfaTablets = viewModel::setIfaTabletsConsumed,
    onIfaReason = viewModel::toggleIfaNonConsumptionReason,
    onCalcium = viewModel::setCalciumTaken,
    onVisitedFacility = viewModel::setVisitedFacilitySinceLastVisit,
    onLastAncDate = viewModel::setLastAncVisitDate,
    onDeliveryPlace = viewModel::setAdvisedDeliveryPlace,
    onSickleCell = viewModel::setSickleCell,
    onFamilyPlanning = viewModel::toggleFamilyPlanningMethod,
    onFeelingStressed = viewModel::setFeelingStressed,
    onFamilySupport = viewModel::setAdequateFamilySupport,
    onPlanningMigration = viewModel::setPlanningMigration,
    onUsgDone = viewModel::setUsgDone,
    onUsgDate = viewModel::setUsgDate,
    onUsgType = viewModel::setUsgType,
    onUsgFinding = viewModel::setUsgFinding,
    onTransportShared = viewModel::setTransportContactShared,
    onFundsArranged = viewModel::setFundsArranged,
    onBirthCompanion = viewModel::setBirthCompanionIdentified,
    onRemarks = viewModel::setRemarks,
    onCounsellingTopic = viewModel::toggleCounsellingTopic,
  )

  when (state.visitDataSubTab) {
    VisitDataSubTab.TESTS -> TestsSubTab(state = state.visitData, actions = actions, modifier = modifier)
    VisitDataSubTab.SYMPTOMS -> SymptomsSubTab(state = state.visitData, actions = actions, modifier = modifier)
    VisitDataSubTab.HISTORY -> HistorySubTab(state = state.visitData, actions = actions, modifier = modifier)
  }
}

/** Tests/Symptoms/History pill sub-tabs for the Visit Data step. */
@Composable
private fun VisitDataSubTabRow(
  selected: VisitDataSubTab,
  onSelect: (VisitDataSubTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val labels = mapOf(
    VisitDataSubTab.TESTS to stringResource(R.string.visit_form_subtab_tests),
    VisitDataSubTab.SYMPTOMS to stringResource(R.string.visit_form_subtab_symptoms),
    VisitDataSubTab.HISTORY to stringResource(R.string.visit_form_subtab_history),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing), modifier = modifier) {
    labels.forEach { (tab, label) ->
      val isSelected = tab == selected
      val shape = RoundedCornerShape(Dimens.ChipHeight / 2)
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (isSelected) Primary else NeutralG400,
        modifier = Modifier
          .clip(shape)
          .background(if (isSelected) PrimarySurface else White)
          .let { if (isSelected) it else it.border(1.dp, NeutralG75, shape) }
          .clickable { onSelect(tab) }
          .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.SmallSpacing),
      )
    }
  }
}

/** CR-016a placeholder body — real field content lands with 016b/c/d. */
@Composable
private fun StepPendingPlaceholder(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.visit_form_step_pending),
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
    )
  }
}

/** Footer bar: hairline divider + right-aligned forward action (per design). */
@Composable
private fun StepFooter(state: VisitFormUiState, viewModel: VisitFormViewModel, hPadding: Dp) {
  Column(modifier = Modifier.fillMaxWidth()) {
    HorizontalDivider(color = NeutralG50)
    Row(
      horizontalArrangement = Arrangement.End,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = hPadding, vertical = Dimens.ItemSpacing),
    ) {
      when (state.currentStep) {
        // First step: no Back button (mirrors Enrollment's Consent step) — the
        // header/system back handles exit via the confirmation dialog instead.
        // Visit Data has its own Tests → Symptoms → History → Summary chain —
        // each sub-tab's Next is gated on that sub-tab alone (jumping between
        // pills directly is still possible; goToSummary re-checks everything).
        VisitFormStep.VISIT_DATA -> when (state.visitDataSubTab) {
          VisitDataSubTab.TESTS -> PrimaryButton(
            text = stringResource(R.string.visit_form_subtab_symptoms),
            onClick = viewModel::goToSymptoms,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
          VisitDataSubTab.SYMPTOMS -> PrimaryButton(
            text = stringResource(R.string.visit_form_subtab_history),
            onClick = viewModel::goToHistory,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
          VisitDataSubTab.HISTORY -> PrimaryButton(
            text = stringResource(R.string.visit_form_next_summary),
            onClick = viewModel::goToSummary,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
        VisitFormStep.SUMMARY -> {
          SecondaryButton(
            text = stringResource(R.string.visit_form_back),
            onClick = viewModel::goBack,
            height = Dimens.SmallButtonHeight,
          )
          Spacer(modifier = Modifier.weight(1f))
          PrimaryButton(
            text = stringResource(R.string.visit_form_next_health_info),
            onClick = viewModel::goToHealthInfo,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
        VisitFormStep.HEALTH_INFO -> {
          SecondaryButton(
            text = stringResource(R.string.visit_form_back),
            onClick = viewModel::goBack,
            height = Dimens.SmallButtonHeight,
          )
          Spacer(modifier = Modifier.weight(1f))
          PrimaryButton(
            text = stringResource(R.string.visit_form_next_referral),
            onClick = viewModel::goToReferral,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
        VisitFormStep.REFERRAL -> {
          SecondaryButton(
            text = stringResource(R.string.visit_form_back),
            onClick = viewModel::goBack,
            height = Dimens.SmallButtonHeight,
          )
          Spacer(modifier = Modifier.weight(1f))
          // 016d ships real submit/save; today this is a stubbed "coming soon".
          PrimaryButton(
            text = stringResource(R.string.visit_form_submit),
            onClick = viewModel::onSubmit,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
      }
    }
  }
}

/** Exit confirmation — mid-flow back would discard the in-memory draft (FR-S-4.2). */
@Composable
private fun ExitConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(Dimens.CardRadius), color = White) {
      Column(modifier = Modifier.padding(Dimens.ScreenPadding)) {
        Text(
          text = stringResource(R.string.visit_form_exit_title),
          style = MaterialTheme.typography.titleLarge,
          color = NeutralG400,
        )
        Text(
          text = stringResource(R.string.visit_form_exit_message),
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
          modifier = Modifier.padding(top = Dimens.SmallSpacing),
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing, Alignment.End),
          modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
        ) {
          TextButton(onClick = onDismiss) {
            Text(
              text = stringResource(R.string.visit_form_exit_cancel),
              style = MaterialTheme.typography.labelLarge,
            )
          }
          SecondaryButton(
            text = stringResource(R.string.visit_form_exit_confirm),
            onClick = onConfirm,
            height = Dimens.SmallButtonHeight,
          )
        }
      }
    }
  }
}
