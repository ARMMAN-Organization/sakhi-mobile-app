package org.armman.sakhi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.SerifTitle
import org.armman.sakhi.ui.theme.White

/**
 * White dashboard card with a bold title, optional trailing label
 * (e.g. current month) and arbitrary content below.
 */
@Composable
fun SummaryCard(
  title: String,
  modifier: Modifier = Modifier,
  trailingLabel: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    shape = RoundedCornerShape(Dimens.CardRadius),
    colors = CardDefaults.cardColors(containerColor = White),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(Dimens.ItemSpacing)) {
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = title,
          style = SerifTitle,
          color = NeutralG400,
        )
        if (trailingLabel != null) {
          Text(
            text = trailingLabel,
            style = MaterialTheme.typography.labelSmall,
            color = NeutralG200,
          )
        }
      }
      content()
    }
  }
}
