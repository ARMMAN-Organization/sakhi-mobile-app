package org.armman.sakhi.ui.visitform

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.visitform.InfantVisitRiskFinding
import org.armman.sakhi.data.visitform.VisitFormOuterTab
import org.armman.sakhi.data.visitform.VisitFormQuestionCodes
import org.armman.sakhi.data.visitform.VisitFormRiskFinding
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.ChoiceChip
import org.armman.sakhi.ui.components.ConditionChip
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.RiskBadge
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.forms.DynamicFormField
import org.armman.sakhi.ui.forms.FormSectionScroll
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Screen for the schema-driven ANC_VISIT/INFANT_VISIT Visit Form — replaces the retired hand-coded
 * [org.armman.sakhi.ui.visitform.VisitFormScreen]. Keeps that screen's fixed outer Visit Data /
 * Summary / Health Info / Referral tab shell ([VisitFormOuterTab]), but the fields under each tab
 * now come from whichever schema section(s) [DynamicVisitFormViewModel.subSections] maps onto it
 * instead of hand-coded composables — see that enum's doc for the ANC_VISIT vs INFANT_VISIT
 * mapping. Back/Next flattens sub-tabs and outer tabs into one continuous chain, same as the
 * retired flow's `VisitDataSubTab` → `VisitFormStep` progression.
 *
 * External signature (`onBack`/`onProfile`) is unchanged from the retired screen so
 * `AppNavHost`'s composable call site needed only a one-line swap.
 */
