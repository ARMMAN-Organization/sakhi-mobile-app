package org.armman.sakhi.data.childregistration

import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers

/** DISTINCT prefix from the mother flow's `dynamic_form_draft_payload_` so a child draft's
 * encrypted payload can never collide with a mother draft's, even though both share the same
 * [org.armman.sakhi.data.auth.session.SecureKeyValueStore]. */
private const val PAYLOAD_KEY_PREFIX = "child_form_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one Children
 * Register draft's encrypted [ChildFormDraftPayload] is stored. Shared by
 * [RoomChildFormDraftRepository] (writes) and [ChildFormSyncExecutor] (reads) so the two can never
 * drift apart — same pattern as `dynamicFormDraftPayloadKey` for the mother flow. */
internal fun childFormDraftPayloadKey(localBeneficiaryId: String): String =
  "$PAYLOAD_KEY_PREFIX$localBeneficiaryId"

/** Everything about a Children Register draft that's PII/sensitive and therefore kept out of the
 * plain-SQLite Room table — the answers themselves, plus the registration date needed for
 * submission mapping. Its own type (rather than reusing [FormAnswers] directly) so the fallback
 * registration date has an explicit, versioned home in the stored JSON. */
data class ChildFormDraftPayload(
  val answers: FormAnswers,
  val registrationDateIso: String,
)

internal val childFormDraftGson = Gson()
