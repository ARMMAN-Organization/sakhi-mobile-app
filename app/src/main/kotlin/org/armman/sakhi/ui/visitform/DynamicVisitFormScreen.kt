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
import androidx.compose.ui.res.stringArrayResource
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
import org.armman.sakhi.ui.enrollment.components.AppDateField
import org.armman.sakhi.ui.enrollment.components.AppDropdownField
import org.armman.sakhi.ui.enrollment.components.AppRadioGroup
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
          onBack()
        }
        DynamicVisitFormEvent.QueuedOffline -> {
          Toast.makeText(context, queuedOffline, Toast.LENGTH_LONG).show()
          onBack()
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

private val OUTER_TAB_ORDER = listOf(
  VisitFormOuterTab.VISIT_DATA,
  VisitFormOuterTab.SUMMARY,
  VisitFormOuterTab.HEALTH_INFO,
  VisitFormOuterTab.REFERRAL,
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
          selectedOuterTab == VisitFormOuterTab.REFERRAL -> ReferralTabContent(state = state, viewModel = viewModel)
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
 * Referral outer tab (bharath, 2026-08-08) — a standalone hand-built form (Date/Facility/Type),
 * NOT schema-driven. Facility options are a hardcoded placeholder list (same labels as the
 * ANC_VISIT schema's own "Advised place of delivery" options, Q42) until a real facility
 * directory API exists — see this feature's scope discussion. Submit isn't wired to a backend
 * this pass; it fires the same [DynamicVisitFormEvent.ComingSoon] toast the rest of the form uses.
 */
@Composable
private fun ReferralTabContent(state: DynamicVisitFormUiState, viewModel: DynamicVisitFormViewModel) {
  val facilityOptions = stringArrayResource(R.array.visit_form_referral_facility_options).toList()
  val typeOptions = listOf(
    stringResource(R.string.visit_form_referral_type_accompanied),
    stringResource(R.string.visit_form_referral_type_standard),
  )
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(Dimens.ScreenPadding),
  ) {
    Text(
      text = stringResource(R.string.visit_form_referral_details_title),
      style = MaterialTheme.typography.titleLarge,
      color = NeutralG400,
    )
    AppDateField(
      label = stringResource(R.string.visit_form_referral_date),
      placeholder = "",
      value = state.referralDate,
      onDateSelected = viewModel::setReferralDate,
      maxDate = viewModel.visitDate,
    )
    AppDropdownField(
      label = stringResource(R.string.visit_form_referral_facility),
      placeholder = "",
      options = facilityOptions,
      selectedIndex = facilityOptions.indexOf(state.referralFacility).takeIf { it >= 0 },
      onSelected = { index -> viewModel.setReferralFacility(facilityOptions.getOrNull(index)) },
    )
    AppRadioGroup(
      label = stringResource(R.string.visit_form_referral_type),
      options = typeOptions,
      selectedIndex = typeOptions.indexOf(state.referralType).takeIf { it >= 0 },
      onSelected = { index -> viewModel.setReferralType(typeOptions.getOrNull(index)) },
      horizontal = true,
    )
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
      )
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
