package org.armman.sakhi.data.forms

/**
 * How many digits a `number` field will accept while typing.
 *
 * The form spec states input length separately from value range — e.g. row 33 "How many family
 * members in your household?" is *"2 digit, Range 2 to 15"*, and row 34 "How many children under 5
 * years of age" is *"1 digit"* with no range at all. The two do different jobs:
 *
 * - the **digit cap** stops nonsense being typed in the first place (`6666` in a 2-digit field),
 * - the **range** ([FormNumericRangeValidator]) reports a value that is the right length but still
 *   out of bounds (`16` in a 2..15 field), which the Sakhi may legitimately be mid-way through
 *   typing, so it must stay an error message and not a keystroke block.
 *
 * Caps are derived from `numericRange.max` wherever the schema declares one, so a range the backend
 * changes takes effect without an app release. [EXPLICIT_MAX_DIGITS] covers only the fields whose
 * limit the schema cannot express (a digit count with no range).
 *
 * `mobile_number` is deliberately absent — it is a fixed-length rule, not a range, and is capped by
 * [MobileNumberRule] in the renderer.
 */
/** Spec row 34's live `question_code`, confirmed against the active `MOTHER_REGISTRATION` schema. */
const val CHILDREN_UNDER_FIVE_QUESTION_CODE = "how_many_children_under_5_years_of_age_are_in_your_household"

object FormNumericInputRule {

  /**
   * Spec digit limits for fields with no `numericRange` to derive one from, keyed by
   * `question_code`. Add an entry here only when the spec states a digit count and the schema
   * declares no range — a range is always the better source, since it needs no app release.
   *
   * Retire an entry as soon as the schema grows a `numericRange` for that field: [maxDigits] checks
   * this map first, so a stale entry would silently override a range the backend has since fixed.
   */
  private val EXPLICIT_MAX_DIGITS: Map<String, Int> = mapOf(
    // Spec row 34, "How many children under 5 years of age are in your household?" — *1 digit*, no
    // range. The live schema declares no `numericRange` for it (confirmed against the active-version
    // response in api-calls.jsonl), so nothing capped it and the field accepted "11" and longer.
    // Its sibling row 33 (household members) needs no entry — its 2..15 range caps it at 2 digits.
    //
    // This is the app-side stopgap: the durable fix is a `numericRange` on the schema entry, which
    // would cap the input AND give a range message without an app release. Delete this entry then.
    CHILDREN_UNDER_FIVE_QUESTION_CODE to 1,
  )

  /** Highest digit cap this rule will infer from a range. A wider range than this is treated as
   * uncapped rather than silently allowing an implausibly long entry to look intentional. */
  private const val MAX_INFERABLE_DIGITS = 9

  /**
   * Digits [field] accepts, or null when it should stay uncapped (not a `number` field, no explicit
   * entry, and no usable `numericRange.max`).
   */
  fun maxDigits(field: FormFieldSchema): Int? {
    if (field.inputType != FormFieldInputType.NUMBER) return null
    EXPLICIT_MAX_DIGITS[field.questionCode]?.let { return it }
    return digitsIn(field.numericRange?.max)
  }

  /** Digit count of [max] as a whole number, e.g. `15.0` -> 2, `9.0` -> 1, `0.0` -> 1. */
  private fun digitsIn(max: Double?): Int? {
    if (max == null || max < 0) return null
    val whole = max.toLong()
    val digits = whole.toString().length
    return digits.takeIf { it in 1..MAX_INFERABLE_DIGITS }
  }
}