@Composable
fun DynamicVisitFormScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  /** CR-M3-06: called instead of [onBack] when the just-submitted visit graded at least one
   * `isEducationTrigger` condition — (beneficiaryId, conditionCodes). Defaults to falling
   * straight back to [onBack] for any caller that hasn't wired Health Education yet. */
  onSubmittedNeedsEducation: (String, List<String>) -> Unit = { _, _ -> onBack() },
  /** CR-Closure-03/CR-Closure-01 items #5/#6: called instead of [onBack]/[onSubmittedNeedsEducation]
   * when the just-submitted visit forces a same-session closure prompt — either PP5 (see
   * [DynamicVisitFormUiState.triggersClosurePrompt]) or the last CCV visit with no HR detected
   * (see [DynamicVisitFormUiState.triggersChildClosurePrompt]) — (beneficiaryId, isChildClosure).
   * `isChildClosure` picks which closure form the caller should force open: `false` for the PP5/
   * mother case, `true` for the CCV/child case. Defaults to [onBack] for any caller that hasn't
   * wired the forced-closure hand-off yet. */
  onSubmittedTriggersClosure: (String, Boolean) -> Unit = { _, _ -> onBack() },
  viewModel: DynamicVisitFormViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val comingSoon = stringResource(R.string.visit_form_coming_soon)
  val submitted = stringResource(R.string.visit_form_submitted)
  val queuedOffline = stringResource(R.string.visit_form_queued_offline)
  var showExitDialog by remember { mutableStateOf(false) }
  var selectedOuterTab by remember { mutableStateOf(VisitFormOuterTab.VISIT_DATA) }
  var selectedSection by remember { mutableStateOf<String?>(null) }
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        DynamicVisitFormEvent.ExitForm -> onBack()
        DynamicVisitFormEvent.ComingSoon -> Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show()
        DynamicVisitFormEvent.Submitted -> {
          Toast.makeText(context, submitted, Toast.LENGTH_SHORT).show()
          // CR-Closure-01 items #5/#6: HR detected at the last CCV visit — tell the Sakhi the
          // extension window before routeAfterSubmit below silently exits on it (see that
          // function's own doc for why this case has nothing further to route into this session).
          viewModel.uiState.value.ccvHrExtensionWindow?.let { window ->
            Toast.makeText(
              context,
              context.getString(
                R.string.visit_form_ccv_hr_extension_deferred,
                window.windowStartDate.orEmpty(),
                window.windowEndDate.orEmpty(),
              ),
              Toast.LENGTH_LONG,
            ).show()
          }
          // Read viewModel.uiState.value directly, NOT the composable-scoped `state` — this
          // LaunchedEffect(viewModel) never restarts (its key never changes), so a captured
          // `state` reference here would be pinned to whatever it was on first composition, not
          // the goRulesRiskResult DynamicVisitFormViewModel.onFinish just wrote moments ago.
          routeAfterSubmit(
            viewModel.uiState.value,
            viewModel.beneficiaryId,
            onSubmittedNeedsEducation,
            onSubmittedTriggersClosure,
            onBack,
          )
        }
        DynamicVisitFormEvent.QueuedOffline -> {
          Toast.makeText(context, queuedOffline, Toast.LENGTH_LONG).show()
          routeAfterSubmit(
            viewModel.uiState.value,
            viewModel.beneficiaryId,
            onSubmittedNeedsEducation,
            onSubmittedTriggersClosure,
            onBack,
          )
        }
        is DynamicVisitFormEvent.SubmitFailed ->
          Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
      }
    }
  }

  // Once the schema loads, land on VISIT_DATA's first mapped section (its only section for
  // ANC_VISIT, or "Tests" for INFANT_VISIT) — mirrors the retired flow's default
  // `VisitDataSubTab.TESTS` start state.
  LaunchedEffect(state.formCode) {
    if (state.formCode != null && selectedSection == null) {
      selectedSection = viewModel.subSections(VisitFormOuterTab.VISIT_DATA).firstOrNull()
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
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> LoadError(onRetry = viewModel::load)
          // CR-Referral-01 Pass 4: swaps the whole body for the referral capture step - see
          // DynamicVisitFormUiState.showReferralCaptureStep's doc for when/why this is set.
          state.showReferralCaptureStep -> ReferralCaptureStep(state = state, viewModel = viewModel)
          else -> VisitFormBody(
            visitLabel = viewModel.visitLabel,
            state = state,
            viewModel = viewModel,
            selectedOuterTab = selectedOuterTab,
            selectedSection = selectedSection,
            onSelect = { tab, section -> selectedOuterTab = tab; selectedSection = section },
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

  // FR-S-4.4 Option B (mother/ANC_VISIT only — see DynamicVisitFormUiState.criticalCondition's
  // doc): no confirm dialog, closing this banner immediately discards the draft and exits.
  state.criticalCondition?.let { condition ->
    CriticalConditionBanner(
      message = stringResource(condition.messageRes),
      onDismiss = viewModel::dismissCritical,
    )
  }

  // CR-M3-06 requirement #4.
  if (state.activeEducationHintField != null) {
    LearnMoreHintDialog(
      topic = state.activeEducationHintTopic,
      onDismiss = viewModel::dismissEducationHint,
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

/** One flattened step in the Back/Next chain: an outer tab plus (for VISIT_DATA — the only outer
 * tab any schema section maps onto this pass) the section it shows. `section == null` means the
 * outer tab has nothing mapped and renders [BlankTabPlaceholder]. */
private data class VisitFormFlatStep(val outerTab: VisitFormOuterTab, val section: String?)

// CR-Referral-01 Pass 4 (2026-08-27): REFERRAL deliberately excluded -- referral capture is now a
// conditional step (see ReferralCaptureStep / DynamicVisitFormUiState.showReferralCaptureStep),
// not a tab in this fixed order. The enum value itself stays (VisitFormOuterTab's own doc
// explains why) but nothing in this list ever selects it, so outerTabLabel/nextOuterTabLabel's
// REFERRAL branches below are unreachable dead code, kept only so those `when`s stay exhaustive.
private val OUTER_TAB_ORDER = listOf(
  VisitFormOuterTab.VISIT_DATA,
  VisitFormOuterTab.SUMMARY,
  VisitFormOuterTab.HEALTH_INFO,
)

@Composable
private fun outerTabLabel(tab: VisitFormOuterTab): String = when (tab) {
  VisitFormOuterTab.VISIT_DATA -> stringResource(R.string.visit_form_tab_visit_data)
  VisitFormOuterTab.SUMMARY -> stringResource(R.string.visit_form_tab_summary)
  VisitFormOuterTab.HEALTH_INFO -> stringResource(R.string.visit_form_tab_health_info)
  VisitFormOuterTab.REFERRAL -> stringResource(R.string.visit_form_tab_referral)
}

/** The static "Next: <outer tab>" label used when the next flat step crosses into a new outer
 * tab — [VisitFormOuterTab.VISIT_DATA] never appears here since it's always the first step. */
@Composable
private fun nextOuterTabLabel(tab: VisitFormOuterTab): String = when (tab) {
  VisitFormOuterTab.SUMMARY -> stringResource(R.string.visit_form_next_summary)
  VisitFormOuterTab.HEALTH_INFO -> stringResource(R.string.visit_form_next_health_info)
  VisitFormOuterTab.REFERRAL -> stringResource(R.string.visit_form_next_referral)
  VisitFormOuterTab.VISIT_DATA -> stringResource(R.string.visit_form_tab_visit_data)
}

@Composable
private fun VisitFormBody(
  visitLabel: String,
  state: DynamicVisitFormUiState,
  viewModel: DynamicVisitFormViewModel,
  selectedOuterTab: VisitFormOuterTab,
  selectedSection: String?,
  onSelect: (VisitFormOuterTab, String?) -> Unit,
) {
  val flatSteps = OUTER_TAB_ORDER.flatMap { tab ->
    val secs = viewModel.subSections(tab)
    if (secs.isEmpty()) listOf(VisitFormFlatStep(tab, null)) else secs.map { VisitFormFlatStep(tab, it) }
  }
  val currentIndex = flatSteps
    .indexOfFirst { it.outerTab == selectedOuterTab && it.section == selectedSection }
    .coerceAtLeast(0)
  val previousStep = flatSteps.getOrNull(currentIndex - 1)
  val nextStep = flatSteps.getOrNull(currentIndex + 1)
  val isLastStep = currentIndex == flatSteps.lastIndex

  // Pill sub-tabs only show when the active outer tab has more than one mapped section. Every
  // schema section maps onto VISIT_DATA this pass (see VisitFormOuterTab's doc), so SUMMARY/
  // HEALTH_INFO/REFERRAL always have zero and render the blank placeholder below instead.
  val subSectionsForOuterTab = viewModel.subSections(selectedOuterTab)

  Column(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.visit_form_title, visitLabel),
        style = SerifTitleLarge,
        color = NeutralG400,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
      )
      AppTabRow(
        tabs = OUTER_TAB_ORDER.map { outerTabLabel(it) },
        selectedIndex = OUTER_TAB_ORDER.indexOf(selectedOuterTab),
        onTabSelected = { index ->
          val tab = OUTER_TAB_ORDER[index]
          onSelect(tab, viewModel.subSections(tab).firstOrNull())
        },
        distributeEvenly = true,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
      )
      if (subSectionsForOuterTab.size > 1) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
          modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SmallSpacing),
        ) {
          subSectionsForOuterTab.forEach { section ->
            ChoiceChip(
              text = section,
              selected = section == selectedSection,
              onClick = { onSelect(selectedOuterTab, section) },
            )
          }
        }
      }
      Box(modifier = Modifier.weight(1f)) {
        when {
          selectedSection != null -> DynamicVisitFormFieldList(
            fields = viewModel.fieldsInSection(selectedSection),
            state = state,
            viewModel = viewModel,
            sectionKey = "${selectedOuterTab.name}:$selectedSection",
          )
          // Mother (ANC_VISIT) Summary: risk banner + Tests findings card - see SummaryTabContent's
          // doc. INFANT_VISIT gets its own, narrower Summary branch just below (2026-08-08) - known
          // risks only, no vitals findings card (no risk model exists yet for infant vitals).
          selectedOuterTab == VisitFormOuterTab.SUMMARY && state.formCode == FORM_CODE_MOTHER -> SummaryTabContent(
            state = state,
            viewModel = viewModel,
            onEditVisitData = {
              val firstSection = viewModel.subSections(VisitFormOuterTab.VISIT_DATA).firstOrNull()
              onSelect(VisitFormOuterTab.VISIT_DATA, firstSection)
            },
          )
          selectedOuterTab == VisitFormOuterTab.SUMMARY && state.formCode in FORM_CODES_INFANT_FAMILY -> InfantSummaryTabContent(
            state = state,
            viewModel = viewModel,
          )
          // Every other form code's Summary tab (POSTPARTUM_VISIT/PP1..PPn, NEONATAL_VISIT/NN1-NN2,
          // and any future code with no bespoke Summary view of its own) — plain "filled fields"
          // review, same shape as DeliverySessionScreen's own Summary tab. See
          // FieldSummaryTabContent's doc.
          selectedOuterTab == VisitFormOuterTab.SUMMARY -> FieldSummaryTabContent(
            state = state,
            viewModel = viewModel,
            onEditVisitData = {
              val firstSection = viewModel.subSections(VisitFormOuterTab.VISIT_DATA).firstOrNull()
              onSelect(VisitFormOuterTab.VISIT_DATA, firstSection)
            },
          )
          else -> BlankTabPlaceholder()
        }
      }
    }
    HorizontalDivider(color = NeutralG50)
    Row(
      horizontalArrangement = Arrangement.End,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
    ) {
      if (previousStep != null) {
        SecondaryButton(
          text = stringResource(R.string.visit_form_back),
          onClick = { onSelect(previousStep.outerTab, previousStep.section) },
          height = Dimens.SmallButtonHeight,
        )
        Spacer(modifier = Modifier.weight(1f))
      }
      // Gates Next/Submit on required fields, mirroring the Mother Registration form's
      // isSectionReady()/isReadyToSubmit() pattern: a sub-tab with no schema section (SUMMARY,
      // the outer HEALTH_INFO placeholder, REFERRAL) has nothing to require, so it stays enabled.
      val canProceed = if (isLastStep) {
        viewModel.isReadyToSubmit() && !state.isSubmitting
      } else {
        selectedSection?.let(viewModel::isSectionReady) ?: true
      }
      PrimaryButton(
        text = when {
          isLastStep -> stringResource(R.string.visit_form_submit)
          nextStep!!.outerTab == selectedOuterTab -> nextStep.section.orEmpty()
          else -> nextOuterTabLabel(nextStep.outerTab)
        },
        enabled = canProceed,
        loading = isLastStep && state.isSubmitting,
        onClick = {
          if (isLastStep) viewModel.onFinish() else onSelect(nextStep!!.outerTab, nextStep.section)
        },
        fullWidth = false,
        height = Dimens.SmallButtonHeight,
        trailingIcon = painterResource(R.drawable.ic_arrow_right),
      )
    }
  }
}

