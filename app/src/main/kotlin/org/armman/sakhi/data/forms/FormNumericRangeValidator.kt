package org.armman.sakhi.data.forms

/** Validates a `number`-input field's entered text against its [FormFieldSchema.numericRange]. */
object FormNumericRangeValidator {

  /** Null = valid (no range declared, blank/unparseable text — required-ness and format errors
   * are a separate, already-existing concern, not this validator's job). Non-null = a
   * human-irrelevant-to-format-here signal that the value fell outside [min, max]; callers attach
   * their own message. */
  fun isWithinRange(range: FormNumericRange?, enteredText: String): Boolean {
    if (range == null) return true
    val value = enteredText.toDoubleOrNull() ?: return true
    if (range.min != null && value < range.min) return false
    if (range.max != null && value > range.max) return false
    return true
  }
}
