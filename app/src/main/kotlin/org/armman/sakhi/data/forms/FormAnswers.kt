package org.armman.sakhi.data.forms

/**
 * A dynamic-form draft's in-progress answers, keyed by `question_code`. Split into
 * [singleValues] (text/number/date/select/radio — always one value, stored as its raw entered
 * text, a `value_code`, or an ISO date string) and [multiValues] (`multiselect`/
 * `multiselect_date`, which can hold several `value_code`s/dates at once) because the two shapes
 * need different rendering and different validation handling; a single `Map<String, Any?>` would
 * push that type-juggling onto every caller instead of doing it once here.
 */
data class FormAnswers(
  val singleValues: Map<String, String> = emptyMap(),
  val multiValues: Map<String, List<String>> = emptyMap(),
) {
  fun valueOf(questionCode: String): String? = singleValues[questionCode]

  fun multiValueOf(questionCode: String): List<String> = multiValues[questionCode].orEmpty()

  fun withSingleValue(questionCode: String, value: String?): FormAnswers =
    copy(
      singleValues = if (value.isNullOrBlank()) {
        singleValues - questionCode
      } else {
        singleValues + (questionCode to value)
      },
    )

  fun withMultiValue(questionCode: String, values: List<String>): FormAnswers =
    copy(
      multiValues = if (values.isEmpty()) multiValues - questionCode else multiValues + (questionCode to values),
    )
}
