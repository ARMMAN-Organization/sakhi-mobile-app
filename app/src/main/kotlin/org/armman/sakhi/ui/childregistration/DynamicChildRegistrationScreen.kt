package org.armman.sakhi.ui.childregistration

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.motherlink.MotherPrefillQuestionCodes
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.components.StatusBanner
import org.armman.sakhi.ui.components.StatusBannerVariant
import org.armman.sakhi.ui.enrollment.steps.EnrollmentCompleteContent
import org.armman.sakhi.ui.forms.CrossFieldErrorAttribution
import org.armman.sakhi.ui.forms.DynamicFormField
import org.armman.sakhi.ui.forms.crossFieldMessage
import org.armman.sakhi.ui.forms.labelResolver
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.White
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schema `section` label whose tab gets the design's Consent-specific decorations. */
private const val CONSENT_SECTION = "Consent"

/**
 * Screen hosting the CR-020 dynamic Children Register form — standalone twin of
 * `DynamicMotherRegistrationScreen`, wired as the "Child" enrollment path from the entry selector.
 * Fields are grouped into tabs by the schema's `section` key; a client-side **Summary** tab is
 * appended for review + submit. A client-side eligibility error (age windows, future DOB) renders
 * inline above the action bar and blocks Submit; refused consent instead aborts the registration
 * outright (toast + back to Home, see [onConsentRefused]). On a successful submit
 * the sheet swaps to the shared [EnrollmentCompleteContent] success state.
 */
