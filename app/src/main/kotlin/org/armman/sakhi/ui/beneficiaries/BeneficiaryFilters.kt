package org.armman.sakhi.ui.beneficiaries

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.ui.components.FilterOption
import org.armman.sakhi.ui.components.FilterPopup
import org.armman.sakhi.ui.components.RiskBadge
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

/** Fixed bottom filter bar plus the floating Pada/Risk filter popups. */
@Composable
internal fun FilterOverlay(
  state: BeneficiariesUiState,
  viewModel: BeneficiariesViewModel,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    when (state.openFilter) {
      // "All" rows: value null, checked when no specific selection, tap clears.
      OpenFilter.PADA -> FilterPopup(
        options = listOf<FilterOption<String?>>(
          FilterOption(null) {
            Text(
              stringResource(R.string.filter_all_padas),
              style = MaterialTheme.typography.titleMedium,
            )
          },
        ) + state.padaOptions.map { pada ->
          FilterOption<String?>(pada) {
            Text(pada, style = MaterialTheme.typography.titleMedium)
          }
        },
        selected = if (state.selectedPadas.isEmpty()) setOf(null) else state.selectedPadas,
        onToggle = { pada ->
          if (pada == null) viewModel.onClearFilter(OpenFilter.PADA) else viewModel.onTogglePada(pada)
        },
        onApply = viewModel::onApplyFilters,
        onClear = { viewModel.onClearFilter(OpenFilter.PADA) },
        onClose = { viewModel.onOpenFilter(OpenFilter.NONE) },
        modifier = Modifier
          .align(Alignment.Start)
          .padding(start = Dimens.ItemSpacing, bottom = Dimens.SmallSpacing),
      )
      OpenFilter.RISK -> FilterPopup(
        options = listOf<FilterOption<RiskLevel?>>(
          FilterOption(null) {
            Text(
              stringResource(R.string.filter_all_risks),
              style = MaterialTheme.typography.titleMedium,
            )
          },
        ) + RiskLevel.entries.map { risk ->
          FilterOption<RiskLevel?>(risk) { RiskBadge(riskLevel = risk) }
        },
        selected = if (state.selectedRisks.isEmpty()) setOf(null) else state.selectedRisks,
        onToggle = { risk ->
          if (risk == null) viewModel.onClearFilter(OpenFilter.RISK) else viewModel.onToggleRisk(risk)
        },
        onApply = viewModel::onApplyFilters,
        onClear = { viewModel.onClearFilter(OpenFilter.RISK) },
        onClose = { viewModel.onOpenFilter(OpenFilter.NONE) },
        modifier = Modifier
          .align(Alignment.End)
          .padding(end = Dimens.ItemSpacing, bottom = Dimens.SmallSpacing),
      )
      OpenFilter.NONE -> Unit
    }
    Surface(color = White, modifier = Modifier.softShadow()) {
      Column {
        HorizontalDivider(thickness = 1.dp, color = NeutralG50)
        Row(modifier = Modifier.fillMaxWidth().padding(Dimens.ItemSpacing)) {
          FilterBarButton(
            text = stringResource(R.string.beneficiaries_filter_pada),
            leadingIcon = painterResource(R.drawable.ic_location),
            onClick = { viewModel.onOpenFilter(OpenFilter.PADA) },
            modifier = Modifier.weight(1f).padding(end = Dimens.SmallSpacing),
          )
          FilterBarButton(
            text = stringResource(R.string.beneficiaries_filter_risk),
            leadingIcon = painterResource(R.drawable.ic_warning),
            onClick = { viewModel.onOpenFilter(OpenFilter.RISK) },
            modifier = Modifier.weight(1f).padding(start = Dimens.SmallSpacing),
          )
        }
      }
    }
  }
}

/** Outlined pill with leading icon + trailing filter glyph (Pada / Risk). */
@Composable
private fun FilterBarButton(
  text: String,
  leadingIcon: Painter,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Alignment intent: icon + label + filter glyph centered as a group.
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = modifier
      .height(Dimens.ButtonHeight)
      .border(1.dp, NeutralG75, CircleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = Dimens.ItemSpacing),
  ) {
    Icon(
      painter = leadingIcon,
      contentDescription = null,
      tint = NeutralG400,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = text,
      style = MaterialTheme.typography.titleMedium,
      color = NeutralG400,
      modifier = Modifier.padding(horizontal = Dimens.SmallSpacing),
    )
    Icon(
      painter = painterResource(R.drawable.ic_funnel_simple),
      contentDescription = null,
      tint = NeutralG400,
      modifier = Modifier.size(16.dp),
    )
  }
}

/** Outlined month dropdown chip ("Apr 2026 ⌄") for the Journey Complete tab. */
@Composable
internal fun MonthDropdown(
  options: List<YearMonth>,
  selected: YearMonth?,
  onSelected: (YearMonth?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .height(Dimens.ChipHeight)
        .border(1.dp, NeutralG75, CircleShape)
        .clickable { expanded = true }
        .padding(horizontal = Dimens.ItemSpacing),
    ) {
      Text(
        text = selected?.format(monthFormatter)
          ?: stringResource(R.string.beneficiaries_month_all),
        style = MaterialTheme.typography.titleMedium,
        color = NeutralG400,
      )
      Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = NeutralG400,
        modifier = Modifier.padding(start = 4.dp).size(20.dp),
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.beneficiaries_month_all)) },
        onClick = {
          expanded = false
          onSelected(null)
        },
      )
      options.forEach { month ->
        DropdownMenuItem(
          text = { Text(month.format(monthFormatter)) },
          onClick = {
            expanded = false
            onSelected(month)
          },
        )
      }
    }
  }
}
