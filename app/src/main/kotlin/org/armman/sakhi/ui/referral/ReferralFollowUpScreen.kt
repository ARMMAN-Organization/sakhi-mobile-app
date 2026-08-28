package org.armman.sakhi.ui.referral

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.enrollment.components.AppDateField
import org.armman.sakhi.ui.enrollment.components.AppRadioGroup
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.White

/**
 * CR-Referral-01: submits `POST /referrals/{id}/follow-up` for the referral linked to a
 * Beneficiary Profile visit card, and — when eligible — the Standard→Accompanied conversion.
 * Opened only when [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit.referralIncomplete] is
 * true; see [ReferralFollowUpViewModel]'s doc for the eligibility rules.
 */
@Composable
fun ReferralFollowUpScreen(
  onBack: () -> Unit,
  onSubmitted: () -> Unit,
  viewModel: ReferralFollowUpViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  LaunchedEffect(Unit) {
    viewModel.events.collectLatest { event ->
      when (event) {
        ReferralFollowUpEvent.SubmittedSuccessfully -> {
          Toast.makeText(context, "Referral follow-up submitted", Toast.LENGTH_SHORT).show()
          onSubmitted()
        }
        is ReferralFollowUpEvent.SubmitFailed ->
          Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
        ReferralFollowUpEvent.ConvertedSuccessfully ->
          Toast.makeText(context, "Converted to Accompanied referral", Toast.LENGTH_SHORT).show()
        is ReferralFollowUpEvent.ConvertFailed ->
          Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      BackHeader(title = "Referral Follow-up", subtitle = "", onBack = onBack)
      Surface(color = White, modifier = Modifier.fillMaxSize()) {
        when {
          state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
          state.hasError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't load this referral. Please go back and try again.", color = NeutralG400)
          }
          // CR-Referral-01 bug report (2026-08-27): the editable form used to render regardless
          // of the referral's actual status, so re-opening an already-resolved one (e.g. a stale
          // screen instance still on the back stack from an earlier successful submit) hit
          // "Cannot submit a follow-up for a referral with status COMPLETED" from the backend on
          // Submit instead of being caught here, before she wastes time re-typing the form.
          state.status != org.armman.sakhi.data.referral.ReferralStatus.PENDING_FOLLOWUP ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                text = "This referral's follow-up has already been submitted (status: ${state.status.name.lowercase().replace('_', ' ')}). Go back to the beneficiary profile to see the latest status.",
                color = NeutralG400,
                modifier = Modifier.padding(Dimens.ScreenPadding),
              )
            }
          else -> ReferralFollowUpForm(state = state, viewModel = viewModel)
        }
      }
    }
  }
}

@Composable
private fun ReferralFollowUpForm(state: ReferralFollowUpUiState, viewModel: ReferralFollowUpViewModel) {
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(Dimens.ScreenPadding),
  ) {
    if (state.canConvertToAccompanied) {
      Text(
        text = "This is a Standard referral. If the Sakhi is accompanying the beneficiary instead, convert it before submitting the follow-up.",
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG400,
      )
      SecondaryButton(
        text = if (state.isConverting) "Converting…" else "Convert to Accompanied",
        onClick = viewModel::convertToAccompanied,
        enabled = !state.isConverting,
      )
    }

    AppRadioGroup(
      label = "Did the beneficiary visit the facility?",
      options = listOf("Yes", "No"),
      selectedIndex = when (state.visitedFacility) {
        true -> 0
        false -> 1
        null -> null
      },
      onSelected = { index -> viewModel.setVisitedFacility(index == 0) },
      horizontal = true,
    )

    AppDateField(
      label = "Follow-up date",
      placeholder = "",
      value = state.followupDate,
      onDateSelected = viewModel::setFollowupDate,
    )

    if (state.visitedFacility == false) {
      AppTextField(
        label = "Reason not visited",
        placeholder = "",
        value = state.notVisitedReason,
        onValueChange = viewModel::setNotVisitedReason,
        singleLine = false,
        minLines = 3,
      )
    }

    if (state.visitedFacility == true) {
      AppTextField(
        label = "Diagnosis",
        placeholder = "",
        value = state.diagnosis,
        onValueChange = viewModel::setDiagnosis,
        singleLine = false,
        minLines = 2,
      )
      AppTextField(
        label = "Treatment given",
        placeholder = "",
        value = state.treatmentGiven,
        onValueChange = viewModel::setTreatmentGiven,
        singleLine = false,
        minLines = 2,
      )
      AppTextField(
        label = "Outcome",
        placeholder = "",
        value = state.outcome,
        onValueChange = viewModel::setOutcome,
        singleLine = false,
        minLines = 2,
      )
    }

    PrimaryButton(
      text = if (state.isSubmitting) "Submitting…" else "Submit",
      onClick = viewModel::submit,
      enabled = !state.isSubmitting && state.visitedFacility != null && state.followupDate != null,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
