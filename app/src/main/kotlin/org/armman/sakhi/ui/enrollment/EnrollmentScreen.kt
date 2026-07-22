package org.armman.sakhi.ui.enrollment

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.components.BeneficiaryTypeOption
import org.armman.sakhi.ui.enrollment.steps.ConsentStep
import org.armman.sakhi.ui.enrollment.steps.EnrollmentCompleteContent
import org.armman.sakhi.ui.enrollment.steps.HealthHistoryActions
import org.armman.sakhi.ui.enrollment.steps.HealthHistoryStep
import org.armman.sakhi.ui.enrollment.steps.PersonalInfoActions
import org.armman.sakhi.ui.enrollment.steps.PersonalInfoStep
import org.armman.sakhi.ui.enrollment.steps.SummaryStep
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.White
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How long the green saved pill stays visible (design shows a transient toast). */
private const val SAVED_TOAST_MILLIS = 2000L

/**
 * Enrollment Form (CR-015a) — entry selector + Consent → … → Summary stepper
 * scaffold + completion state, per `Enrollment form.pdf` (purple frames).
 */
@Composable
fun EnrollmentScreen(
  onBack: () -> Unit,
  onProfile: () -> Unit = {},
  /** CR-018: Pregnant Woman enrollment now runs entirely through the dynamic
   * `DynamicMotherRegistrationScreen` (backend-driven schema) instead of this screen's static
   * Consent/Personal Info/Health History steps — those steps are only still reachable if this
   * callback is left as a no-op, kept for tests/previews that don't wire real navigation. */
  onPregnantWomanSelected: () -> Unit = {},
  viewModel: EnrollmentViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val comingSoon = stringResource(R.string.enrollment_coming_soon)
  var showExitDialog by remember { mutableStateOf(false) }
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }

  var showSavedToast by remember { mutableStateOf(false) }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        EnrollmentEvent.ComingSoon ->
          Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show()
        // Green pill per the Summary frame (SM-UI-5); auto-dismisses below.
        EnrollmentEvent.DataSaved -> showSavedToast = true
      }
    }
  }

  LaunchedEffect(showSavedToast) {
    if (showSavedToast) {
      delay(SAVED_TOAST_MILLIS)
      showSavedToast = false
    }
  }

  val inStepper = state.currentStep != EnrollmentStep.ENTRY &&
    state.currentStep != EnrollmentStep.COMPLETE
  val exitFlow = {
    viewModel.exitEnrollment()
    onBack()
  }
  // Mid-flow back must confirm (draft would be lost); entry/complete exit directly.
  BackHandler { if (inStepper) showExitDialog = true else exitFlow() }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        BackHeader(
          title = stringResource(R.string.enrollment_back_title),
          subtitle = today,
          onBack = { if (inStepper) showExitDialog = true else exitFlow() },
          onAvatarClick = onProfile,
        )
        Surface(
          color = White,
          shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
          modifier = Modifier.fillMaxSize(),
        ) {
          when (state.currentStep) {
            EnrollmentStep.COMPLETE -> EnrollmentCompleteContent(
              onStartVisitForm = viewModel::onStartVisitForm,
            )
            else -> EnrollmentBody(
              state = state,
              viewModel = viewModel,
              onPregnantWomanSelected = onPregnantWomanSelected,
            )
          }
        }
      }
      if (showSavedToast) {
        SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
      }
    }
  }

  if (showExitDialog) {
    ExitConfirmationDialog(
      onConfirm = {
        showExitDialog = false
        exitFlow()
      },
      onDismiss = { showExitDialog = false },
    )
  }
}

/** Green "Data has been saved" pill per the Summary frame (SM-UI-5). */
@Composable
private fun SavedToast(modifier: Modifier = Modifier) {
  Surface(
    color = StatusSuccess,
    shape = RoundedCornerShape(Dimens.TileRadius),
    modifier = modifier.padding(bottom = Dimens.SavedToastBottomPadding),
  ) {
    Text(
      text = stringResource(R.string.enrollment_data_saved),
      style = MaterialTheme.typography.labelLarge,
      color = White,
      modifier = Modifier.padding(
        horizontal = Dimens.ItemSpacing,
        vertical = Dimens.SmallSpacing,
      ),
    )
  }
}

