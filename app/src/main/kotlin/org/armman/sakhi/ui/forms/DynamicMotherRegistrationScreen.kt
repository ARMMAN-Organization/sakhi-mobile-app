package org.armman.sakhi.ui.forms

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.StatusBanner
import org.armman.sakhi.ui.components.StatusBannerVariant
import org.armman.sakhi.ui.enrollment.steps.EnrollmentCompleteContent
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schema `section` label whose tab gets the design's Consent-specific decorations (intro line +
 * "Ensure that the beneficiary" heading). Comes from the backend's field `section` values. */
private const val CONSENT_SECTION = "Consent"

/**
 * Screen hosting the CR-018 dynamic Mother Registration form. Wired as the real Pregnant Woman
 * enrollment path (see `EnrollmentScreen`'s entry selector) — the static Consent/Personal Info/
 * Health History steps are superseded by the MOTHER_REGISTRATION schema, which covers the same
 * ground (including consent) plus everything ARMMAN adds later without an app release.
 *
 * Fields are grouped into tabs by the schema's `section` key via
 * [DynamicMotherRegistrationViewModel.sections]/[DynamicMotherRegistrationViewModel.fieldsInSection];
 * a client-side **Summary** tab is appended after them for review + submit. The bottom bar advances
 * tab-by-tab (each forward step gated by the current tab's own validity) and shows Submit on the
 * Summary tab. On a successful submit the sheet swaps to the [EnrollmentCompleteContent] success
 * state, matching the static flow.
 */
@Composable
fun DynamicMotherRegistrationScreen(
  onBack: () -> Unit,
  /** Leaves the enrollment sub-graph (→ Home) once the Sakhi is done with the success screen —
   * invoked from the Complete screen's back, not automatically on submit, so the "Enrollment
   * Complete!" state is actually shown (mirrors the static flow's COMPLETE step). */
  onSubmitted: (beneficiaryId: String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: DynamicMotherRegistrationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }
  var selectedTabIndex by remember { mutableStateOf(0) }
  val context = LocalContext.current
  val comingSoonMessage = stringResource(R.string.enrollment_coming_soon)
  val isSuccess = state.submissionState is SubmissionState.Success
  val snackbarHostState = remember { SnackbarHostState() }

  // Submission errors (e.g. "token expired", validation/conflict messages) surface as a transient
  // snackbar rather than persistent red text — keyed on the state so each new failure re-shows.
  LaunchedEffect(state.submissionState) {
    (state.submissionState as? SubmissionState.Failed)?.let { failed ->
      snackbarHostState.showSnackbar(message = failed.message, duration = SnackbarDuration.Long)
    }
  }

  // On the success screen, both system back and the header back leave enrollment (→ Home) rather
  // than popping back into the completed form.
  BackHandler(enabled = isSuccess) { onSubmitted(viewModel.beneficiaryId) }

  Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.enrollment_back_title),
        subtitle = today,
        onBack = { if (isSuccess) onSubmitted(viewModel.beneficiaryId) else onBack() },
      )
      // Content sits on the white rounded-top sheet over the lavender background, per the design.
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        Column(modifier = Modifier.fillMaxSize()) {
          when {
            isSuccess -> EnrollmentCompleteContent(
              onStartVisitForm = {
                // Same behaviour as the static flow: the Visit Form can't be opened yet (no
                // visit is scheduled at enrollment time), so this is a "coming soon" stub.
                Toast.makeText(context, comingSoonMessage, Toast.LENGTH_SHORT).show()
              },
            )

            state.isLoading && state.version == null -> Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier.fillMaxSize(),
            ) { CircularProgressIndicator() }

            state.loadError != null -> Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
            ) {
              Text(text = state.loadError.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }

            else -> {
              Text(
                text = stringResource(R.string.enrollment_title),
                style = SerifTitleLarge,
                color = NeutralG400,
                modifier = Modifier.padding(
                  start = Dimens.ScreenPadding,
                  end = Dimens.ScreenPadding,
                  top = Dimens.ScreenPadding,
                ),
              )
              FormContent(
                state = state,
                viewModel = viewModel,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
              )
            }
          }
        }
      }
    }
      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .safeDrawingPadding()
          .padding(Dimens.ScreenPadding),
      ) { data ->
        StatusBanner(message = data.visuals.message, variant = StatusBannerVariant.Error)
      }
    }
  }
}

