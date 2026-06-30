package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.armman.sakhi.ui.theme.Dimens

/** App-local primary action button following the style guide. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Button(onClick = onClick, modifier = modifier.fillMaxWidth().height(Dimens.ButtonHeight)) { Text(text) }
}
