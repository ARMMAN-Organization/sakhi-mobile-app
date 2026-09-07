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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.ConfirmationDialog
import org.armman.sakhi.ui.components.FullScreenLoadingOverlay
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
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
  /** The success screen's "Start Visit Form" CTA — opens the woman just enrolled, whose visits live
   * on her profile. Distinct from [onSubmitted] on purpose: leaving the success screen goes Home,
   * tapping the CTA goes to her. Both clear the enrollment sub-graph. */
  onStartVisitForm: (beneficiaryId: String) -> Unit,
  /** Leaves the enrollment sub-graph (→ Home) when the beneficiary refuses consent — the
   * registration is abandoned, so nothing is saved and the form is not left on screen. */
  onConsentRefused: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: DynamicMotherRegistrationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }
  var selectedTabIndex by remember { mutableStateOf(0) }
  val context = LocalContext.current
  val isSuccess = state.submissionState is SubmissionState.Success
  val snackbarHostState = remember { SnackbarHostState() }

  // Submission errors (e.g. "token expired", validation/conflict messages) surface as a transient
  // snackbar rather than persistent red text — keyed on the state so each new failure re-shows.
  // A hard duplicate (SRS FR-S-2.4) is a fixed sentence from resources, not backend text, so it is
  // localised like the rest of the form's copy.
  val duplicateBlockedMessage = stringResource(R.string.enrollment_duplicate_blocked)
  LaunchedEffect(state.submissionState) {
    when (val submission = state.submissionState) {
      is SubmissionState.Failed ->
        snackbarHostState.showSnackbar(message = submission.message, duration = SnackbarDuration.Long)

      SubmissionState.DuplicateBlocked ->
        snackbarHostState.showSnackbar(message = duplicateBlockedMessage, duration = SnackbarDuration.Long)

      else -> Unit
    }
  }

  // SRS FR-S-2.5 — a completed earlier pregnancy exists; confirming enrolls this pregnancy as a new
  // case linked to that one, and never overwrites it.
  if (state.duplicatePrompt != null) {
    ConfirmationDialog(
      title = stringResource(R.string.enrollment_duplicate_new_pregnancy_title),
      message = stringResource(R.string.enrollment_duplicate_new_pregnancy_message),
      confirmLabel = stringResource(R.string.enrollment_duplicate_new_pregnancy_confirm),
      cancelLabel = stringResource(R.string.enrollment_duplicate_new_pregnancy_cancel),
      onConfirm = viewModel::onConfirmNewPregnancy,
      onCancel = viewModel::onDismissDuplicatePrompt,
    )
  }

  // "Did we receive consent? → No" aborts the registration on the spot: a toast explains why, then
  // the Sakhi is returned Home. A Toast (not the snackbar below) because the host is destroyed by
  // the navigation that follows in the same frame — a snackbar would never be seen.
  val consentRefusedMessage = stringResource(R.string.enrollment_consent_refused_toast)
  LaunchedEffect(Unit) {
    viewModel.consentRefused.collect {
      Toast.makeText(context, consentRefusedMessage, Toast.LENGTH_LONG).show()
      onConsentRefused()
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
              // She has no scheduled visit yet, so there is no visit form to open directly; her
              // profile is where her visits appear once scheduled, and it is the only screen that
              // resolves a freshly enrolled (unsynced) UUID id. Replaces the old "coming soon"
              // stub — the toast was a dead end on the last screen of a completed enrollment.
              onStartVisitForm = { onStartVisitForm(viewModel.beneficiaryId) },
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
      FullScreenLoadingOverlay(visible = state.submissionState is SubmissionState.Saving)
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

  // A field-attributable submit failure jumps to the section holding the first flagged field; the
  // list itself then scrolls to the field (see DynamicFormFieldList). One-shot: the ViewModel
  // clears errorScroll once the list reports it scrolled.
  LaunchedEffect(state.errorScroll) {
    val target = state.errorScroll ?: return@LaunchedEffect
    val sectionIndex = schemaSections.indexOf(target.section)
    if (sectionIndex >= 0) onTabSelected(sectionIndex)
  }

  // Violated cross-field rules (e.g. "children under 5" higher than "family members"), resolved
  // once per composition: attributed to the field that should show them inline, and listed in a
  // banner on the Summary tab so a rule spanning other tabs still explains a blocked Submit.
  //
  // Resolved against EVERY schema field, not just the currently visible ones: a rule can name a
  // field that's hidden right now (e.g. Living children/Still births/Abortions while Gravida == 1)
  // and it's still a real question with a real label, not one that stopped existing — falling back
  // to visibleFields() here printed the raw question_code instead (the reported bug).
  val labelOf = labelResolver(viewModel.allFields())
  val crossFieldMessages: Map<String, String> = buildMap {
    CrossFieldErrorAttribution.byQuestionCode(viewModel.crossFieldViolations())
      .forEach { (code, rule) -> crossFieldMessage(rule, labelOf)?.let { put(code, it) } }
    // Spec obstetric rules (Gravida/Para/abortions/still births/dead children) are checked here
    // rather than server-side: validationJson can express neither a sum with a constant term nor
    // "live births", which the form never captures directly. A backend rule saying the same thing
    // already occupies the field's slot, so `putIfAbsent` keeps one message per field.
    viewModel.visibleFields().forEach { field ->
      FormObstetricRuleset.violationFor(field.questionCode, state.answers)
        ?.let { violation -> obstetricMessage(violation) }
        ?.let { putIfAbsent(field.questionCode, it) }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    AppTabRow(
      tabs = tabs,
      selectedIndex = safeIndex,
      onTabSelected = onTabSelected,
      distributeEvenly = true,
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
          crossFieldMessages = crossFieldMessages,
          isConsentSection = currentSchemaSection == CONSENT_SECTION,
          // Identifies which section the reused list is showing, so it can reset to the first
          // question on a tab switch (see DynamicFormFieldList).
          sectionKey = currentSchemaSection,
          // Only scroll within the list once it's the section the flagged field lives on — the tab
          // switch below moves there first, then this list (now holding the field) scrolls to it.
          scrollTarget = state.errorScroll?.takeIf { it.section == currentSchemaSection },
          onScrolled = viewModel::onErrorScrollHandled,
        )
      }
    }

    // Summary tab is where Submit lives, so any violated cross-field rule is spelled out here even
    // when the fields it references sit on earlier tabs — otherwise Submit is disabled with no
    // on-screen reason.
    if (isSummaryTab && crossFieldMessages.isNotEmpty()) {
      StatusBanner(
        message = crossFieldMessages.values.joinToString(separator = "\n"),
        variant = StatusBannerVariant.Error,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SmallSpacing),
      )
    }

    FormActionBar(
      // First tab (Consent) has nothing to go back to within the form — the header's back arrow
      // already exits it. Every later tab (including Summary) gets a Back pill, matching the
      // static enrollment flow's per-step design this dynamic screen otherwise mirrors.
      showBack = safeIndex > 0,
      showSubmit = isSummaryTab,
      nextLabel = tabs.getOrNull(safeIndex + 1),
      // Per-tab gate: the forward button unlocks only once THIS tab's required fields are filled;
      // Submit (Summary tab) additionally requires the whole form + cross-field rules to pass.
      canGoNext = currentSchemaSection?.let(viewModel::isSectionReady) ?: false,
      canSubmit = viewModel.isReadyToSubmit(),
      submissionState = state.submissionState,
      onBack = { onTabSelected(safeIndex - 1) },
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
  /** Violated cross-field rule messages keyed by `question_code`; see [CrossFieldErrorAttribution]. */
  crossFieldMessages: Map<String, String>,
  isConsentSection: Boolean,
  /** Title of the section currently rendered; drives the scroll reset below. */
  sectionKey: String?,
  scrollTarget: ErrorScrollTarget?,
  onScrolled: () -> Unit,
) {
  val context = LocalContext.current
  val listState = rememberLazyListState()

  // Every section reuses this composable instance (only [fields] changes), so [listState] — and the
  // previous section's scroll offset with it — survives a tab switch and lands the Sakhi mid-form.
  // Reset to the first question whenever the section changes. Keyed on [sectionKey] rather than
  // [fields] so conditionally revealed fields (visibleWhen) appearing mid-section don't yank the
  // list back to the top. See [FormSectionScroll.shouldResetToTop] for the error-scroll guard.
  LaunchedEffect(sectionKey) {
    if (FormSectionScroll.shouldResetToTop(hasPendingErrorScroll = scrollTarget != null)) {
      listState.scrollToItem(0)
    }
  }

  // Scroll to the flagged field once this list is the one holding it. Keyed on the one-shot token
  // AND [fields] so it also fires right after a tab switch swaps in this section's fields (the token
  // alone wouldn't retrigger, since this composable instance is reused across sections).
  LaunchedEffect(scrollTarget?.token, fields) {
    val target = scrollTarget ?: return@LaunchedEffect
    val fieldIndex = fields.indexOfFirst { it.questionCode == target.questionCode }
    if (fieldIndex < 0) return@LaunchedEffect
    // The Consent tab prepends one intro item before the field items; offset the index by it.
    val itemIndex = fieldIndex + if (isConsentSection) 1 else 0
    listState.animateScrollToItem(itemIndex)
    onScrolled()
  }
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
    state = listState,
    // Extra bottom slack, not symmetric padding: `bringIntoView` can only scroll as far as the
    // content allows, so without room past the last field a focused field near the end of a section
    // stays pinned against the viewport edge (or clipped) however hard it asks to be revealed.
    contentPadding = PaddingValues(
      start = Dimens.ScreenPadding,
      end = Dimens.ScreenPadding,
      top = Dimens.ScreenPadding,
      bottom = Dimens.FormListBottomSlack,
    ),
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
          registrationDate = viewModel.registrationDate,
          mediaCompleted = field.questionCode in state.mediaCompleted,
          capturedImageUri = state.capturedImages[field.questionCode],
          // Server error first (it reflects the last submit attempt), then a live cross-field
          // violation, so the Sakhi always sees a reason rather than a dead Submit button.
          errorText = state.fieldErrors[field.questionCode] ?: crossFieldMessages[field.questionCode],
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

/** Pinned bottom action bar: an outlined "Back" pill (every tab but the first) on the left,
 * forward navigation ("Personal Info →", …, "Summary →") or "Submit" (Summary tab) on the right —
 * matching the design's per-tab flow, and the same Back/forward pairing the static enrollment
 * flow's [org.armman.sakhi.ui.enrollment.EnrollmentScreen] uses for its own steps. */
@Composable
private fun FormActionBar(
  showBack: Boolean,
  showSubmit: Boolean,
  nextLabel: String?,
  canGoNext: Boolean,
  canSubmit: Boolean,
  submissionState: SubmissionState,
  onBack: () -> Unit,
  onNext: () -> Unit,
  onSubmit: () -> Unit,
) {
  // Submission errors are shown as a snackbar by the host screen, not inline here.
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      if (showBack) {
        SecondaryButton(
          text = stringResource(R.string.enrollment_pi_back),
          onClick = onBack,
          leadingIcon = painterResource(R.drawable.ic_arrow_left),
        )
      }
      // Fills all remaining width so the forward/submit button stays right-aligned whether or not
      // a Back pill is showing — same trick the static flow's StepFooter uses.
      Spacer(modifier = Modifier.weight(1f))
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