@Composable
private fun BlankTabPlaceholder() {
  Box(modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding)) {
    Text(
      text = stringResource(R.string.visit_form_step_pending),
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG200,
    )
  }
}

/**
 * Mother (ANC_VISIT) Summary outer tab (CR-016c, re-wired onto the dynamic ViewModel
 * 2026-08-08): risk banner (comorbidity chips + overall [RiskBadge]) then a Tests card with one
 * row per [VisitFormRiskFinding] — ported from the retired hand-coded Summary tab's layout (now
 * in `_to_delete/`), minus the Symptoms card (no findings built for it this pass). ANC_VISIT
 * only — see [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.testsFindings]'s doc.
 * INFANT_VISIT has its own, separate [InfantSummaryTabContent] (2026-08-08) — kept as a distinct
 * composable rather than reusing this one because it shows known risks only, no vitals findings
 * card (no risk model exists yet for infant vitals like weight/temperature/MUAC).
 */
@Composable
private fun SummaryTabContent(
  state: DynamicVisitFormUiState,
  viewModel: DynamicVisitFormViewModel,
  onEditVisitData: () -> Unit,
) {
  val findings = remember(state.answers, state.formCode) { viewModel.testsFindings() }
  val overallRisk = remember(state.answers, state.formCode, state.comorbidities) { viewModel.overallRiskLevel() }
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(Dimens.ScreenPadding),
  ) {
    SummaryRiskBanner(riskLevel = overallRisk, comorbidities = state.comorbidities)
    if (findings.isNotEmpty()) {
      SummaryFindingsCard(findings = findings, onEdit = onEditVisitData)
    }
  }
}

