package org.armman.sakhi.ui.forms

import org.armman.sakhi.data.forms.AGE_FROM_DOB_QUESTION_CODES
import org.armman.sakhi.data.forms.FormFieldInputType
import org.armman.sakhi.data.forms.FormFieldSchema

/**
 * Decides whether a dynamic-form field shows the red `*` required marker next to its label.
 *
 * Kept as a pure (non-Compose) object so the rule is unit-testable — the repo has no Compose UI
 * test harness, so display logic lives here and the composables stay a thin rendering layer.
 *
 * The marker is a *user affordance*: it means "you must fill this in before Next/Submit enables".
 * So it is shown only where the Sakhi can actually act:
 * - [FormFieldSchema.required] must be true — the same flag the ViewModel's `fieldsAnsweredAndInRange`
 *   gate uses, so the marker can never disagree with what actually blocks submission.
 * - Computed/derived fields are excluded ([FormFieldSchema.computedFrom], and the DOB-derived age
 *   codes in [AGE_FROM_DOB_QUESTION_CODES] which the schema doesn't yet mark as computed). These
 *   render read-only via `AppReadOnlyField`; marking a box the Sakhi cannot type into would read as
 *   a broken form. The same exclusion list `DynamicFormFieldBody` uses to pick the read-only branch.
 * - `media`/`image` fields are excluded: their label is the text *inside* an action pill
 *   ("Play consent guidelines", "Take photo of consent form"), and an asterisk inside a button
 *   caption reads as part of the action, not as a required marker. Their completion is still gated
 *   by the same submit rules.
 * - [FormFieldInputType.UNKNOWN] is excluded — that branch renders an "app update needed" error
 *   line, not a labelled field.
 *
 * Geography fields are *not* excluded here: whether they render read-only or as a dropdown depends
 * on how many options the backend returns at runtime, so that split is applied at the call site
 * (`GeographyField`) — only the dropdown form receives the marker.
 */
object RequiredFieldMarker {

  /** Input types whose label is rendered inside an action button rather than as a field label. */
  private val ACTION_INPUT_TYPES = setOf(
    FormFieldInputType.MEDIA,
    FormFieldInputType.IMAGE,
  )

  /** True when [field]'s label should be suffixed with the red `*`. */
  fun isShownFor(field: FormFieldSchema): Boolean {
    if (!field.required) return false
    if (field.computedFrom != null) return false
    if (field.questionCode in AGE_FROM_DOB_QUESTION_CODES) return false
    if (field.inputType in ACTION_INPUT_TYPES) return false
    if (field.inputType == FormFieldInputType.UNKNOWN) return false
    return true
  }
}
