package org.armman.sakhi.data.forms

import android.util.Log
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/** Shared with the other temporary "SakhiSync" diagnostics added this session — see
 * [RemoteFormsRepository]'s own tag doc. */
private const val TAG = "SakhiSync"

/**
 * Tolerant [FormVisibleWhen] deserializer.
 *
 * The backend's own schema (`form-field.dto.ts`'s `formFieldSchema`) defines `visibleWhen` as
 * always a single `{field, operator, value}` object — but a real `GET /forms/ANC_VISIT/
 * active-version` response has been observed shipping it as a JSON **array** for one field
 * instead (a content-authoring bug flagged to the backend team, not a documented alternate
 * shape). A bare reflective Gson parse throws `IllegalStateException: Expected BEGIN_OBJECT but
 * was BEGIN_ARRAY` on that mismatch — and because `schemaJson` deserializes as one array in a
 * single pass, that one bad field takes down the ENTIRE form's schema: the Sakhi can't open the
 * form at all ("We couldn't load this visit's data"), not just lose the one visibility rule.
 *
 * This adapter degrades gracefully instead: anything that isn't a well-formed
 * `{field, operator}` object becomes `null` — the field is simply never hidden, which is the
 * safe direction to fail (an extra visible field is recoverable; a form that won't open at all
 * is not) — and logs so the bad content still gets flagged rather than silently tolerated
 * forever.
 */
class FormVisibleWhenDeserializer : JsonDeserializer<FormVisibleWhen> {
  override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): FormVisibleWhen? {
    if (json.isJsonNull) return null
    if (!json.isJsonObject) {
      Log.w(TAG, "FormVisibleWhenDeserializer: expected a visibleWhen object, got: $json")
      return null
    }

    val obj = json.asJsonObject
    val field = obj.get("field")?.takeIf { it.isJsonPrimitive }?.asString
    val operator = obj.get("operator")?.takeIf { it.isJsonPrimitive }?.asString
    if (field.isNullOrBlank() || operator.isNullOrBlank()) {
      Log.w(TAG, "FormVisibleWhenDeserializer: visibleWhen object missing field/operator: $json")
      return null
    }

    // `value` is `z.any()` on the backend (an `isSet` rule carries none at all) — same lenient
    // "primitive only" handling as before this adapter existed; a non-primitive value (object/
    // array) degrades to null rather than throwing, consistent with the rest of this class.
    val value = obj.get("value")?.takeIf { it.isJsonPrimitive }?.asString

    return FormVisibleWhen(field = field, value = value, operator = operator)
  }
}
