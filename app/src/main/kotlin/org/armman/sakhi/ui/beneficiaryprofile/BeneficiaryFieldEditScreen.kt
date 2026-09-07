package org.armman.sakhi.ui.beneficiaryprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.MobileNumberRule
import org.armman.sakhi.ui.components.AppTextField
import org.armman.sakhi.ui.components.BackHeader
import org.armman.sakhi.ui.components.ChoiceChip
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.ValidationErrorBanner
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200

/**
 * CR-Registration-Edit Phase 1: the screen behind the Beneficiary Profile's Edit stub. Renders
 * exactly the fields [org.armman.sakhi.data.forms.EditableFieldCodes] allowlists for the
 * beneficiary's form code, using the SAME schema (label/type/options) the registration form
 * itself was built from — see [BeneficiaryFieldEditViewModel.load]'s own doc for why nothing here
 * is hardcoded per field.
 */
@Composable
fun BeneficiaryFieldEditScreen(
  onBack: () -> Unit,
  onSaved: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: BeneficiaryFieldEditViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()

  LaunchedEffect(Unit) {
    viewModel.eventFlow.collectLatest { event ->
      when (event) {
        FieldEditEvent.SaveSucceeded -> onSaved()
      }
    }
  }

  Scaffold(modifier = modifier.fillMaxSize().safeDrawingPadding()) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      BackHeader(
        title = "Edit details",
        subtitle = "",
        onBack = onBack,
      )
      when (val current = state) {
        FieldEditUiState.Loading -> Unit
        is FieldEditUiState.NotEditable -> NotEditableContent(current.reason)
        is FieldEditUiState.Content -> ContentEditor(
          state = current,
          onValueChange = viewModel::onValueChange,
          onSave = viewModel::save,
        )
      }
    }
  }
}

@Composable
private fun NotEditableContent(reason: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(Dimens.ScreenPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(text = reason, style = MaterialTheme.typography.bodyMedium, color = NeutralG200)
  }
}

@Composable
private fun ContentEditor(
  state: FieldEditUiState.Content,
  onValueChange: (fieldCode: String, value: String) -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.weight(1f).padding(horizontal = Dimens.ScreenPadding),
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
      item {
        if (state.errorMessage != null) {
          ValidationErrorBanner(
            errors = listOf(state.errorMessage),
            modifier = Modifier.padding(top = Dimens.ItemSpacing),
          )
        }
      }
      items(state.visibleFields, key = { it.schema.questionCode }) { field ->
        EditableFieldInput(
          field = field,
          value = state.values[field.schema.questionCode].orEmpty(),
          onValueChange = { onValueChange(field.schema.questionCode, it) },
        )
      }
    }
    PrimaryButton(
      text = "Save",
      onClick = onSave,
      loading = state.isSaving,
      modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
    )
  }
}

@Composable
private fun EditableFieldInput(
  field: EditableField,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val schema = field.schema
  when (schema.inputType) {
    FormFieldInputType.SELECT, FormFieldInputType.RADIO -> {
      Column(modifier = modifier.fillMaxWidth()) {
        Text(text = schema.label, style = MaterialTheme.typography.labelLarge, color = NeutralG200)
        Column(
          modifier = Modifier.padding(top = 4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          schema.options.orEmpty().sortedBy { it.sortOrder }.forEach { option ->
            ChoiceChip(
              text = option.label,
              selected = value == option.valueCode,
              onClick = { onValueChange(option.valueCode) },
            )
          }
        }
      }
    }

    FormFieldInputType.NUMBER -> {
      // `mobile_number` is a plain `number` field in the schema with no length constraint of its
      // own, exactly like DynamicFormRenderer's own doc on this — cap it at MobileNumberRule's
      // digit count here too, so the edit screen can't accept an 11+ digit phone number the way
      // the enrollment form already refuses to. Every other NUMBER field on this allowlist keeps
      // passing the raw keystroke through, unchanged.
      val isMobile = schema.questionCode == MobileNumberRule.QUESTION_CODE
      AppTextField(
        value = value,
        onValueChange = { input ->
          onValueChange(
            if (isMobile) input.filter(Char::isDigit).take(MobileNumberRule.REQUIRED_DIGITS) else input,
          )
        },
        label = schema.label,
        placeholder = schema.label,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
      )
    }

    // TEXT/DATE/anything else this Phase-1 allowlist doesn't actually contain (no MEDIA/IMAGE/
    // MULTISELECT field is on the allowlist per the backend contract) — a plain text field is a
    // safe fallback rather than silently hiding the field.
    else -> AppTextField(
      value = value,
      onValueChange = onValueChange,
      label = schema.label,
      placeholder = schema.label,
      modifier = modifier,
    )
  }
}

