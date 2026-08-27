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

  /**
   * True when [enteredText] already parses above [range]'s `max` — used by the renderer to
   * reject a keystroke before it lands, unlike [isWithinRange] which only reports (but doesn't
   * block) an out-of-range value once it's already in the field.
   *
   * Only `max` is enforced this way. `min` stays a soft, [isWithinRange]-only check: blocking
   * below `min` would break ordinary left-to-right typing (e.g. typing "2" en route to "20" in a
   * 10..20 field would get the leading "2" rejected). Appending digits only grows a value, so no
   * final value that ends up `<= max` ever passes through an intermediate state `> max` — this
   * never blocks a keystroke on the way to a legitimate entry.
   */
  fun exceedsMax(range: FormNumericRange?, enteredText: String): Boolean {
    if (range?.max == null) return false
    val value = enteredText.toDoubleOrNull() ?: return false
    return value > range.max
  }
}
