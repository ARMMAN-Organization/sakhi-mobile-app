package org.armman.sakhi.ui.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.White

/**
 * Summary tab's "Review Details": one white bordered card per section (with a per-section Edit
 * pill that jumps back to that tab), each listing its answered fields as label/value rows. Data
 * comes pre-resolved from [DynamicMotherRegistrationViewModel.buildSummary] (coded values already
 * mapped to labels), so this composable is purely presentational — mirrors the static flow's
 * `SummaryStep` visuals but driven by the dynamic schema.
 */
@Composable
fun DynamicFormSummary(
  sections: List<SummarySection>,
  onEditSection: (String) -> Unit,
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
      ReviewCard(title = section.title, onEdit = { onEditSection(section.title) }) {
        section.rows.forEach { row -> ReviewRow(label = row.label, value = row.value) }
      }
    }
  }
}

/** White bordered card: section header + Edit pill, then label/value rows. */
@Composable
private fun ReviewCard(
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
private fun ReviewRow(label: String, value: String) {
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
