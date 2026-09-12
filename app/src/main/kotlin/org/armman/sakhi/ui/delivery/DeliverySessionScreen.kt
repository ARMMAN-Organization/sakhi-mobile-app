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
import org.armman.sakhi.ui.components.AppTabRow
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.FullScreenLoadingOverlay
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
 * `DELIVERY_VISIT` form screen — CR-042's Delivery Event Session entry step. The form itself stays
 * ONE flat scrollable field list regardless of schema `section` (unlike
 * [DeliveryChildRegistrationScreen]/`DynamicChildRegistrationScreen`, which split into one tab per
 * section) — only a second, client-side **Summary** tab is appended for review + submit, grouping
 * the same answered fields into per-section cards (e.g. "Delivery Details"/"Infant Details", per the
 * active schema's own `section` values) the same way those tabbed screens' Summary tab does. See
 * [DeliverySessionViewModel]'s doc for why this screen stops at the delivery form itself rather than
 * hosting the whole multi-step session.
 *
 * [onNavigateToVisit] opens the freshly-generated PP1 visit (or a same-session NN visit later,
 * once that step exists) the same way [onStartVisit] on Beneficiary Profile does — this screen
 * doesn't build its own visit-form UI, it hands off to the existing one.
 */
@Composable
fun DeliverySessionScreen(
  onBack: () -> Unit,
  onNavigateToVisit: (beneficiaryId: String, localScheduleUuid: String) -> Unit,
  /** CR-042: hands off into [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationScreen] for the
   * SAME [org.armman.sakhi.ui.delivery.DeliverySessionViewModel.sessionUuid] this screen just
   * recorded a delivery under — see [DeliverySessionEvent.Submitted]'s own doc for when this fires
   * instead of [onNavigateToVisit]. Default no-op keeps existing previews/tests that don't care
   * about this working unchanged. */
  onNavigateToChildRegistration: (beneficiaryId: String, sessionUuid: String) -> Unit = { _, _ -> },
  viewModel: DeliverySessionViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var selectedTabIndex by remember { mutableStateOf(0) }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        DeliverySessionEvent.ExitForm -> onBack()
        is DeliverySessionEvent.Submitted -> {
          if (!event.childBeneficiaryIds.isNullOrEmpty()) {
            // At least one live-born child — hand off straight into CHILD_REGISTRATION for it
            // rather than the PP1/back branch below. See DeliveryChildRegistrationViewModel's class
            // doc for how that screen resolves which specific child (of up to three) comes first.
            Toast.makeText(context, "Delivery recorded. Register the child now.", Toast.LENGTH_SHORT).show()
            onNavigateToChildRegistration(viewModel.beneficiaryId, viewModel.sessionUuid)
            return@collect
          }
          Toast.makeText(context, "Delivery recorded", Toast.LENGTH_SHORT).show()
          val pp1 = event.pp1LocalScheduleUuid
          if (pp1 != null) {
            onNavigateToVisit(viewModel.beneficiaryId, pp1)
          } else {
            onBack()
          }
        }
        DeliverySessionEvent.QueuedOffline -> {
          Toast.makeText(context, "Saved. It will upload on your next Data Upload.", Toast.LENGTH_SHORT).show()
          onBack()
        }
        is DeliverySessionEvent.SubmitFailed -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(
        title = stringResource(R.string.beneficiary_profile_delivery_form),
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
          else -> DeliverySessionBody(
            state = state,
            viewModel = viewModel,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
          )
        }
      }
    }
    FullScreenLoadingOverlay(visible = state.isSubmitting)
  }
}

@Composable
private fun DeliverySessionBody(
  state: DeliverySessionUiState,
  viewModel: DeliverySessionViewModel,
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
) {
  val formLabel = stringResource(R.string.beneficiary_profile_delivery_form)
  val summaryLabel = stringResource(R.string.enrollment_tab_summary)
  val tabs = listOf(formLabel, summaryLabel)
  val safeIndex = selectedTabIndex.coerceIn(0, tabs.size - 1)
  val isSummaryTab = safeIndex == 1

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
        DeliverySessionSummary(sections = summary, onEditForm = { onTabSelected(0) })
      } else {
        DeliverySessionFieldList(state = state, viewModel = viewModel)
      }
    }

    DeliverySessionActionBar(
      showSubmit = isSummaryTab,
      nextLabel = summaryLabel,
      canGoNext = viewModel.isReadyToSubmit(),
      canSubmit = viewModel.isReadyToSubmit(),
      isSubmitting = state.isSubmitting,
      onNext = { onTabSelected(1) },
      onSubmit = { viewModel.onSubmit() },
    )
  }
}

/** Pinned bottom action bar: "Next" (→ Summary) on the flat form tab, "Submit" on the Summary tab.
 * Both gate on the SAME [DeliverySessionViewModel.isReadyToSubmit] — unlike
 * [DeliveryChildRegistrationScreen]'s per-section `canGoNext`, this form has only the one input tab,
 * so there is no earlier, narrower gate to check before Summary. Delivery-local copy of that
 * screen's `DeliveryChildFormActionBar` for this screen's two-tab (not N-tab) shape. */
@Composable
private fun DeliverySessionActionBar(
  showSubmit: Boolean,
  nextLabel: String,
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
          text = nextLabel,
          enabled = canGoNext,
          onClick = onNext,
          fullWidth = false,
        )
      }
    }
  }
}

@Composable
private fun DeliverySessionFieldList(state: DeliverySessionUiState, viewModel: DeliverySessionViewModel) {
  val context = LocalContext.current

  // Same live-capture-into-app-private-storage pattern as AdHocFormScreen's field list.
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
    items(viewModel.visibleFields(), key = { it.questionCode }) { field ->
      DynamicFormField(
        field = field,
        answers = state.answers,
        registrationDate = LocalDate.now(),
        beneficiaryRegistrationDate = state.beneficiaryRegistrationDate,
        motherLmpDate = state.motherLmpDate,
        mediaCompleted = false,
        capturedImageUri = state.capturedImages[field.questionCode],
        loadOptions = { viewModel.optionsFor(field) },
        onSingleAnswer = { value -> viewModel.setAnswer(field.questionCode, value) },
        onMultiAnswer = { values -> viewModel.setMultiAnswer(field.questionCode, values) },
        onPlayMedia = {},
        onCaptureImage = {
          val uri = photoUriFor.getOrPut(field.questionCode) {
            val photoFile = File(File(context.filesDir, "delivery-session"), "${field.questionCode}.jpg")
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

/** Summary tab's "Review Details": one white bordered card per schema section with an Edit pill
 * (which always returns to the single form tab — there is no per-section tab to jump back to, see
 * class doc) and its answered label/value rows. Delivery-local copy of the child registration
 * flow's `ChildFormSummary`, sharing [SummarySection]/[SummaryRow] from [DeliverySummaryModels]. */
@Composable
private fun DeliverySessionSummary(
  sections: List<SummarySection>,
  onEditForm: () -> Unit,
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
      DeliverySessionReviewCard(title = section.title, onEdit = onEditForm) {
        section.rows.forEach { row -> DeliverySessionReviewRow(label = row.label, value = row.value) }
      }
    }
  }
}

@Composable
private fun DeliverySessionReviewCard(
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
private fun DeliverySessionReviewRow(label: String, value: String) {
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