@Composable
private fun EnrollmentBody(
  state: EnrollmentUiState,
  viewModel: EnrollmentViewModel,
  onPregnantWomanSelected: () -> Unit,
) {
  // Tab labels wrap to two lines on mobile; tablet shows them on one line
  // (QA 2026-07-13), so the resource's line break becomes a space there.
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val stepTabs = listOf(
    stringResource(R.string.enrollment_tab_consent),
    stringResource(R.string.enrollment_tab_personal_info),
    stringResource(R.string.enrollment_tab_health_history),
    stringResource(R.string.enrollment_tab_summary),
  ).map { label -> if (isTablet) label.replace("\n", " ") else label }

  // Tablet designs inset content 48dp from the screen edges; mobile 24dp.
  val hPadding = if (isTablet) Dimens.ScreenPaddingTablet else Dimens.ScreenPadding

  // Hoisted (rather than inline rememberScrollState()) so a blocked Next tap can scroll the step
  // back to its validation banner — otherwise, tapping Next while scrolled down near the bottom
  // button leaves the (now off-screen) banner unnoticed and the step looks like it silently did
  // nothing, see EnrollmentUiState.validationScrollTrigger.
  val scrollState = rememberScrollState()
  LaunchedEffect(state.validationScrollTrigger) {
    if (state.validationScrollTrigger > 0) scrollState.animateScrollTo(0)
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
        text = stringResource(R.string.enrollment_title),
        style = SerifTitleLarge,
        color = NeutralG400,
        modifier = Modifier.padding(top = Dimens.ScreenPadding),
      )
      when (state.currentStep) {
        EnrollmentStep.ENTRY -> EntrySelector(
          selectedType = state.beneficiaryType,
          onSelect = { type ->
            viewModel.selectBeneficiaryType(type)
            // CR-018: Pregnant Woman now goes straight to the dynamic, backend-schema-driven
            // form instead of this screen's own static Consent/Personal Info/Health History
            // steps — those steps' fields are superseded by the MOTHER_REGISTRATION schema.
            if (type == BeneficiaryType.PREGNANT_WOMAN) onPregnantWomanSelected()
          },
        )
        else -> {
          // Tab row spans the full content width (per tablet design QA
          // 2026-07-13); SpaceBetween yields the design's equal gaps.
          AppTabRow(
            tabs = stepTabs,
            selectedIndex = state.currentStep.ordinal - EnrollmentStep.CONSENT.ordinal,
            onTabSelected = { index ->
              viewModel.goToStep(EnrollmentStep.entries[EnrollmentStep.CONSENT.ordinal + index])
            },
            distributeEvenly = true,
            indicatorOverhang = Dimens.TabIndicatorOverhang,
            modifier = Modifier.padding(top = Dimens.ItemSpacing),
          )
          StepContent(state = state, viewModel = viewModel)
        }
      }
    }
    if (state.currentStep != EnrollmentStep.ENTRY) {
      StepFooter(state = state, viewModel = viewModel, hPadding = hPadding)
    }
  }
}

