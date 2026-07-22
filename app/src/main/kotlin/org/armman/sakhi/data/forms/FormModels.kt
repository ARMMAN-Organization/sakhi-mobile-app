package org.armman.sakhi.data.forms

import com.google.gson.annotations.SerializedName

/** Every `input_type` the backend's form schema is known to emit (CR-018, confirmed against a
 * real `GET /forms/MOTHER_REGISTRATION/active-version` v5 response). [UNKNOWN] is the fallback
 * for any value not in this list — new types the backend adds later shouldn't crash schema
 * parsing or fail the whole form fetch; the renderer just skips fields it doesn't understand yet
 * (and should log/flag that loudly, since it's a real gap, not a silent success). */
enum class FormFieldInputType {
  TEXT,
  TEXT_GEO,
  NUMBER,
  DATE,
  SELECT,
  RADIO,
  MULTISELECT,
  MULTISELECT_DATE,
  MEDIA,
  IMAGE,
  UNKNOWN,
}

fun String.toFormFieldInputType(): FormFieldInputType = when (this) {
  "text" -> FormFieldInputType.TEXT
  "text_geo" -> FormFieldInputType.TEXT_GEO
  "number" -> FormFieldInputType.NUMBER
  "date" -> FormFieldInputType.DATE
  "select" -> FormFieldInputType.SELECT
  "radio" -> FormFieldInputType.RADIO
  "multiselect" -> FormFieldInputType.MULTISELECT
  "multiselect_date" -> FormFieldInputType.MULTISELECT_DATE
  "media" -> FormFieldInputType.MEDIA
  "image" -> FormFieldInputType.IMAGE
  else -> FormFieldInputType.UNKNOWN
}

/** One selectable option within a `select`/`radio`/`multiselect` field, inline in the schema
 * (unlike [FormFieldSchema.lookupCategoryCode] fields, whose options come from
 * [org.armman.sakhi.data.lookup.LookupRepository] instead). */
data class FormFieldOption(
  val label: String,
  @SerializedName("sort_order") val sortOrder: Int,
  @SerializedName("value_code") val valueCode: String,
)

/** `field`/`value`/`operator` condition gating whether a field is shown. Only `"eq"` has been
 * observed; an unrecognized operator is treated as "always visible" — the safer failure mode is
 * showing an extra field, not silently hiding a required one. */
data class FormVisibleWhen(
  val field: String,
  val value: String,
  val operator: String,
)

data class FormNumericRange(
  val min: Double?,
  val max: Double?,
)

/**
 * One field in a form's `schemaJson`. Deliberately a single flat data class (not a sealed
 * hierarchy per `input_type`) because Gson deserializes the whole array in one pass and unknown/
 * absent properties should default to null rather than fail the parse — matches how loosely
 * typed the real backend response actually is (e.g. `select` fields with neither `options` nor
 * `lookup_category_code`, see [org.armman.sakhi.data.forms.GeographyQuestionCodes]).
 */
data class FormFieldSchema(
  val label: String,
  val required: Boolean,
  @SerializedName("input_type") val inputTypeRaw: String,
  @SerializedName("question_code") val questionCode: String,
  @SerializedName("computedFrom") val computedFrom: String? = null,
  @SerializedName("lookup_category_code") val lookupCategoryCode: String? = null,
  val options: List<FormFieldOption>? = null,
  val visibleWhen: FormVisibleWhen? = null,
  val numericRange: FormNumericRange? = null,
  /** `media` fields (e.g. the Arogya Sakhi orientation video, consent audio) that gate forward
   * navigation until playback finishes. */
  val requirePlaybackComplete: Boolean? = null,
  /** `image` fields' capture constraint, e.g. `"LIVE_CAMERA_ONLY"` — no gallery picker allowed
   * for the consent-form photo. */
  val captureMode: String? = null,
  /** Tab this field renders under (e.g. `"Consent"`, `"Personal Info"`, `"Health History"`) —
   * added to the schema (v6) specifically so the dynamic form can restore the old static
   * stepper's tabbed navigation without hardcoding a per-field grouping client-side. A field with
   * no `section` (older cached versions, or a future field the backend forgets to tag) falls back
   * to a catch-all tab client-side rather than silently disappearing — see
   * [DynamicMotherRegistrationViewModel.sectionOf]. */
  val section: String? = null,
) {
  val inputType: FormFieldInputType get() = inputTypeRaw.toFormFieldInputType()
}

/** One cross-field rule from `validationJson`. Matches the backend's `crossFieldRuleSchema`
 * discriminated union exactly: `LTE` compares `fields[0] <= fields[1]`; `SUM_EQUALS` checks
 * `sum(fields) == value-of(equals)`. [equals] is only present for `SUM_EQUALS`. */
data class FormCrossFieldRule(
  val rule: String,
  val fields: List<String>,
  val equals: String? = null,
)

data class FormVersion(
  val id: String,
  val formDefinitionId: String,
  val versionNo: String,
  val schemaJson: List<FormFieldSchema>,
  val validationJson: List<FormCrossFieldRule>,
  val effectiveFrom: String,
  val effectiveTo: String?,
  val status: String,
)

data class FormActiveVersionResponseDto(
  val success: Boolean,
  val message: String?,
  val data: FormVersion?,
)