@Composable
private fun SummaryRiskBanner(riskLevel: RiskLevel, comorbidities: List<String>) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(NeutralG50)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = stringResource(R.string.visit_form_vs_risk_identified),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      RiskBadge(riskLevel = riskLevel)
    }
    if (comorbidities.isEmpty()) {
      Text(
        text = stringResource(R.string.visit_form_vs_no_conditions),
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      )
    } else {
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      ) {
        comorbidities.forEach { condition -> ConditionChip(condition) }
      }
    }
  }
}

@Composable
private fun SummaryFindingsCard(findings: List<VisitFormRiskFinding>, onEdit: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing), modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = stringResource(R.string.visit_form_vs_tests_title),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      SecondaryButton(
        text = stringResource(R.string.visit_form_vs_edit),
        onClick = onEdit,
        trailingIcon = painterResource(R.drawable.ic_pencil_simple),
        height = Dimens.SmallButtonHeight,
      )
    }
    findings.forEach { finding -> SummaryMetricCard(finding = finding, modifier = Modifier.fillMaxWidth()) }
  }
}

@Composable
private fun SummaryMetricCard(finding: VisitFormRiskFinding, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .border(1.dp, NeutralG50, RoundedCornerShape(Dimens.CardRadius))
      .padding(Dimens.ItemSpacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = stringResource(finding.labelRes),
        style = MaterialTheme.typography.titleMedium,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      RiskBadge(riskLevel = finding.riskLevel)
    }
    Text(
      text = finding.value,
      style = MaterialTheme.typography.headlineLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.SmallSpacing),
    )
    if (finding.referenceRange.isNotBlank()) {
      Text(
        text = stringResource(R.string.visit_form_vs_reference_range, finding.referenceRange),
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
      )
    }
  }
}

/**
 * Infant (INFANT_VISIT) Summary outer tab (2026-08-08) — the infant counterpart to
 * [SummaryTabContent], scoped deliberately narrower per this feature's ask: an overall
 * [RiskBadge] plus one row per [InfantVisitRiskFinding] from
 * [DynamicVisitFormViewModel.infantKnownRisks] (danger signs, nutritional status, deformity,
 * prematurity, activity level, developmental milestones, feeding concerns — see
 * [org.armman.sakhi.data.visitform.InfantVisitRiskAssessment]'s doc). No Tests findings card
 * (infant has no vitals risk model yet) and no Edit button — just the risk list itself.
 */
