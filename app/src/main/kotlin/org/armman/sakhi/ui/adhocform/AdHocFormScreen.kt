package org.armman.sakhi.ui.adhocform

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.forms.DynamicFormField
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.White
import java.io.File
import java.time.LocalDate

/**
 * Generic screen for any of the five schema-driven ad-hoc forms
 * (`REFERRAL_VISIT`/`REFERRAL_FOLLOWUP_VISIT`/`ANC_CLOSURE_VISIT`/`CHILD_CLOSURE_VISIT`/
 * `BENEFICIARY_REOPEN_VISIT`) — one flat scrollable list of every currently-visible field, driven
 * by [AdHocFormViewModel]. Reuses [DynamicFormField], the same per-field renderer the Mother/Child
 * Registration and Visit Form screens already use — there was no need to build a second one.
 *
 * Deliberately simpler than [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen]: no outer/sub
 * tab shell (these forms are short, single-purpose, and opened ad-hoc rather than as one step in a
 * multi-visit flow), and no TIME/REQUIRED_IF_SELECTED field support — deliberately deferred (see
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
      BackHeader(title = viewModel.formCode, subtitle = "", onBack = { viewModel.exitForm() })
      Surface(
        color = White,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.isLoading -> Centered { CircularProgressIndicator() }
          state.hasError -> Centered { Text("Couldn't load this form. Check your connection and try again.") }
          else -> AdHocFormBody(state = state, viewModel = viewModel)
        }
      }
    }
  }
}

@Composable
private fun AdHocFormBody(state: AdHocFormUiState, viewModel: AdHocFormViewModel) {
  val context = LocalContext.current

  // Live capture into app-private storage, one target file per question_code — same pattern as
  // DynamicVisitFormScreen's field list (see that file's doc, and file_paths.xml's
  // "ad_hoc_form_captures" entry this depends on). Reported bug (2026-08-18): onCaptureImage was a
  // no-op here, so tapping "Photo of case paper/discharge summary..." or "Photo of further
  // investigation advised" (Referral Follow-up's two image fields) did nothing at all.
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

  Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      contentPadding = PaddingValues(Dimens.ItemSpacing),
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
      items(viewModel.visibleFields(), key = { it.questionCode }) { field ->
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
              FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            }
            captureTargetCode = field.questionCode
            takePictureLauncher.launch(uri)
          },
        )
      }
    }
    PrimaryButton(
      text = "Submit",
      onClick = { viewModel.onSubmit() },
      enabled = viewModel.isReadyToSubmit() && !state.isSubmitting,
      loading = state.isSubmitting,
      modifier = Modifier.fillMaxWidth().padding(Dimens.ItemSpacing),
    )
  }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
}
