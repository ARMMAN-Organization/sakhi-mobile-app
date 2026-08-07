package org.armman.sakhi.data.forms

/**
 * `question_code`(s) for the MOTHER_REGISTRATION beneficiary-name field, matched as a SET rather
 * than one literal — same defensive pattern as [MULTISELECT_DATE_SPELLINGS]/
 * [REGISTRATION_DATE_QUESTION_CODES] elsewhere in this file's neighbourhood, because this exact
 * field has already changed shape more than once, confirmed against real `active-version`
 * responses each time:
 *  1. v1 (pre-CR-018 split): one combined field, [LEGACY_TYPO] (`beneficary_name_...`, with the
 *     backend's own typo in "beneficary").
 *  2. 2026-07-22: split into three separate questions (`first_name`/`middle_name`/`last_name`,
 *     see [DynamicFormSubmissionMapper.QuestionCode]).
 *  3. 2026-08-06: combined again into [CURRENT] (`beneficiary_name`, correctly spelled this
 *     time) — confirmed the same day the `/beneficiaries` `pii.fullName` contract also reverted
 *     to a single joined name (see [org.armman.sakhi.data.enrollment.BeneficiaryPiiDto]'s doc).
 *     The two changes landing together is not a coincidence: the form field and the API field are
 *     meant to agree.
 *
 * [COMBINED_CODES] covers #1 and #3; the split-field case (#2) is handled separately since it's 3
 * codes, not 1 — each caller supplies its own split-field fallback via [combinedNameAnswer], since
 * what to do when NEITHER shape is answered differs (the submission mapper fails the submission;
 * the beneficiary list shows "Unnamed beneficiary").
 *
 * 2026-08-06 lesson: this field's shape drifted TWICE the same day (form schema AND `/beneficiaries`
 * contract) and two independent call sites ([DynamicFormSubmissionMapper] and
 * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource]) had each hardcoded their own
 * copy of the first/middle/last read, so fixing one silently left the other broken (a real
 * "Unnamed beneficiary" bug in production). [combinedNameAnswer] exists so there is only ONE place
 * that knows how to read the combined shape — every caller must go through it rather than
 * re-deriving its own copy.
 */
object BeneficiaryNameQuestionCodes {
  const val CURRENT = "beneficiary_name"
  const val LEGACY_TYPO = "beneficary_name_first_name_middle_name_last_name"
  val COMBINED_CODES: Set<String> = setOf(CURRENT, LEGACY_TYPO)

  /**
   * The first non-blank, trimmed answer among [COMBINED_CODES], or null if neither combined shape
   * is answered — in which case the caller should fall back to its own read of the split
   * first_name/middle_name/last_name questions (see this object's doc for why that fallback isn't
   * unified here too).
   */
  fun combinedNameAnswer(answers: FormAnswers): String? =
    COMBINED_CODES.firstNotNullOfOrNull { code -> answers.valueOf(code)?.trim()?.takeIf { it.isNotBlank() } }
}
