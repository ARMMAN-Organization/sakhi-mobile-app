package org.armman.sakhi.ui.adhocform

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
import androidx.compose.ui.res.painterResource
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
 * Generic screen for any of the five schema-driven ad-hoc forms
 * (`REFERRAL_VISIT`/`REFERRAL_FOLLOWUP_VISIT`/`ANC_CLOSURE_VISIT`/`CHILD_CLOSURE_VISIT`/
 * `BENEFICIARY_REOPEN_VISIT`) — driven by [AdHocFormViewModel]. Reuses [DynamicFormField], the same
 * per-field renderer the Mother/Child Registration and Visit Form screens already use — there was
 * no need to build a second one.
 *
 * CR-Referral-01 (2026-09-01): fields are grouped into one tab per schema `section` plus a final
 * client-side **Summary** review tab, the same "one tab per section + Summary" shell every other
 * tabbed dynamic form already has ([org.armman.sakhi.ui.childregistration
 * .DynamicChildRegistrationScreen], [org.armman.sakhi.ui.forms.DynamicMotherRegistrationScreen]) —
 * this screen previously stayed a single flat scrollable list regardless of section count, which
 * read as a missing Summary/review step once a real design (Referral Follow-up's four-section
 * schema) was checked against it. A schema with only one section still gets two tabs (that section
 * + Summary); this is purely presentational — no field/question/validation/submission change.
 *
 * No TIME/REQUIRED_IF_SELECTED field support — deliberately deferred (see
 * [AdHocFormViewModel.crossFieldViolations]'s doc and [org.armman.sakhi.data.forms
 * .FormFieldInputType]'s own TODO). `image` fields ARE supported (Referral Follow-up's two photo
 * questions) — live capture into app-private storage, same "one target file per question_code"
 * pattern as [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen]'s own `onCaptureImage`; no
 * `media` (video/audio) field exists on any of the five forms' schemas, so that input type stays
 * unimplemented here.
 */
@Composable
fun AdHocFormScreen(
  onBack: () -> Unit,
  viewModel: AdHocFormViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var selectedTabIndex by remember { mutableStateOf(0) }

  // CR-Closure-03: the PP5-triggered forced mother-closure prompt must be completed before exit
  // (SRS) -- swallow the hardware/gesture back action for every other ad-hoc form this is a no-op
  // effect, since BackHandler is disabled(false-guarded) by default.
  BackHandler(enabled = viewModel.forced) { /* no-op: must submit or the app is left running the form */ }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        AdHocFormEvent.ExitForm -> onBack()
        AdHocFormEvent.Submitted -> {
          Toast.makeText(context, "Submitted", Toast.LENGTH_SHORT).show()
          onBack()
        }
        AdHocFormEvent.QueuedOffline -> {
          Toast.makeText(context, "Saved. Will upload when back online.", Toast.LENGTH_SHORT).show()
          onBack()
        }
        is AdHocFormEvent.SubmitFailed -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      // CR-Closure-03: the back-arrow tap is the other (non-system-back) way out of this screen --
      // suppressed the same way as the BackHandler above when forced.
      BackHeader(
        title = viewModel.formTitle,
        subtitle = "",
        onBack = { if (!viewModel.forced) viewModel.exitForm() },
      )
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> Centered { Text("Couldn't load this form. Check your connection and try again.") }
          else -> AdHocFormBody(
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
private fun AdHocFormBody(
  state: AdHocFormUiState,
  viewModel: AdHocFormViewModel,
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
) {
  val schemaSections = viewModel.sections()
  val summaryLabel = stringResource(R.string.enrollment_tab_summary)
  val tabs = schemaSections + summaryLabel
  val safeIndex = selectedTabIndex.coerceIn(0, tabs.size - 1)
  val isSummaryTab = safeIndex >= schemaSections.size
  val currentSection = schemaSections.getOrNull(safeIndex)

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
        val photoLabel = stringResource(R.string.enrollment_consent_photo_captured)
        LaunchedEffect(state.answers, state.capturedImages, state.version) {
          summary = viewModel.buildSummary(photoLabel)
        }
        AdHocFormSummary(
          sections = summary,
          onEditSection = { title -> onTabSelected(schemaSections.indexOf(title).coerceAtLeast(0)) },
        )
      } else {
        AdHocFormFieldList(
          fields = currentSection?.let(viewModel::fieldsInSection).orEmpty(),
          state = state,
          viewModel = viewModel,
        )
      }
    }

    AdHocFormActionBar(
      showSubmit = isSummaryTab,
      nextLabel = tabs.getOrNull(safeIndex + 1),
      canGoNext = currentSection?.let(viewModel::isSectionReady) ?: false,
      canSubmit = viewModel.isReadyToSubmit(),
      isSubmitting = state.isSubmitting,
      onNext = { onTabSelected(safeIndex + 1) },
      onSubmit = { viewModel.onSubmit() },
    )
  }
}

