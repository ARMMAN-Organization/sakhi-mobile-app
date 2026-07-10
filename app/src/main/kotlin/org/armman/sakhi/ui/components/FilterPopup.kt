package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow
import androidx.compose.foundation.border

/** One selectable row inside a [FilterPopup]. */
data class FilterOption<T>(
  val value: T,
  val label: @Composable () -> Unit,
)

/**
 * Floating multi-select filter card (Pada/Risk filters): checkbox rows,
 * close icon, Apply + Clear actions. Anchored by the caller.
 */
@Composable
fun <T> FilterPopup(
  options: List<FilterOption<T>>,
  selected: Set<T>,
  onToggle: (T) -> Unit,
  onApply: () -> Unit,
  onClear: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .width(240.dp)
      .softShadow(cornerRadius = Dimens.CardRadius)
      .background(White, RoundedCornerShape(Dimens.CardRadius))
      .padding(Dimens.ItemSpacing),
  ) {
    // Design: X inside an outlined circle, primary tinted.
    IconButton(onClick = onClose, modifier = Modifier.align(Alignment.End).size(28.dp)) {
      Icon(
        painter = painterResource(R.drawable.ic_x),
        contentDescription = stringResource(R.string.filter_close),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .size(24.dp)
          .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
          .padding(5.dp),
      )
    }
    options.forEach { option ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onToggle(option.value) }
          .padding(vertical = Dimens.SmallSpacing),
      ) {
        FilterCheckbox(checked = option.value in selected)
        Column(modifier = Modifier.padding(start = Dimens.ItemSpacing)) { option.label() }
      }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    ) {
      Button(
        onClick = onApply,
        contentPadding = PaddingValues(horizontal = Dimens.ItemSpacing, vertical = 0.dp),
        modifier = Modifier.height(Dimens.ChipHeight),
      ) {
        Text(stringResource(R.string.filter_apply))
      }
      OutlinedButton(
        onClick = onClear,
        contentPadding = PaddingValues(horizontal = Dimens.ItemSpacing, vertical = 0.dp),
        modifier = Modifier.height(Dimens.ChipHeight),
      ) {
        Text(stringResource(R.string.filter_clear), color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}

/** Square style-guide checkbox: primary fill + white check when selected. */
@Composable
private fun FilterCheckbox(checked: Boolean) {
  val shape = RoundedCornerShape(6.dp)
  if (checked) {
    Icon(
      imageVector = Icons.Filled.Check,
      contentDescription = null,
      tint = White,
      modifier = Modifier
        .size(24.dp)
        .background(MaterialTheme.colorScheme.primary, shape)
        .padding(3.dp),
    )
  } else {
    Column(
      modifier = Modifier
        .size(24.dp)
        .border(1.5.dp, NeutralG75, shape),
    ) { /* empty checkbox */ }
  }
}