@Composable
private fun StepContent(state: EnrollmentUiState, viewModel: EnrollmentViewModel) {
  val context = LocalContext.current
  // Live capture into app-private storage (Excel Q4: no gallery access).
  // remember{} keeps one target URI per composition; each launch overwrites
  // the same file, which also implements retake-replaces-previous.
  val photoUri = remember {
    val photoFile = File(File(context.filesDir, "enrollment"), "consent_photo.jpg")
      .apply { parentFile?.mkdirs() }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
  }
  val takePictureLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture(),
  ) { success ->
    if (success) viewModel.onConsentPhotoCaptured(photoUri.toString())
  }

  when (state.currentStep) {
    EnrollmentStep.CONSENT -> ConsentStep(
      consent = state.consent,
      onWillingPersonalInfo = viewModel::setWillingPersonalInfo,
      onWillingHealthHistory = viewModel::setWillingHealthHistory,
      onWillingDiagnosticTests = viewModel::setWillingDiagnosticTests,
      onUnderstandsReferral = viewModel::setUnderstandsReferral,
      onPlayVideo = viewModel::onPlayVideo,
      onPlayGuidelines = viewModel::onPlayVideo,
      onTakePhoto = { takePictureLauncher.launch(photoUri) },
      modifier = Modifier.padding(bottom = Dimens.ScreenPadding),
    )
    EnrollmentStep.PERSONAL_INFO -> PersonalInfoStep(
      state = state.personalInfo,
      actions = PersonalInfoActions(
        onFirstName = viewModel::setFirstName,
        onMiddleName = viewModel::setMiddleName,
        onLastName = viewModel::setLastName,
        onDob = viewModel::setDob,
        onAge = viewModel::setAge,
        onEducationSelf = viewModel::setEducationSelf,
        onAddress = viewModel::setAddress,
        onMobile = viewModel::setMobileNumber,
        onPhoneOwner = viewModel::setPhoneOwner,
        onNetwork = viewModel::setNetworkAvailability,
        onEducationPartner = viewModel::setEducationPartner,
        onPartnerOccupation = viewModel::setPartnerOccupation,
        onYearsInVillage = viewModel::setYearsInVillage,
        onMigration = viewModel::setMigrationPattern,
        onIncome = viewModel::setIncomeBand,
        onReligion = viewModel::setReligion,
        onCategory = viewModel::setCategory,
        onHousehold = viewModel::setHouseholdMembers,
        onChildrenUnderFive = viewModel::setChildrenUnderFive,
        onState = viewModel::selectState,
        onDistrict = viewModel::selectDistrict,
        onBlock = viewModel::selectBlock,
        onVillage = viewModel::selectVillage,
        onPada = viewModel::selectPada,
        onPhc = viewModel::selectPhc,
        onSubCentre = viewModel::selectSubCentre,
        onLmpKnown = viewModel::setLmpKnown,
        onLmp = viewModel::setLmp,
      ),
      modifier = Modifier.padding(bottom = Dimens.ScreenPadding),
    )
    EnrollmentStep.HEALTH_HISTORY -> HealthHistoryStep(
      state = state.healthHistory,
      actions = HealthHistoryActions(
        onPlannedPregnancy = viewModel::setPlannedPregnancy,
        onTookTreatment = viewModel::setTookTreatment,
        onTreatmentType = viewModel::setTreatmentType,
        onRchStatus = viewModel::setRchStatus,
        onRchNumber = viewModel::setRchNumber,
        onAncStatus = viewModel::setAncStatus,
        onAnc1Date = viewModel::setAnc1Date,
        onAncCondition = viewModel::toggleAncCondition,
        onTdNone = viewModel::setTdNone,
        onTd1 = viewModel::setTd1,
        onTd1Date = viewModel::setTd1Date,
        onTd2 = viewModel::setTd2,
        onTd2Date = viewModel::setTd2Date,
        onTdBooster = viewModel::setTdBooster,
        onTdBoosterDate = viewModel::setTdBoosterDate,
        onGravida = viewModel::setGravida,
        onPara = viewModel::setPara,
        onLivingChildren = viewModel::setLivingChildren,
        onAbortions = viewModel::setAbortions,
        onStillBirths = viewModel::setStillBirths,
        onDeadChildren = viewModel::setDeadChildren,
        onHeightCm = viewModel::setHeightCm,
        onWeightKg = viewModel::setWeightKg,
        onLastPregnancyWhen = viewModel::setLastPregnancyWhen,
        onDeliveryComplication = viewModel::toggleDeliveryComplication,
        onLastDeliveryDuration = viewModel::setLastDeliveryDuration,
        onLastDeliveryType = viewModel::setLastDeliveryType,
        onLastDeliveryPlace = viewModel::setLastDeliveryPlace,
        onLastDeliveryOutcome = viewModel::setLastDeliveryOutcome,
        onBirthWeight = viewModel::setBirthWeight,
        onSelfCondition = viewModel::toggleSelfCondition,
        onLongTermMed = viewModel::toggleLongTermMed,
        onSickleCell = viewModel::setSickleCell,
        onSubstanceUse = viewModel::toggleSubstanceUse,
        onFamilyHistory = viewModel::setFamilyHistory,
        onFamilyCondition = viewModel::toggleFamilyCondition,
        onMalnutrition = viewModel::setMalnutrition,
        onRemarks = viewModel::setRemarks,
      ),
      modifier = Modifier.padding(bottom = Dimens.ScreenPadding),
    )
    EnrollmentStep.SUMMARY -> SummaryStep(
      personalInfo = state.personalInfo,
      healthHistory = state.healthHistory,
      submitFailed = state.submitFailed,
      submitErrorMessage = state.submitErrorMessage,
      onEditPersonalInfo = { viewModel.goToStep(EnrollmentStep.PERSONAL_INFO) },
      onEditHealthHistory = { viewModel.goToStep(EnrollmentStep.HEALTH_HISTORY) },
      modifier = Modifier.padding(bottom = Dimens.ScreenPadding),
    )
    EnrollmentStep.ENTRY, EnrollmentStep.COMPLETE -> Unit
  }
}