@Composable
private fun FormContent(
  state: DynamicFormUiState,
  viewModel: DynamicMotherRegistrationViewModel,
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
) {
  val schemaSections = viewModel.sections()
  // The Summary tab is appended client-side (it has no schema fields — it reviews the others).
  val summaryLabel = stringResource(R.string.enrollment_tab_summary)
  val tabs = schemaSections + summaryLabel
  val safeIndex = selectedTabIndex.coerceIn(0, tabs.size - 1)
  val isSummaryTab = safeIndex >= schemaSections.size
  val currentSchemaSection = schemaSections.getOrNull(safeIndex)

  Column(modifier = Modifier.fillMaxSize()) {
    AppTabRow(
      tabs = tabs,
      selectedIndex = safeIndex,
      onTabSelected = onTabSelected,
      distributeEvenly = true,
      indicatorOverhang = Dimens.TabIndicatorOverhang,
      modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
    )

    // The scrollable content takes all remaining height; the action bar stays pinned below it.
    Box(modifier = Modifier.weight(1f)) {
      if (isSummaryTab) {
        var summary by remember { mutableStateOf<List<SummarySection>>(emptyList()) }
        val mediaLabel = stringResource(R.string.enrollment_summary_completed)
        val photoLabel = stringResource(R.string.enrollment_consent_photo_captured)
        // Rebuild whenever answers/media/photo change so the review is always current.
        LaunchedEffect(state.answers, state.mediaCompleted, state.capturedImages, state.version) {
          summary = viewModel.buildSummary(mediaLabel, photoLabel)
        }
        DynamicFormSummary(
          sections = summary,
          onEditSection = { title -> onTabSelected(schemaSections.indexOf(title).coerceAtLeast(0)) },
        )
      } else {
        DynamicFormFieldList(
          fields = currentSchemaSection?.let(viewModel::fieldsInSection).orEmpty(),
          state = state,
          viewModel = viewModel,
          isConsentSection = currentSchemaSection == CONSENT_SECTION,
        )
      }
    }

    FormActionBar(
      showSubmit = isSummaryTab,
      nextLabel = tabs.getOrNull(safeIndex + 1),
      // Per-tab gate: the forward button unlocks only once THIS tab's required fields are filled;
      // Submit (Summary tab) additionally requires the whole form + cross-field rules to pass.
      canGoNext = currentSchemaSection?.let(viewModel::isSectionReady) ?: false,
      canSubmit = viewModel.isReadyToSubmit(),
      submissionState = state.submissionState,
      onNext = { onTabSelected(safeIndex + 1) },
      onSubmit = { viewModel.submit() },
    )
  }
}

@Composable
private fun DynamicFormFieldList(
  fields: List<FormFieldSchema>,
  state: DynamicFormUiState,
  viewModel: DynamicMotherRegistrationViewModel,
  isConsentSection: Boolean,
) {
  val context = LocalContext.current
  // Live capture into app-private storage, one target file per question_code — mirrors
  // EnrollmentScreen's consent photo / VisitFormScreen's sonography report, but keyed per field
  // rather than a single hardcoded file, since the dynamic schema can declare more than one
  // `image` field. remember{} keeps the map for this composition; each launch overwrites its
  // own field's file, which also implements retake-replaces-previous.
  val photoUriFor = remember { mutableMapOf<String, Uri>() }
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

  // On the Consent tab the design puts the "Ensure that the beneficiary" heading above the first
  // consent question (the first radio in the section); -1 elsewhere so it never renders.
  val ensureHeadingBeforeIndex =
    if (isConsentSection) fields.indexOfFirst { it.inputType == FormFieldInputType.RADIO } else -1

  LazyColumn(
    contentPadding = PaddingValues(Dimens.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxSize(),
  ) {
    if (isConsentSection) {
      item(key = "consent_intro") {
        Text(
          text = stringResource(R.string.enrollment_consent_instruction),
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    itemsIndexed(fields, key = { _, field -> field.questionCode }) { index, field ->
      Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        if (index == ensureHeadingBeforeIndex) {
          Text(
            text = stringResource(R.string.enrollment_consent_ensure),
            style = MaterialTheme.typography.titleLarge,
            color = NeutralG400,
          )
        }
        DynamicFormField(
          field = field,
          answers = state.answers,
          mediaCompleted = field.questionCode in state.mediaCompleted,
          capturedImageUri = state.capturedImages[field.questionCode],
          loadOptions = { viewModel.optionsFor(field) },
          onSingleAnswer = { value -> viewModel.setAnswer(field.questionCode, value) },
          onMultiAnswer = { values -> viewModel.setMultiAnswer(field.questionCode, values) },
          onPlayMedia = { viewModel.markMediaComplete(field.questionCode) },
          onCaptureImage = {
            val uri = photoUriFor.getOrPut(field.questionCode) {
              val photoFile = File(File(context.filesDir, "dynamic-form"), "${field.questionCode}.jpg")
                .apply { parentFile?.mkdirs() }
              FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            }
            captureTargetCode = field.questionCode
            takePictureLauncher.launch(uri)
          },
        )
      }
    }
  }
}

/** Pinned bottom action bar: forward navigation ("Personal Info →", …, "Summary →") on the form
 * tabs, "Submit" on the Summary tab — matching the design's per-tab flow. Right-aligned pill. */
@Composable
private fun FormActionBar(
  showSubmit: Boolean,
  nextLabel: String?,
  canGoNext: Boolean,
  canSubmit: Boolean,
  submissionState: SubmissionState,
  onNext: () -> Unit,
  onSubmit: () -> Unit,
) {
  // Submission errors are shown as a snackbar by the host screen, not inline here.
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      if (showSubmit) {
        PrimaryButton(
          text = stringResource(R.string.enrollment_submit),
          enabled = canSubmit && submissionState !is SubmissionState.Saving,
          loading = submissionState is SubmissionState.Saving,
          onClick = onSubmit,
          fullWidth = false,
          trailingIcon = painterResource(R.drawable.ic_arrow_right),
        )
      } else {
        PrimaryButton(
          // Section labels have no line breaks, but guard anyway so the pill never wraps oddly.
          text = (nextLabel ?: "").replace("\n", " "),
          enabled = canGoNext,
          onClick = onNext,
          fullWidth = false,
          trailingIcon = painterResource(R.drawable.ic_arrow_right),
        )
      }
    }
  }
}
