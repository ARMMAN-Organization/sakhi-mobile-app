package org.armman.sakhi.ui.visitform.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.armman.sakhi.R
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.visitform.VisitDataState

/**
 * Symptoms sub-tab (Excel Q17, Q18–22): danger-sign checklist, palm/sclera/
 * skin checks, swelling and dehydration. Q17's "No abnormal signs" and Q21's
 * "No swelling" are mutually exclusive against their siblings (ViewModel
 * `toggleMulti`) — this composable only renders state, it does not decide
 * exclusivity.
 */
@Composable
fun SymptomsSubTab(
  state: VisitDataState,
  actions: VisitDataActions,
  modifier: Modifier = Modifier,
) {
  val banner = state.showValidationBanner
  val scrollCoordinator = rememberMissingFieldScrollCoordinator(banner)

  Column(
    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    modifier = modifier.fillMaxWidth(),
  ) {
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_danger_signs),
      arrayRes = R.array.visit_form_danger_sign_options,
      codes = state.dangerSigns,
      onToggle = actions.onDangerSign,
      error = visitRequiredText(state.dangerSigns.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.dangerSigns.isEmpty(), banner, scrollCoordinator),
    )
    VisitDropdownField(
      label = stringResource(R.string.visit_form_vd_palm_nails),
      arrayRes = R.array.visit_form_palm_nails_options,
      code = state.palmNails,
      onSelected = actions.onPalmNails,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.palmNails == null, banner, scrollCoordinator),
    )
    VisitDropdownField(
      label = stringResource(R.string.visit_form_vd_sclera),
      arrayRes = R.array.visit_form_palm_nails_options,
      code = state.sclera,
      onSelected = actions.onSclera,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.sclera == null, banner, scrollCoordinator),
    )
    VisitDropdownField(
      label = stringResource(R.string.visit_form_vd_skin),
      arrayRes = R.array.visit_form_palm_nails_options,
      code = state.skin,
      onSelected = actions.onSkin,
      banner = banner,
      modifier = Modifier.scrollToIfMissing(state.skin == null, banner, scrollCoordinator),
    )
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_swelling),
      arrayRes = R.array.visit_form_swelling_options,
      codes = state.swelling,
      onToggle = actions.onSwelling,
      error = visitRequiredText(state.swelling.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.swelling.isEmpty(), banner, scrollCoordinator),
    )
    VisitCheckboxField(
      label = stringResource(R.string.visit_form_vd_dehydration),
      arrayRes = R.array.visit_form_dehydration_options,
      codes = state.dehydration,
      onToggle = actions.onDehydration,
      error = visitRequiredText(state.dehydration.isEmpty(), banner),
      modifier = Modifier.scrollToIfMissing(state.dehydration.isEmpty(), banner, scrollCoordinator),
    )
    if (banner) VisitValidationBanner()
  }
}
