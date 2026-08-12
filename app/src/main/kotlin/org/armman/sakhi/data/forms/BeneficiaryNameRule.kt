package org.armman.sakhi.data.forms

/**
 * Client-side rule for the dynamic MOTHER_REGISTRATION form's beneficiary-name fields
 * (`first_name` / `middle_name` / `last_name`): letters and spaces only, no special characters.
 *
 * Source of truth is the form spec (`Revised App Form Final 20.3.26 - Registration_PW_D.csv`,
 * S.No 19, "Should not accept any special characters"). The backend's `formFieldSchema` is
 * `.strict()` and has no `pattern`/`minLength`/`maxLength` key, and the `/beneficiaries` PII
 * contract only bounds the name by length — so neither can express this rule today and the app
 * enforces it, mirroring the static enrollment flow's `PersonalInfoState.nameError()` so both
 * entry paths validate identically (and reuse the same `R.string.enrollment_error_name_chars`
 * copy). Same stopgap shape as [MobileNumberRule]: if the backend ever ships a real pattern
 * constraint on these fields, prefer that and retire this.
 *
 * [Char.isLetter] is Unicode-aware, so Devanagari names pass unchanged; digits, punctuation and
 * symbols do not.
 */
object BeneficiaryNameRule {
  const val FIRST_NAME = "first_name"
  const val MIDDLE_NAME = "middle_name"
  const val LAST_NAME = "last_name"

  /** Child Registration's name is a single combined field (`name_of_the_child`) rather than the
   * split first/middle/last used by the Mother/enrollment forms, so it needs listing explicitly to
   * get the same "no special characters in a name" enforcement (PR #31 review). */
  const val NAME_OF_THE_CHILD = "name_of_the_child"

  /** Child Registration's caregiver-name field — same literal as [org.armman.sakhi.data.motherlink
   * .MotherPrefillQuestionCodes.CAREGIVER_NAME], duplicated here rather than imported to avoid a
   * `data.forms` → `data.motherlink` package cycle (same convention as [NAME_OF_THE_CHILD] above).
   * Was missing from this rule entirely, so unlike every other name field on the form it accepted
   * special characters untouched. */
  const val CAREGIVER_NAME = "caregiver_name_first_name_middle_name_last_name"

  /** The name questions this rule governs. Every other TEXT field (address, RCH number, …) is
   * deliberately left alone — they legitimately contain digits and punctuation.
   *
   * [BeneficiaryNameQuestionCodes.COMBINED_CODES] is included because the MOTHER_REGISTRATION
   * beneficiary-name field has flip-flopped between one combined question and this rule's own
   * split FIRST_NAME/MIDDLE_NAME/LAST_NAME — without listing both shapes, whichever one the live
   * schema ISN'T currently using would silently stop getting character sanitization the moment it
   * came back (see that object's doc for the full timeline). */
  val QUESTION_CODES = setOf(FIRST_NAME, MIDDLE_NAME, LAST_NAME, NAME_OF_THE_CHILD, CAREGIVER_NAME) +
    BeneficiaryNameQuestionCodes.COMBINED_CODES

  fun appliesTo(questionCode: String): Boolean = questionCode in QUESTION_CODES

  /**
   * A character permitted in a name: a letter, whitespace, or a **combining mark**. Indic scripts
   * write vowels after a consonant as combining marks (Devanagari matras like ी in रीमा), which are
   * NOT letters under [Char.isLetter] — filtering on `isLetter()` alone silently deletes them and
   * corrupts the name (रीमा → रम). Marks carry no standalone meaning, so this can never admit a
   * special character; it only keeps the letters the user actually typed intact.
   */
  fun isNameChar(c: Char): Boolean = c.isLetter() || c.isWhitespace() || when (Character.getType(c)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt() -> true
    else -> false
  }

  /**
   * [value] with every disallowed character removed. Applied on each keystroke by the TEXT field
   * renderer, which makes the restriction block-on-input (the spec says "should not accept", not
   * "warn after the fact") and covers pasted text too — the same approach the NUMBER branch
   * already takes with its digits-only filter.
   */
  fun sanitize(value: String): String = value.filter(::isNameChar)

  /**
   * Whether [value] is free of special characters. Blank/null is valid here — an empty name is a
   * *required-field* failure, owned by the required gate, not a character-set failure.
   *
   * With [sanitize] on the input path this can only fail for a value that entered the form some
   * other way (a draft saved before this rule existed, or an answer restored from the backend),
   * so it stays as the submit/next gate rather than being assumed unreachable.
   */
  fun isValid(value: String?): Boolean =
    value == null || value.all(::isNameChar)
}
