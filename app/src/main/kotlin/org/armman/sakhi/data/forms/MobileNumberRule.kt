package org.armman.sakhi.data.forms

/**
 * Client-side rule for the dynamic MOTHER_REGISTRATION form's `mobile_number` field: an Indian
 * mobile number is exactly [REQUIRED_DIGITS] digits. The backend schema types this field as a plain
 * `number` with no length/pattern constraint, so the app enforces it — mirroring the static
 * enrollment flow's `PersonalInfoState.MOBILE_DIGITS` so both entry paths validate identically
 * (and reuse the same `R.string.enrollment_error_mobile` copy). If the backend ever ships a real
 * length/pattern constraint on this field, prefer that and retire this stopgap.
 */
object MobileNumberRule {
  const val QUESTION_CODE = "mobile_number"
  const val REQUIRED_DIGITS = 10

  /** True once [value] is a complete, valid mobile number (exactly [REQUIRED_DIGITS] digits). A
   * blank or partially-typed value is NOT complete — callers decide whether blank is acceptable
   * (e.g. an optional field) separately from this completeness check. */
  fun isComplete(value: String?): Boolean =
    value != null && value.length == REQUIRED_DIGITS && value.all(Char::isDigit)
}