@Composable
private fun InfantSummaryTabContent(state: DynamicVisitFormUiState, viewModel: DynamicVisitFormViewModel) {
  val risks = remember(state.answers, state.formCode) { viewModel.infantKnownRisks() }
  val overallRisk = remember(state.answers, state.formCode) { viewModel.infantOverallRiskLevel() }
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(Dimens.ScreenPadding),
  ) {
    InfantSummaryRiskBanner(riskLevel = overallRisk, risks = risks)
  }
}

@Composable
private fun InfantSummaryRiskBanner(riskLevel: RiskLevel, risks: List<InfantVisitRiskFinding>) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(NeutralG50)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = stringResource(R.string.visit_form_vs_risk_identified),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      RiskBadge(riskLevel = riskLevel)
    }
    if (risks.isEmpty()) {
      Text(
        text = stringResource(R.string.visit_form_vs_no_known_risks),
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      )
    } else {
      Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      ) {
        risks.forEach { risk -> InfantRiskRow(risk) }
      }
    }
  }
}

@Composable
private fun InfantRiskRow(risk: InfantVisitRiskFinding) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(
      text = risk.label,
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG400,
      modifier = Modifier.weight(1f).padding(end = Dimens.SmallSpacing),
    )
    RiskBadge(riskLevel = risk.riskLevel, compact = true)
  }
}

/**
 * Summary outer tab's plain "filled fields" review — POSTPARTUM_VISIT/NEONATAL_VISIT, and any
 * other form code with no bespoke Summary view of its own (ANC_VISIT/INFANT_VISIT keep their own
 * risk-banner Summary tabs above — see [SummaryTabContent]/[InfantSummaryTabContent]). One white
 * bordered card per schema section with an Edit pill (always returns to Visit Data's first
 * sub-tab — there's no per-section tab to jump back to for these form codes) and its answered
 * label/value rows. Same layout as [org.armman.sakhi.ui.delivery.DeliverySessionScreen]'s own
 * Summary tab (`DeliverySessionSummary`), sharing [SummaryRow]/[SummarySection]'s shape (see
 * [VisitFormSummaryModels]'s doc for why that's a local copy, not a shared import) — this form's
 * first use of that plain-review pattern.
 */
@Composable
private fun FieldSummaryTabContent(
  state: DynamicVisitFormUiState,
  viewModel: DynamicVisitFormViewModel,
  onEditVisitData: () -> Unit,
) {
  val imageCapturedLabel = stringResource(R.string.enrollment_consent_photo_captured)
  val mediaCompletedLabel = stringResource(R.string.visit_form_summary_media_completed)
  val sections = remember(state.answers, state.capturedImages, state.mediaCompleted, state.formCode) {
    viewModel.buildFieldSummary(imageCapturedLabel, mediaCompletedLabel)
  }
  LazyColumn(
    contentPadding = PaddingValues(Dimens.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxSize(),
  ) {
    item(key = "field_summary_title") {
      Text(
        text = stringResource(R.string.enrollment_summary_title),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
    }
    items(sections, key = { it.title }) { section ->
      FieldSummaryReviewCard(title = section.title, onEdit = onEditVisitData) {
        section.rows.forEach { row -> FieldSummaryReviewRow(label = row.label, value = row.value) }
      }
    }
  }
}

@Composable
private fun FieldSummaryReviewCard(
  title: String,
  onEdit: () -> Unit,
  rows: @Composable () -> Unit,
) {
  val shape = RoundedCornerShape(Dimens.CardRadius)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(White)
      .border(1.dp, NeutralG50, shape)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      SecondaryButton(
        text = stringResource(R.string.enrollment_summary_edit),
        onClick = onEdit,
        height = Dimens.SmallButtonHeight,
      )
    }
    rows()
  }
}

@Composable
private fun FieldSummaryReviewRow(label: String, value: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing)) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = NeutralG100)
    Text(
      text = value,
      style = MaterialTheme.typography.bodyLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.LabelValueGap, bottom = Dimens.SmallSpacing),
    )
    HorizontalDivider(color = NeutralG50)
  }
}

