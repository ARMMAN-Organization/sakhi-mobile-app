package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface

/**
 * Small filled pill for a recorded diagnosis/comorbidity label (e.g. "Sickle
 * Cell", "Chronic Diabetes"). Shared by Pre-Visit Health History's Summary
 * card and the Visit Form Summary tab's risk banner (CR-016c) — labels are
 * shown verbatim from `BeneficiaryProfile.diagnoses`, never relabeled.
 */
@Composable
fun ConditionChip(label: String, modifier: Modifier = Modifier) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelLarge,
    color = RiskHigh,
    modifier = modifier
      .background(RiskHighSurface, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  )
}