@Composable
fun DynamicChildRegistrationScreen(
  onBack: () -> Unit,
  /** Leaves the enrollment sub-graph (→ Home) once the Sakhi is done with the success screen. */
  onSubmitted: () -> Unit,
  /** Leaves the enrollment sub-graph (→ the child's profile) when the Sakhi taps "See Visit
   * Form" on the success screen — mirrors [DynamicMotherRegistrationScreen]'s onStartVisitForm. */
  onStartVisitForm: (beneficiaryId: String) -> Unit,
  /** Leaves the enrollment sub-graph (→ Home) when the beneficiary refuses consent — the
   * registration is abandoned, so nothing is saved and the form is not left on screen. */
  onConsentRefused: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: DynamicChildRegistrationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val today = remember {
    LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
  }
  var selectedTabIndex by remember { mutableStateOf(0) }
  val context = LocalContext.current
  val isSuccess = state.submissionState is SubmissionState.Success
  val snackbarHostState = remember { SnackbarHostState() }

  // Backend submission failures surface as a transient snackbar, keyed on the state so each new
  // failure re-shows. The message is chosen from the failure kind (duplicate vs generic).
  val duplicateMessage = stringResource(R.string.child_reg_duplicate)
  val submitFailedMessage = stringResource(R.string.child_reg_submit_failed)
  LaunchedEffect(state.submissionState) {
    (state.submissionState as? SubmissionState.Failed)?.let { failed ->
      val message = when (failed.kind) {
        ChildSubmitFailureKind.DUPLICATE -> duplicateMessage
        ChildSubmitFailureKind.GENERIC -> failed.backendMessage ?: submitFailedMessage
      }
      snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
    }
  }

  // "Did we receive consent? → No" aborts the registration on the spot: a toast explains why, then
  // the Sakhi is returned Home. A Toast (not the snackbar above) because the host is destroyed by
  // the navigation that follows in the same frame — a snackbar would never be seen.
  val consentRefusedMessage = stringResource(R.string.enrollment_consent_refused_toast)
  LaunchedEffect(Unit) {
    viewModel.consentRefused.collect {
      Toast.makeText(context, consentRefusedMessage, Toast.LENGTH_LONG).show()
      onConsentRefused()
    }
  }

  BackHandler(enabled = isSuccess) { onSubmitted() }

  Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        BackHeader(
          title = stringResource(R.string.enrollment_back_title),
          subtitle = today,
          onBack = { if (isSuccess) onSubmitted() else onBack() },
        )
        Surface(
          color = White,
          shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
          modifier = Modifier.fillMaxSize(),
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            when {
              isSuccess -> EnrollmentCompleteContent(
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
                  text = stringResource(R.string.child_reg_title),
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
  state: ChildFormUiState,
  viewModel: DynamicChildRegistrationViewModel,
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
) {
  val schemaSections = viewModel.sections()
  val summaryLabel = stringResource(R.string.enrollment_tab_summary)
  val tabs = schemaSections + summaryLabel
  val safeIndex = selectedTabIndex.coerceIn(0, tabs.size - 1)
  val isSummaryTab = safeIndex >= schemaSections.size
  val currentSchemaSection = schemaSections.getOrNull(safeIndex)

  // Violated cross-field rules, attributed to the field that should show them inline and listed in
  // a Summary-tab banner — without this a violation only disabled Submit, with nothing on screen.
  val labelOf = labelResolver(viewModel.visibleFields())
  val crossFieldMessages: Map<String, String> = buildMap {
    CrossFieldErrorAttribution.byQuestionCode(viewModel.crossFieldViolations())
      .forEach { (code, rule) -> crossFieldMessage(rule, labelOf)?.let { put(code, it) } }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    AppTabRow(
      tabs = tabs,
      selectedIndex = safeIndex,
      onTabSelected = onTabSelected,
      distributeEvenly = true,
      modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ItemSpacing),
    )

    Box(modifier = Modifier.weight(1f)) {
      if (isSummaryTab) {
        var summary by remember { mutableStateOf<List<SummarySection>>(emptyList()) }
        val mediaLabel = stringResource(R.string.enrollment_summary_completed)
        val photoLabel = stringResource(R.string.enrollment_consent_photo_captured)
        LaunchedEffect(state.answers, state.mediaCompleted, state.capturedImages, state.version) {
          summary = viewModel.buildSummary(mediaLabel, photoLabel)
        }
        ChildFormSummary(
          sections = summary,
          onEditSection = { title -> onTabSelected(schemaSections.indexOf(title).coerceAtLeast(0)) },
        )
      } else {
        ChildFormFieldList(
          fields = currentSchemaSection?.let(viewModel::fieldsInSection).orEmpty(),
          state = state,
          viewModel = viewModel,
          crossFieldMessages = crossFieldMessages,
          isConsentSection = currentSchemaSection == CONSENT_SECTION,
          // Identifies which section the reused list is showing, so it can reset to the first
          // question on a tab switch (see ChildFormFieldList).
          sectionKey = currentSchemaSection,
        )
      }
    }

    // Summary tab hosts Submit, so a cross-field rule referencing fields on earlier tabs still
    // explains itself here rather than leaving Submit disabled for no visible reason.
    if (isSummaryTab && crossFieldMessages.isNotEmpty()) {
      StatusBanner(
        message = crossFieldMessages.values.joinToString(separator = "\n"),
        variant = StatusBannerVariant.Error,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SmallSpacing),
      )
    }

    // Inline client-side age-eligibility error (blocks Submit), rendered above the action bar.
    // Consent refusal is NOT one of these — it exits the flow instead (see [onConsentRefused]).
    state.validationError?.let { error ->
      StatusBanner(
        message = stringResource(validationErrorRes(error)),
        variant = StatusBannerVariant.Error,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SmallSpacing),
      )
    }

    FormActionBar(
      showSubmit = isSummaryTab,
      nextLabel = tabs.getOrNull(safeIndex + 1),
      canGoNext = currentSchemaSection?.let(viewModel::isSectionReady) ?: false,
      canSubmit = viewModel.isReadyToSubmit(),
      submissionState = state.submissionState,
      onNext = { onTabSelected(safeIndex + 1) },
      onSubmit = { viewModel.submit() },
    )
  }
}

/** Maps a [ChildValidationError] to its user-facing string resource. */
private fun validationErrorRes(error: ChildValidationError): Int = when (error) {
  ChildValidationError.DOB_FUTURE -> R.string.child_reg_dob_future
  ChildValidationError.INELIGIBLE_DIRECT -> R.string.child_reg_ineligible_direct
  ChildValidationError.INELIGIBLE_MOTHER -> R.string.child_reg_ineligible_mother
}