@Composable
private fun AdHocFormFieldList(
  fields: List<FormFieldSchema>,
  state: AdHocFormUiState,
  viewModel: AdHocFormViewModel,
) {
  val context = LocalContext.current

  // Live capture into app-private storage, one target file per question_code — same pattern as
  // DynamicVisitFormScreen's field list (see that file's doc, and file_paths.xml's
  // "ad_hoc_form_captures" entry this depends on). Reported bug (2026-08-18): onCaptureImage was a
  // no-op here, so tapping "Photo of case paper/discharge summary..." or "Photo of further
  // investigation advised" (Referral Follow-up's two image fields) did nothing at all.
  val photoUriFor = remember { mutableMapOf<String, android.net.Uri>() }
  // CR-Referral-01/02: the raw on-disk path each questionCode's photo was written to (mirrors
  // photoUriFor's key), so viewModel.setCapturedImagePath can hand the data layer a real File
  // path — see AdHocFormUiState.capturedImagePaths's doc for why that's needed alongside the
  // content:// URI.
  val photoFileFor = remember { mutableMapOf<String, File>() }
  var captureTargetCode by remember { mutableStateOf<String?>(null) }

  val takePictureLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture(),
  ) { success ->
    val code = captureTargetCode
    if (success && code != null) {
      viewModel.setCapturedImage(code, photoUriFor.getValue(code).toString())
      viewModel.setCapturedImagePath(code, photoFileFor.getValue(code).absolutePath)
    }
    captureTargetCode = null
  }

  LazyColumn(
    modifier = Modifier.fillMaxWidth().fillMaxSize(),
    contentPadding = PaddingValues(Dimens.ItemSpacing),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
  ) {
    items(fields, key = { it.questionCode }) { field ->
      DynamicFormField(
        field = field,
        answers = state.answers,
        registrationDate = LocalDate.now(),
        beneficiaryRegistrationDate = state.beneficiaryRegistrationDate,
        mediaCompleted = false,
        capturedImageUri = state.capturedImages[field.questionCode],
        loadOptions = { viewModel.optionsFor(field) },
        onSingleAnswer = { value -> viewModel.setAnswer(field.questionCode, value) },
        onMultiAnswer = { values -> viewModel.setMultiAnswer(field.questionCode, values) },
        onPlayMedia = {},
        onCaptureImage = {
          val uri = photoUriFor.getOrPut(field.questionCode) {
            val photoFile = File(File(context.filesDir, "ad-hoc-form"), "${field.questionCode}.jpg")
              .apply { parentFile?.mkdirs() }
            photoFileFor[field.questionCode] = photoFile
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
          }
          captureTargetCode = field.questionCode
          takePictureLauncher.launch(uri)
        },
      )
    }
  }
}

/** Bottom action bar: "Next" (→ next section, or Summary from the last one) everywhere except the
 * Summary tab, which gets "Submit" instead — same shape as every other tabbed dynamic form's own
 * action bar ([org.armman.sakhi.ui.childregistration.DynamicChildRegistrationScreen]'s
 * `FormActionBar`, [org.armman.sakhi.ui.delivery.DeliverySessionScreen]'s
 * `DeliverySessionActionBar`). Tabs themselves stay freely tappable via [AppTabRow] regardless of
 * [canGoNext] — only this button is gated, matching those same screens. */
@Composable
private fun AdHocFormActionBar(
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
 * pill and its answered label/value rows — same look as every other dynamic form's Summary tab
 * (child/mother registration, delivery, the main visit form). */
@Composable
private fun AdHocFormSummary(
  sections: List<SummarySection>,
  onEditSection: (String) -> Unit,
) {
  LazyColumn(
    contentPadding = PaddingValues(Dimens.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxSize(),
  ) {
    item(key = "ad_hoc_form_review_title") {
      Text(
        text = stringResource(R.string.enrollment_summary_title),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
    }
    items(sections, key = { it.title }) { section ->
      AdHocFormReviewCard(title = section.title, onEdit = { onEditSection(section.title) }) {
        section.rows.forEach { row -> AdHocFormReviewRow(label = row.label, value = row.value) }
      }
    }
  }
}

@Composable
private fun AdHocFormReviewCard(
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
private fun AdHocFormReviewRow(label: String, value: String) {
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
