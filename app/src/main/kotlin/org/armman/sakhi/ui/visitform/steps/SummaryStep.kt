package org.armman.sakhi.ui.visitform.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.visitform.VisitRiskFinding
import org.armman.sakhi.ui.components.ConditionChip
import org.armman.sakhi.ui.components.RiskBadge
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskLow
import org.armman.sakhi.ui.theme.RiskMild
import org.armman.sakhi.ui.theme.RiskModerate
import org.armman.sakhi.ui.theme.SerifTitle

/**
 * Visit Form Summary tab (CR-016c): overall risk banner (comorbidity chips +
 * computed risk level), then read-only Tests/Symptoms review — one outlined,
 * full-width metric card per finding, stacked one per row on every screen
 * size (a two-per-row tablet grid was tried and reverted — the narrower
 * cards wrapped labels/reference-ranges awkwardly). Each card's "Edit" jumps
 * back to the matching Visit Data sub-tab — the source of truth stays
 * [org.armman.sakhi.ui.visitform.VisitDataState]; nothing here is separately
 * editable.
 */
@Composable
fun SummaryStep(
  riskLevel: RiskLevel,
  comorbidities: List<String>,
  testsFindings: List<VisitRiskFinding>,
  symptomsFindings: List<VisitRiskFinding>,
  onEditTests: () -> Unit,
  onEditSymptoms: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = modifier.fillMaxWidth(),
  ) {
    RiskBanner(riskLevel = riskLevel, comorbidities = comorbidities)
    FindingsCard(
      title = stringResource(R.string.visit_form_vs_tests_title),
      findings = testsFindings,
      onEdit = onEditTests,
    )
    FindingsCard(
      title = stringResource(R.string.visit_form_vs_symptoms_title),
      findings = symptomsFindings,
      onEdit = onEditSymptoms,
    )
  }
}

@Composable
private fun RiskBanner(riskLevel: RiskLevel, comorbidities: List<String>) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(NeutralG50)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = stringResource(R.string.visit_form_vs_risk_identified),
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      RiskBadge(riskLevel = riskLevel)
    }
    if (comorbidities.isEmpty()) {
      Text(
        text = stringResource(R.string.visit_form_vs_no_conditions),
        style = MaterialTheme.typography.bodyMedium,
        color = NeutralG200,
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      )
    } else {
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      ) {
        comorbidities.forEach { condition -> ConditionChip(condition) }
      }
    }
  }
}

/** Tests/Symptoms review section: serif header + pencil "Edit" pill, then one full-width metric card per row. */
@Composable
private fun FindingsCard(
  title: String,
  findings: List<VisitRiskFinding>,
  onEdit: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = title,
        style = SerifTitle,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      SecondaryButton(
        text = stringResource(R.string.visit_form_vs_edit),
        onClick = onEdit,
        trailingIcon = painterResource(R.drawable.ic_pencil_simple),
        height = Dimens.SmallButtonHeight,
      )
    }
    findings.forEach { finding -> MetricCard(finding = finding, modifier = Modifier.fillMaxWidth()) }
  }
}

/** One outlined metric card: label + risk badge on top, big value + reference range below. */
@Composable
private fun MetricCard(finding: VisitRiskFinding, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .border(1.dp, NeutralG50, RoundedCornerShape(Dimens.CardRadius))
      .padding(Dimens.ItemSpacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = stringResource(finding.labelRes),
        style = MaterialTheme.typography.titleMedium,
        color = NeutralG400,
        modifier = Modifier.weight(1f),
      )
      RiskBadge(riskLevel = finding.riskLevel)
    }
    if (finding.value.isNotBlank()) {
      // Value and reference range stack (rather than share a Row) so neither
      // ever squeezes/wraps into the other regardless of card width — the
      // two-per-row tablet grid this replaced wrapped the range into
      // several one-character lines when the card got narrow.
      Text(
        text = finding.value,
        style = MaterialTheme.typography.headlineLarge,
        color = finding.riskLevel.valueColor(),
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      )
      if (finding.referenceRange.isNotBlank()) {
        Text(
          text = stringResource(R.string.visit_form_vs_reference_range, finding.referenceRange),
          style = MaterialTheme.typography.bodyMedium,
          color = NeutralG200,
        )
      }
    }
  }
}

/** Same palette as [RiskBadge]'s container colour — keeps the big value legible on white. */
private fun RiskLevel.valueColor() = when (this) {
  RiskLevel.HIGH -> RiskHigh
  RiskLevel.MODERATE -> RiskModerate
  RiskLevel.MILD -> RiskMild
  RiskLevel.LOW -> RiskLow
}