/**
 * Referral capture step — CR-Referral-01 Pass 4 (2026-08-27): moved out of a persistent outer
 * tab (see [VisitFormOuterTab]'s doc) into this conditional, full-screen step that replaces the
 * normal tab body only when [DynamicVisitFormUiState.showReferralCaptureStep] is true — i.e. only
 * once [DynamicVisitFormViewModel.onFinish] has already determined, from the on-device risk
 * result, that this visit needs a referral decision. Matches the PRD's decision tree ("Visit
 * completed — Risk assessment — Referral decision (by Sakhi)") far more closely than the old
 * always-visible tab did, and — because the trigger check is on-device — appears identically
 * whether she's online or offline when she taps Submit.
 *
 * CR-Referral-01 Pass 6 (2026-08-31): genuinely schema-driven now — the fields rendered here are
 * fetched live from `GET /forms/REFERRAL_VISIT/active-version`
 * ([DynamicVisitFormViewModel.loadReferralFormIfNeeded]) and rendered through the same generic
 * [org.armman.sakhi.ui.forms.DynamicFormField] every other form uses, honoring the schema's own
 * `visibleWhen` branching via [org.armman.sakhi.data.forms.FormVisibilityEvaluator] instead of
 * hand-coded `if` checks (Pass 5's approach — labels/options copied by hand into this composable
 * — looked identical but silently went stale the moment the backend schema changed; a real device
 * log confirmed exactly that gap, so this step never actually called the API before Pass 6).
 * Still not the generic [org.armman.sakhi.ui.adhocform.AdHocFormScreen] though — this step is
 * auto-triggered mid-visit, not opened as its own ad-hoc form (see
 * [DynamicVisitFormUiState.showReferralCaptureStep]'s doc for why that whole screen wasn't
 * reused), and intentionally still separate from the ANC_VISIT schema's own "Referrals" section (a
 * different set of questions, already rendered as a Visit Data sub-tab).
 *
 * Facility capture is free-text name + `place_of_referral` (the schema's dropdown, mapped by
 * [DynamicVisitFormViewModel.referralCaptureOrNull] into `POST /referrals`'s `facilityType` — see
 * [org.armman.sakhi.data.referral.ReferralCapture.facilityType]'s doc for why this is no longer
 * the app's old [org.armman.sakhi.data.referral.FacilityType] enum, and
 * [DynamicVisitFormViewModel.PLACE_OF_REFERRAL_TO_FACILITY_TYPE]'s doc for why the mapping is
 * lossy rather than a raw passthrough).
 *
 * These fields are bundled into a [org.armman.sakhi.data.referral.ReferralCapture] by
 * [DynamicVisitFormViewModel.onFinish]'s second (post-capture) call and only ever become an
 * actual referral once the visit submission succeeds AND the server's OWN risk-assessment
 * response ALSO confirms a trigger — see
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.maybeCreateReferral]'s doc for
 * why the on-device result that gates this screen isn't treated as authoritative on its own.
 * [DynamicVisitFormViewModel.skipReferralCapture] clears these fields and submits anyway (her
 * judgement call, per the PRD, that no referral is actually needed); leaving them blank and
 * tapping Submit here has the identical effect — same as answering "not a new condition" or
 * "beneficiary declined" partway through this step (see [DynamicVisitFormViewModel
 * .referralCaptureOrNull]'s doc for why both of those branches also end with no referral).
 */
