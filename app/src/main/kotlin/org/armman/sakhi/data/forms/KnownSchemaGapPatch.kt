package org.armman.sakhi.data.forms

/**
 * Client-side corrections for confirmed backend `schemaJson` authoring gaps — cases where the
 * live schema's own `visibleWhen` doesn't match the SRS's documented skip logic, verified against
 * a real `GET /forms/:formCode/active-version` payload (not guessed). Kept separate from
 * [FormVisibilityEvaluator] (which must stay a faithful, unopinionated mirror of the backend's own
 * `isVisible` — see that object's own doc) so a temporary workaround never gets mistaken for the
 * general-purpose evaluator itself.
 *
 * Every entry here is a STOPGAP, not a permanent client-side design: the backend's own
 * required-field enforcement is driven by the SAME `visibleWhen` this patch only corrects
 * on-device, so submitting a form with one of these fields patched hidden can still be rejected
 * server-side (a `required: true` field the backend's own schema still considers visible/
 * mandatory) until ARMMAN republishes the schema with the fix baked in. This closes the reported
 * UI symptom (a question that should disappear staying on screen) but does not by itself
 * guarantee the submission succeeds for every affected case — flagged for a backend ask
 * alongside this fix, not instead of it.
 *
 * Each [apply] call only overrides a field's [FormFieldSchema.visibleWhen] when it still matches
 * the EXACT wrong condition confirmed at the time this patch was written — never a blanket
 * "always use my condition" override. That makes this self-retiring: the moment ARMMAN republishes
 * the schema with the real fix, the live condition no longer matches [WRONG_VISIBLE_WHEN] below,
 * this patch stops matching, and [apply] becomes a silent no-op — nobody has to remember to come
 * back and delete it, and it can never fight a real backend fix by re-applying a stale one.
 */
object KnownSchemaGapPatch {

  private const val FORM_CODE_ANC_VISIT = "ANC_VISIT"

  /**
   * ANC_VISIT spec row 48 ("Have you done USG since last visit"): "If yes then continue, if no
   * then go to 52" (Remarks) — i.e. rows 49-51 (date/type/finding of USG) should only render when
   * [USG_DONE_QUESTION_CODE] is "yes". Bug report (2026-09-04): "USG follow-up question remains
   * visible after selecting 'No'".
   *
   * Confirmed via a real `GET /forms/ANC_VISIT/active-version` payload (schema v9,
   * effectiveFrom 2026-08-08) that all three follow-up fields carry the generic
   * `have_you_been_able_to_meet_the_beneficiary_for_the_visit = yes` condition instead — the same
   * gate almost every other field in this form has — so they were never actually wired to Q48 at
   * all and stay visible/mandatory for the whole visit regardless of what she answers there. The
   * exact same "if yes then continue, if no skip ahead" shape already works correctly two
   * questions earlier in this same schema (`if_yes_enter_date_of_latest_anc_visit_at_the_health_facility`,
   * gated on `have_you_visited_health_facility_since_my_last_visit`) — this patch gives the USG
   * fields that identical, already-proven condition instead of the generic one.
   */
  private const val USG_DONE_QUESTION_CODE = "have_you_done_usg_since_last_visit"
  private val USG_FOLLOWUP_QUESTION_CODES = setOf(
    "if_yes_date_of_usg",
    "type_of_usg",
    "usg_finding",
  )
  private val WRONG_VISIBLE_WHEN = FormVisibleWhen(
    field = "have_you_been_able_to_meet_the_beneficiary_for_the_visit",
    value = "yes",
    operator = "eq",
  )
  private val CORRECT_VISIBLE_WHEN = FormVisibleWhen(
    field = USG_DONE_QUESTION_CODE,
    value = "yes",
    operator = "eq",
  )

  fun apply(formCode: String, version: FormVersion): FormVersion {
    if (formCode != FORM_CODE_ANC_VISIT) return version
    // `List.map` always allocates a new list even when every element is unchanged, so a plain
    // `patchedSchema === version.schemaJson` reference check would never be true -- tracking an
    // explicit [changed] flag is what actually makes this a true no-op (same [version] instance
    // back out) once the live condition no longer matches [WRONG_VISIBLE_WHEN].
    var changed = false
    val patchedSchema = version.schemaJson.map { field ->
      if (field.questionCode in USG_FOLLOWUP_QUESTION_CODES && field.visibleWhen == WRONG_VISIBLE_WHEN) {
        changed = true
        field.copy(visibleWhen = CORRECT_VISIBLE_WHEN)
      } else {
        field
      }
    }
    return if (changed) version.copy(schemaJson = patchedSchema) else version
  }
}
