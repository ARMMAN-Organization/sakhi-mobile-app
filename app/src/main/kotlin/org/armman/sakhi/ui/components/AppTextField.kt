package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG75

/**
 * Style-guide text field: label above an outlined input with optional
 * error subtext below (title + placeholder + subtext states from the design system).
 */
@Composable
fun AppTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  placeholder: String,
  modifier: Modifier = Modifier,
  errorText: String? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  enabled: Boolean = true,
  trailingIcon: (@Composable () -> Unit)? = null,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = NeutralG400,
      modifier = Modifier.padding(bottom = 4.dp),
    )
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = { Text(placeholder, color = NeutralG100) },
      isError = errorText != null,
      singleLine = true,
      enabled = enabled,
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      visualTransformation = visualTransformation,
      trailingIcon = trailingIcon,
      shape = RoundedCornerShape(8.dp),
      colors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = NeutralG75,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        errorBorderColor = MaterialTheme.colorScheme.error,
      ),
      modifier = Modifier.fillMaxWidth(),
    )
    if (errorText != null) {
      Text(
        text = errorText,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