@Composable
private fun ReferralCaptureStep(state: DynamicVisitFormUiState, viewModel: DynamicVisitFormViewModel) {
  // CR-Referral-01 Pass 6 (2026-08-31): genuinely schema-driven — every field below is rendered
  // through the same generic DynamicFormField the ad-hoc forms use, off the schema this ViewModel
  // fetched live from GET /forms/REFERRAL_VISIT/active-version (DynamicVisitFormViewModel
  // .loadReferralFormIfNeeded). No hand-copied labels/options here anymore — see
  // DynamicVisitFormUiState's doc for why Pass 5's hand-built approach was replaced.
  Column(modifier = Modifier.fillMaxSize()) {
    Column(
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(Dimens.ScreenPadding),
    ) {
      Text(
        text = stringResource(R.string.visit_form_referral_details_title),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
      // CR-Referral-01 Pass 4: explains WHY this screen appeared, since it's no longer a tab she
      // chose to open herself.
      Text(
        text = stringResource(R.string.visit_form_referral_step_explainer),
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
      )
      when {
        state.referralFormLoading -> {
          Box(modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.ItemSpacing), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        state.referralFormVersion == null -> {
          Text(
            text = stringResource(R.string.visit_form_error_load),
            style = MaterialTheme.typography.bodyMedium,
            color = NeutralG400,
          )
        }
        else -> {
          viewModel.visibleReferralFields().forEach { field ->
            DynamicFormField(
              field = field,
              answers = state.referralAnswers,
              registrationDate = viewModel.visitDate,
              mediaCompleted = false,
              capturedImageUri = null,
              loadOptions = { viewModel.optionsFor(field) },
              onSingleAnswer = { value -> viewModel.setReferralAnswer(field.questionCode, value) },
              onMultiAnswer = {}, // REFERRAL_VISIT's schema has no multiselect fields.
              onPlayMedia = {}, // ...nor any media/audio fields.
              onCaptureImage = {}, // ...nor any image-capture fields.
            )
          }
        }
      }
    }
    HorizontalDivider(color = NeutralG50)
    Row(
      horizontalArrangement = Arrangement.End,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
    ) {
      SecondaryButton(
        text = stringResource(R.string.visit_form_back),
        onClick = viewModel::cancelReferralCapture,
        height = Dimens.SmallButtonHeight,
      )
      Spacer(modifier = Modifier.weight(1f))
      SecondaryButton(
        text = stringResource(R.string.visit_form_referral_skip),
        onClick = viewModel::skipReferralCapture,
        enabled = !state.isSubmitting,
        height = Dimens.SmallButtonHeight,
        modifier = Modifier.padding(end = Dimens.SmallSpacing),
      )
      PrimaryButton(
        text = stringResource(R.string.visit_form_submit),
        onClick = viewModel::onFinish,
        enabled = !state.isSubmitting,
        loading = state.isSubmitting,
        fullWidth = false,
        height = Dimens.SmallButtonHeight,
        trailingIcon = painterResource(R.drawable.ic_arrow_right),
      )
    }
  }
}

@Composable
private fun DynamicVisitFormFieldList(
  fields: List<FormFieldSchema>,
  state: DynamicVisitFormUiState,
  viewModel: DynamicVisitFormViewModel,
  /** Title of the section currently rendered — drives the scroll-to-top-on-tab-switch reset, same
   * pattern as [org.armman.sakhi.ui.forms.DynamicMotherRegistrationScreen]'s field list. */
  sectionKey: String?,
) {
  val context = LocalContext.current
  val listState = rememberLazyListState()

  LaunchedEffect(sectionKey) {
    if (FormSectionScroll.shouldResetToTop(hasPendingErrorScroll = false)) {
      listState.scrollToItem(0)
    }
  }

  // Live capture into app-private storage, one target file per question_code — same pattern as
  // DynamicMotherRegistrationScreen's field list (see that file's doc for why one file per field).
  val photoUriFor = remember { mutableMapOf<String, android.net.Uri>() }
  var captureTargetCode by remember { mutableStateOf<String?>(null) }

  val takePictureLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture(),
  ) { success ->
    val code = captureTargetCode
    if (success && code != null) {
      viewModel.setCapturedImage(code, photoUriFor.getValue(code).toString())
    }
    captureTargetCode = null
  }

  LazyColumn(
    state = listState,
    contentPadding = PaddingValues(
      start = Dimens.ScreenPadding,
      end = Dimens.ScreenPadding,
      top = Dimens.ScreenPadding,
      bottom = Dimens.FormListBottomSlack,
    ),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxSize(),
  ) {
    itemsIndexed(fields, key = { _, field -> field.questionCode }) { _, field ->
      DynamicFormField(
        field = field,
        answers = state.answers,
        registrationDate = viewModel.visitDate,
        mediaCompleted = field.questionCode in state.mediaCompleted,
        capturedImageUri = state.capturedImages[field.questionCode],
        readOnlyQuestionCodes = if (state.heightLockedFromContext) {
          setOf(VisitFormQuestionCodes.HEIGHT_CM)
        } else {
          emptySet()
        },
        loadOptions = { viewModel.optionsFor(field) },
        onSingleAnswer = { value -> viewModel.setAnswer(field.questionCode, value) },
        onMultiAnswer = { values -> viewModel.setMultiAnswer(field.questionCode, values) },
        onPlayMedia = { viewModel.markMediaComplete(field.questionCode) },
        onCaptureImage = {
          val uri = photoUriFor.getOrPut(field.questionCode) {
            val photoFile = File(File(context.filesDir, "dynamic-visit-form"), "${field.questionCode}.jpg")
              .apply { parentFile?.mkdirs() }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
          }
          captureTargetCode = field.questionCode
          takePictureLauncher.launch(uri)
        },
        formCode = state.formCode,
        riskGrade = state.highlightedFieldGrades[field.questionCode],
        hasHealthEducationHint = field.questionCode in state.educationHintFieldConditions,
        onLearnMoreClick = { viewModel.showEducationHint(field.questionCode) },
      )
    }
  }
}

/** CR-M3-06 (Health Education) + CR-Closure-03 (PP5 forced closure): decides where a
 * just-completed submit routes to. [DynamicVisitFormUiState.triggersClosurePrompt] is checked
 * FIRST and wins outright when true — the SRS's PP5-closure trigger is not conditional on risk
 * grading, and in practice POSTPARTUM_VISIT has no risk-grading pack at all yet (see
 * `docs/context/delivery-log.md`'s CR-M3-05 notes), so the two paths never actually compete on a
 * real submission; the ordering just makes that non-competition explicit rather than accidental.
 * Otherwise, routes into Health Education when [DynamicVisitFormUiState.goRulesRiskResult] (set to
 * the final, authoritative grading by [DynamicVisitFormViewModel.onFinish] right before this event
 * fires) has at least one `isEducationTrigger` condition. [state.formCode] decides MOTHER vs CHILD
 * the same way [DynamicVisitFormViewModel.evaluateGoRulesRisk] branches. Falls back to [onBack]
 * whenever there is nothing to show — no triggered condition, or no result at all. */
private fun routeAfterSubmit(
  state: DynamicVisitFormUiState,
  beneficiaryId: String,
  onSubmittedNeedsEducation: (String, List<String>) -> Unit,
  onSubmittedTriggersClosure: (String, Boolean) -> Unit,
  onBack: () -> Unit,
) {
  if (state.triggersClosurePrompt) {
    onSubmittedTriggersClosure(beneficiaryId, false)
    return
  }
  // CR-Closure-01 items #5/#6: checked ahead of the education-routing fallback below, not folded
  // into it — the SRS's CCV-boundary routing (closure prompt OR HR extension) is not conditional
  // on risk grading either, same non-competition rationale triggersClosurePrompt's own doc above
  // gives for PP5. A coincidental isEducationTrigger flag on this same submission must not divert
  // either branch into Health Education instead.
  if (state.triggersChildClosurePrompt) {
    onSubmittedTriggersClosure(beneficiaryId, true)
    return
  }
  if (state.ccvHrExtensionWindow != null) {
    // HR detected at the last CCV visit: the extension window was already surfaced to the Sakhi
    // via a Toast in this screen's own event handler (see the Submitted branch above) before this
    // function was called — nothing left to route into, since the extension itself isn't a form
    // to fill this session (no persisted schedule row for it yet, see that field's own doc).
    onBack()
    return
  }
  val formCode = state.formCode
  val conditionMap = if (formCode == FORM_CODE_MOTHER) {
    org.armman.sakhi.data.rules.RiskConditionIds.ANC
  } else {
    org.armman.sakhi.data.rules.RiskConditionIds.INFANT
  }
  // Real backend contract (GET /beneficiaries/{beneficiaryId}/risk, confirmed 2026-08-28) keys
  // education content by conditionCode (e.g. "JAUNDICE"), NOT the UUID riskConditionId the
  // on-device grading result carries — same idToCode reverse lookup
  // DynamicVisitFormViewModel.recheckGoRulesRisk already builds for field highlighting.
  val idToCode = conditionMap.entries.associate { (code, id) -> id to code }
  val triggeredConditionCodes = state.goRulesRiskResult?.conditions.orEmpty()
    .filter { it.isEducationTrigger }
    .mapNotNull { idToCode[it.riskConditionId] }
    .distinct()
  if (triggeredConditionCodes.isEmpty()) {
    onBack()
    return
  }
  onSubmittedNeedsEducation(beneficiaryId, triggeredConditionCodes)
}

/** CR-M3-06 requirement #4: "Learn More" sheet for a field the real-time evaluator flagged as an
 * education trigger (see [DynamicVisitFormUiState.educationHintFieldConditions]) — same dialog
 * shape as [CriticalConditionBanner], reused here for a non-urgent, dismiss-only informational
 * message rather than a warning. Renders the generic coming-soon copy while
 * [DynamicVisitFormUiState.activeEducationHintMessages] is empty (still loading, or no
 * content-service row for this condition yet) — never blocks on the network round trip, per
 * requirement #6's "must not block submission" contract extended to this in-form entry point too. */
@Composable
private fun LearnMoreHintDialog(topic: org.armman.sakhi.data.healtheducation.HealthEducationTopic?, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(Dimens.CardRadius), color = White) {
      Column(modifier = Modifier.padding(Dimens.ScreenPadding)) {
        if (topic != null) {
          Text(text = topic.topicName, style = MaterialTheme.typography.titleMedium)
        } else {
          // Still loading (HealthEducationRepository.getPlaceholderTopic's round trip hasn't
          // returned yet) — the button below stays enabled regardless, per requirement #6's
          // "must not block" contract extended to this in-form entry point.
          Text(text = "Learn More", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = NeutralG400,
            modifier = Modifier.padding(top = Dimens.SmallSpacing),
          )
        }
        PrimaryButton(
          text = "Close",
          onClick = onDismiss,
          modifier = Modifier.padding(top = Dimens.ItemSpacing),
        )
      }
    }
  }
}

/** Full-width "Immediate Urgency" dialog (FR-S-4.4) — ported verbatim from the retired
 * [org.armman.sakhi.ui.visitform.VisitFormScreen]. Dismiss discards & exits, no confirm step. */
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

/** Ported verbatim from the retired [org.armman.sakhi.ui.visitform.VisitFormScreen]. */
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
          )
        }
      }
    }
  }
}
