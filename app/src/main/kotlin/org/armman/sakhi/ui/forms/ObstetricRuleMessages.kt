package org.armman.sakhi.ui.forms

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.FormObstetricRuleset

/**
 * Message for a broken obstetric-history rule, shown inline under the field the Sakhi should change
 * so she can fix it there and then rather than discovering it at Submit.
 *
 * Reuses the static Health History step's existing (already translated) messages, so both enrollment
 * flows word the same rule identically.
 */
@Composable
fun obstetricMessage(violation: FormObstetricRuleset.Violation): String = when (violation) {
  FormObstetricRuleset.Violation.GRAVIDA_TOTAL ->
    stringResource(R.string.enrollment_hh_error_gravida_total)

  FormObstetricRuleset.Violation.PARA_EXCEEDS_GRAVIDA ->
    stringResource(R.string.enrollment_hh_error_para)

  FormObstetricRuleset.Violation.ABORTIONS_EXCEED_GRAVIDA ->
    stringResource(R.string.enrollment_hh_error_abortions)

  FormObstetricRuleset.Violation.DEAD_CHILDREN_EXCEED_LIVING ->
    stringResource(R.string.enrollment_hh_error_dead)
}
