package org.armman.sakhi.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG75

/**
 * Style-guide search bar: full-pill outline, leading search icon, optional
 * trailing slot (e.g. voice input icon).
 */
@Composable
fun SearchBar(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  trailingIcon: @Composable (() -> Unit)? = null,
) {
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NeutralG400),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    modifier = modifier,
    decorationBox = { innerTextField ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .height(Dimens.SearchBarHeight)
          .border(1.dp, NeutralG75, CircleShape)
          .padding(horizontal = Dimens.ItemSpacing),
      ) {
        Icon(
          imageVector = Icons.Filled.Search,
          contentDescription = null,
          tint = NeutralG400,
          modifier = Modifier.size(20.dp),
        )
        Box(modifier = Modifier.weight(1f).padding(start = Dimens.SmallSpacing)) {
          if (value.isEmpty()) {
            Text(
              text = placeholder,
              style = MaterialTheme.typography.bodyLarge,
              color = NeutralG100,
            )
          }
          innerTextField()
        }
        trailingIcon?.invoke()
      }
    },
  )
}
