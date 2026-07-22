package org.armman.sakhi.data.forms

import com.google.gson.Gson

private const val PAYLOAD_KEY_PREFIX = "dynamic_form_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one draft's
 * encrypted [DynamicFormDraftPayload] is stored. Shared by [RoomDynamicFormDraftRepository]
 * (writes) and [DynamicFormSyncExecutor] (reads) so the two can never drift apart — same pattern
 * as `enrollmentDraftPayloadKey` for the static flow. */
internal fun dynamicFormDraftPayloadKey(localBeneficiaryId: String): String =
  "$PAYLOAD_KEY_PREFIX$localBeneficiaryId"

/** Everything about a draft that's PII/sensitive and therefore kept out of the plain-SQLite Room
 * table — the answers themselves, plus the registration date needed for submission mapping. Kept
 * as its own small type (rather than reusing [FormAnswers] directly) so the fallback registration
 * date has an explicit, versioned home in the stored JSON. */
data class DynamicFormDraftPayload(
  val answers: FormAnswers,
  val registrationDateIso: String,
)

internal val dynamicFormDraftGson = Gson()