@Composable
private fun ChildFormFieldList(
  fields: List<FormFieldSchema>,
  state: ChildFormUiState,
  viewModel: DynamicChildRegistrationViewModel,
  /** Violated cross-field rule messages keyed by `question_code`; see [CrossFieldErrorAttribution]. */
  crossFieldMessages: Map<String, String>,
  isConsentSection: Boolean,
  /** Title of the section currently rendered; drives the scroll reset below. */
  sectionKey: String?,
) {
  val context = LocalContext.current
  val listState = rememberLazyListState()

  // Every section reuses this composable instance (only [fields] changes), so [listState] — and the
  // previous section's scroll offset with it — survives a tab switch and lands the Sakhi mid-form.
  // Reset to the first question whenever the section changes. Keyed on [sectionKey] rather than
  // [fields] so conditionally revealed fields (visibleWhen) appearing mid-section don't yank the
  // list back to the top. Unconditional here: unlike the mother flow this screen has no post-submit
  // error scroll to yield to — if that is ever ported over, guard this with
  // [FormSectionScroll.shouldResetToTop] as DynamicMotherRegistrationScreen does.
  LaunchedEffect(sectionKey) { listState.scrollToItem(0) }

  // Live capture into app-private storage, one target file per question_code (retake replaces).
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
        // `mother_beneficiary_id` on the registered-mother path renders as the mother picker
        // instead of the schema's `number` box: the Sakhi selects a name, the answer stored is the
        // beneficiary UUID. See MotherLinkField for why this can't be a schema-driven `select`.
        if (viewModel.isMotherLinkField(field)) {
          MotherLinkField(
            label = field.label,
            selectedLabel = viewModel.selectedMotherLabel(),
            mothers = state.motherOptions,
            isLoading = state.isLoadingMothers,
            loadFailed = state.motherLoadFailed,
            onSelect = viewModel::selectMother,
            onRetry = viewModel::loadMothers,
          )
        } else {
          DynamicFormField(
            field = field,
            answers = state.answers,
            registrationDate = viewModel.registrationDate,
            errorText = crossFieldMessages[field.questionCode],
            mediaCompleted = field.questionCode in state.mediaCompleted,
            capturedImageUri = state.capturedImages[field.questionCode],
            // mother_age is locked shut for exactly as long as it still holds the value
            // MotherPrefill derived from the linked mother's DOB — see
            // MotherPrefillQuestionCodes.MOTHER_AGE's doc. It reverts to a normal editable NUMBER
            // field the moment the Sakhi edits it (isPrefilledFromMother drops the code) or the
            // path switches away from the registered-mother flow (MotherPrefill.clear wipes it).
            readOnlyQuestionCodes = if (viewModel.isPrefilledFromMother(MotherPrefillQuestionCodes.MOTHER_AGE)) {
              setOf(MotherPrefillQuestionCodes.MOTHER_AGE)
            } else {
              emptySet()
            },
            loadOptions = { viewModel.optionsFor(field) },
            onSingleAnswer = { value -> viewModel.setAnswer(field.questionCode, value) },
            onMultiAnswer = { values -> viewModel.setMultiAnswer(field.questionCode, values) },
            onPlayMedia = { viewModel.markMediaComplete(field.questionCode) },
            onCaptureImage = {
              val uri = photoUriFor.getOrPut(field.questionCode) {
                val photoFile = File(File(context.filesDir, "child-registration"), "${field.questionCode}.jpg")
                  .apply { parentFile?.mkdirs() }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
              }
              captureTargetCode = field.questionCode
              takePictureLauncher.launch(uri)
            },
          )
          // Tells the Sakhi this value was copied from the linked mother rather than typed by her,
          // and therefore worth a glance. Disappears for this field as soon as she edits it.
          if (viewModel.isPrefilledFromMother(field.questionCode)) {
            MotherPrefillHint()
          }
        }
      }
    }
  }
}

/** Pinned bottom action bar: forward navigation on the form tabs, "Submit" on the Summary tab. */
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

/** Summary tab's "Review Details": one white bordered card per section with a per-section Edit pill
 * and its answered label/value rows. Child-local copy of the mother flow's DynamicFormSummary (the
 * child flow uses its own [SummarySection] type to stay standalone). */
@Composable
private fun ChildFormSummary(
  sections: List<SummarySection>,
  onEditSection: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    contentPadding = PaddingValues(Dimens.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = modifier.fillMaxSize(),
  ) {
    item(key = "review_title") {
      Text(
        text = stringResource(R.string.enrollment_summary_title),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
    }
    items(sections, key = { it.title }) { section ->
      ChildReviewCard(title = section.title, onEdit = { onEditSection(section.title) }) {
        section.rows.forEach { row -> ChildReviewRow(label = row.label, value = row.value) }
      }
    }
  }
}

@Composable
private fun ChildReviewCard(
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
        trailingIcon = painterResource(R.drawable.ic_pencil_simple),
        height = Dimens.SmallButtonHeight,
      )
    }
    rows()
  }
}

@Composable
private fun ChildReviewRow(label: String, value: String) {
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
