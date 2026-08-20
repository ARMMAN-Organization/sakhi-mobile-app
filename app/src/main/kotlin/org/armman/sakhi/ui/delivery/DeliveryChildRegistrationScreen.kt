package org.armman.sakhi.ui.delivery

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.forms.DynamicFormField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.White
import java.io.File
import java.time.LocalDate

/**
 * `CHILD_REGISTRATION` form screen for CR-042's Delivery Event Session — the twin of
 * [DeliverySessionScreen] for the step that follows it. Fields are grouped into tabs by the
 * schema's `section` key with a client-side **Summary** tab appended for review + submit — the
 * same tabs+Summary shape as [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationScreen],
 * minus that screen's consent-refusal/mother-picker/eligibility-error concerns, none of which apply
 * here (the mother is already known via [DeliveryChildRegistrationViewModel.motherLocalBeneficiaryId],
 * and this flow has no success screen of its own — see [DeliveryChildRegistrationEvent.Submitted]).
 *
 * For a twin/triplet delivery this same screen instance is reused for every child — see
 * [DeliveryChildRegistrationViewModel]'s class doc — so [DeliveryChildRegistrationEvent
 * .Submitted.hasMoreChildren] only shows a toast and lets the (already-reloading) ViewModel refresh
 * the field list for the next child in place, rather than navigating anywhere. [selectedTabIndex]
 * resets to the first tab on that reload (keyed on [DeliveryChildRegistrationUiState.childIndex])
 * so the next child's registration always starts from its first section rather than resuming on
 * whatever tab the previous child's submission left selected.
 *
 * [onNavigateToVisit] opens PP1 once every child is registered, the same hand-off contract as
 * [DeliverySessionScreen.onNavigateToVisit].
 */
@Composable
fun DeliveryChildRegistrationScreen(
  onBack: () -> Unit,
  onNavigateToVisit: (beneficiaryId: String, localScheduleUuid: String) -> Unit,
  viewModel: DeliveryChildRegistrationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var selectedTabIndex by remember { mutableStateOf(0) }

  LaunchedEffect(state.childIndex) { selectedTabIndex = 0 }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        DeliveryChildRegistrationEvent.ExitForm -> onBack()
        is DeliveryChildRegistrationEvent.Submitted -> {
          if (event.hasMoreChildren) {
            Toast.makeText(context, "Child registered. Continuing to the next child.", Toast.LENGTH_SHORT).show()
          } else {
            Toast.makeText(context, "Child registered.", Toast.LENGTH_SHORT).show()
            val pp1 = event.pp1LocalScheduleUuid
            if (pp1 != null) {
              onNavigateToVisit(viewModel.motherLocalBeneficiaryId, pp1)
            } else {
              onBack()
            }
          }
        }
        DeliveryChildRegistrationEvent.QueuedOffline -> {
          Toast.makeText(context, "Saved. Will upload when back online.", Toast.LENGTH_SHORT).show()
          onBack()
        }
        is DeliveryChildRegistrationEvent.SubmitFailed -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.beneficiary_profile_delivery_child_registration),
        subtitle = "",
        onBack = { viewModel.exitForm() },
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> Centered { Text("Couldn't load this form. Check your connection and try again.") }
          else -> DeliveryChildRegistrationBody(
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

@Composable
private fun DeliveryChildRegistrationBody(
  state: DeliveryChildRegistrationUiState,
  viewModel: DeliveryChildRegistrationViewModel,
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
) {
  val schemaSections = viewModel.sections()
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
        DeliveryChildFormSummary(
          sections = summary,
          onEditSection = { title -> onTabSelected(schemaSections.indexOf(title).coerceAtLeast(0)) },
        )
      } else {
        DeliveryChildFormFieldList(
          fields = currentSchemaSection?.let(viewModel::fieldsInSection).orEmpty(),
          state = state,
          viewModel = viewModel,
          sectionKey = currentSchemaSection,
        )
      }
    }

    DeliveryChildFormActionBar(
      showSubmit = isSummaryTab,
      nextLabel = tabs.getOrNull(safeIndex + 1),
      canGoNext = currentSchemaSection?.let(viewModel::isSectionReady) ?: false,
      canSubmit = viewModel.isReadyToSubmit(),
      isSubmitting = state.isSubmitting,
      onNext = { onTabSelected(safeIndex + 1) },
      onSubmit = { viewModel.onSubmit() },
    )
  }
}

@Composable
private fun DeliveryChildFormFieldList(
  fields: List<FormFieldSchema>,
  state: DeliveryChildRegistrationUiState,
  viewModel: DeliveryChildRegistrationViewModel,
  /** Title of the section currently rendered — unused beyond identity today (this list has no
   * scroll-position memory to reset, unlike the child/mother registration flows' reused
   * LazyColumn), kept as a parameter so a future scroll-reset can key off it the same way. */
  sectionKey: String?,
) {
  val context = LocalContext.current

  // Same live-capture-into-app-private-storage pattern as DeliverySessionScreen's field list.
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
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(Dimens.ItemSpacing),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
  ) {
    items(fields, key = { it.questionCode }) { field ->
      DynamicFormField(
        field = field,
        answers = state.answers,
        registrationDate = LocalDate.now(),
        beneficiaryRegistrationDate = null,
        mediaCompleted = field.questionCode in state.mediaCompleted,
        capturedImageUri = state.capturedImages[field.questionCode],
        loadOptions = { viewModel.optionsFor(field) },
        onSingleAnswer = { value -> viewModel.setAnswer(field.questionCode, value) },
        onMultiAnswer = { values -> viewModel.setMultiAnswer(field.questionCode, values) },
        onPlayMedia = { viewModel.markMediaComplete(field.questionCode) },
        onCaptureImage = {
          val uri = photoUriFor.getOrPut(field.questionCode) {
            val photoFile = File(File(context.filesDir, "delivery-child-registration"), "${field.questionCode}.jpg")
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

/** Pinned bottom action bar: forward navigation on the form tabs, "Submit" on the Summary tab.
 * Adapted from [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationScreen]'s
 * `FormActionBar` for this screen's plain [Boolean] `isSubmitting` (this flow has no
 * `SubmissionState` sealed type). */
@Composable
private fun DeliveryChildFormActionBar(
  showSubmit: Boolean,
  nextLabel: String?,
  canGoNext: Boolean,
  canSubmit: Boolean,
  isSubmitting: Boolean,
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
          text = "Submit",
          enabled = canSubmit && !isSubmitting,
          loading = isSubmitting,
          onClick = onSubmit,
          fullWidth = false,
        )
      } else {
        PrimaryButton(
          text = (nextLabel ?: "").replace("\n", " "),
          enabled = canGoNext,
          onClick = onNext,
          fullWidth = false,
        )
      }
    }
  }
}

/** Summary tab's "Review Details": one white bordered card per section with a per-section Edit
 * pill and its answered label/value rows. Delivery-local copy of the child registration flow's
 * `ChildFormSummary` (shares [SummarySection]/[SummaryRow] from [DeliverySummaryModels] instead of
 * a private per-screen type — see that file's doc for why). */
@Composable
private fun DeliveryChildFormSummary(
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
      DeliveryReviewCard(title = section.title, onEdit = { onEditSection(section.title) }) {
        section.rows.forEach { row -> DeliveryReviewRow(label = row.label, value = row.value) }
      }
    }
  }
}

@Composable
private fun DeliveryReviewCard(
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
private fun DeliveryReviewRow(label: String, value: String) {
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

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
}
