package org.armman.sakhi.ui.forms

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.armman.sakhi.R
import org.armman.sakhi.data.forms.FormCrossFieldRule
import org.armman.sakhi.data.forms.FormFieldSchema

/** `validationJson` rule discriminators — mirrors `FormCrossFieldValidator`'s own constants. */
private const val RULE_LTE = "LTE"
private const val RULE_SUM_EQUALS = "SUM_EQUALS"

/**
 * Turns a violated cross-field rule into a sentence the Sakhi can act on, using the schema's own
 * field labels ("How many children under 5 …" rather than `children_under_five`) so the message
 * names what she sees on screen.
 *
 * A rule referencing a `question_code` absent from the schema falls back to the raw code rather than
 * rendering an empty gap — a backend/schema mismatch should look wrong, not invisible.
 */
@Composable
fun crossFieldMessage(rule: FormCrossFieldRule, labelOf: (String) -> String): String? = when (rule.rule) {
  RULE_LTE -> rule.fields.takeIf { it.size == 2 }?.let { (higher, limit) ->
    stringResource(R.string.form_error_cross_field_lte, labelOf(higher), labelOf(limit))
  }

  RULE_SUM_EQUALS -> rule.equals?.let { total ->
    stringResource(
      R.string.form_error_cross_field_sum_equals,
      labelOf(total),
      rule.fields.joinToString(separator = ", ") { labelOf(it) },
    )
  }

  // Unknown future rule type: FormCrossFieldValidator never reports it as violated, so there is
  // nothing to describe.
  else -> null
}

/** Label lookup over [fields], falling back to the `question_code` for an unknown one. */
fun labelResolver(fields: List<FormFieldSchema>): (String) -> String {
  val labels = fields.associate { it.questionCode to it.label }
  return { code -> labels[code] ?: code }
}