/** Footer bar: hairline divider + right-aligned forward action (per design). */
@Composable
private fun StepFooter(
  state: EnrollmentUiState,
  viewModel: EnrollmentViewModel,
  hPadding: Dp,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    HorizontalDivider(color = NeutralG50)
    Row(
      horizontalArrangement = Arrangement.End,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = hPadding, vertical = Dimens.ItemSpacing),
    ) {
      when (state.currentStep) {
        EnrollmentStep.CONSENT -> PrimaryButton(
          text = stringResource(R.string.enrollment_next_personal_info),
          onClick = viewModel::goToPersonalInfo,
          enabled = state.consent.isComplete,
          trailingIcon = painterResource(R.drawable.ic_arrow_right),
          height = Dimens.SmallButtonHeight,
          fullWidth = false,
        )
        EnrollmentStep.PERSONAL_INFO -> {
          // Design: Back (outlined, left arrow) + Health History (primary).
          SecondaryButton(
            text = stringResource(R.string.enrollment_pi_back),
            onClick = viewModel::goBack,
            leadingIcon = painterResource(R.drawable.ic_arrow_left),
            height = Dimens.SmallButtonHeight,
          )
          Spacer(modifier = Modifier.weight(1f))
          PrimaryButton(
            text = stringResource(R.string.enrollment_pi_next_health_history),
            onClick = viewModel::goToHealthHistory,
            trailingIcon = painterResource(R.drawable.ic_arrow_right),
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
        // 015c skipped: the placeholder's Next advances ungated to Summary.
        EnrollmentStep.HEALTH_HISTORY -> {
          SecondaryButton(
            text = stringResource(R.string.enrollment_pi_back),
            onClick = viewModel::goBack,
            leadingIcon = painterResource(R.drawable.ic_arrow_left),
            height = Dimens.SmallButtonHeight,
          )
          Spacer(modifier = Modifier.weight(1f))
          PrimaryButton(
            text = stringResource(R.string.enrollment_next_summary),
            onClick = viewModel::goToSummary,
            trailingIcon = painterResource(R.drawable.ic_arrow_right),
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
        EnrollmentStep.SUMMARY -> {
          SecondaryButton(
            text = stringResource(R.string.enrollment_pi_back),
            onClick = viewModel::goBack,
            leadingIcon = painterResource(R.drawable.ic_arrow_left),
            height = Dimens.SmallButtonHeight,
          )
          Spacer(modifier = Modifier.weight(1f))
          PrimaryButton(
            text = stringResource(R.string.enrollment_submit),
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            height = Dimens.SmallButtonHeight,
            fullWidth = false,
          )
        }
        else -> Unit
      }
    }
  }
}

@Composable
private fun EntrySelector(
  selectedType: BeneficiaryType?,
  onSelect: (BeneficiaryType) -> Unit,
) {
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.enrollment_register_as),
      style = MaterialTheme.typography.titleLarge,
      color = NeutralG400,
      modifier = Modifier.padding(top = Dimens.ScreenPadding),
    )
    if (isTablet) {
      // Tablet frame: both option cards side by side on one row.
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
      ) {
        EntryOptions(selectedType, onSelect, optionModifier = Modifier.weight(1f))
      }
    } else {
      // Mobile frame: cards stacked.
      EntryOptions(
        selectedType,
        onSelect,
        optionModifier = Modifier.padding(top = Dimens.ItemSpacing),
      )
    }
  }
}

@Composable
private fun EntryOptions(
  selectedType: BeneficiaryType?,
  onSelect: (BeneficiaryType) -> Unit,
  optionModifier: Modifier,
) {
  BeneficiaryTypeOption(
    label = stringResource(R.string.enrollment_type_child),
    iconRes = R.drawable.ic_baby,
    selected = selectedType == BeneficiaryType.CHILD,
    onClick = { onSelect(BeneficiaryType.CHILD) },
    modifier = optionModifier,
  )
  BeneficiaryTypeOption(
    label = stringResource(R.string.enrollment_type_pregnant_woman),
    iconRes = R.drawable.ic_woman,
    selected = selectedType == BeneficiaryType.PREGNANT_WOMAN,
    onClick = { onSelect(BeneficiaryType.PREGNANT_WOMAN) },
    modifier = optionModifier,
  )
}

/** Exit confirmation — mid-flow back would discard the in-memory draft. */
@Composable
private fun ExitConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(Dimens.CardRadius), color = White) {
      Column(modifier = Modifier.padding(Dimens.ScreenPadding)) {
        Text(
          text = stringResource(R.string.enrollment_exit_title),
          style = MaterialTheme.typography.titleLarge,
          color = NeutralG400,
        )
        Text(
          text = stringResource(R.string.enrollment_exit_message),
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
              text = stringResource(R.string.enrollment_exit_cancel),
              style = MaterialTheme.typography.labelLarge,
            )
          }
          SecondaryButton(
            text = stringResource(R.string.enrollment_exit_confirm),
            onClick = onConfirm,
            height = Dimens.SmallButtonHeight,
          )
        }
      }
    }
  }
}
