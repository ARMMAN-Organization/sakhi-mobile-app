package org.armman.sakhi.data.visitform

import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers

private const val PAYLOAD_KEY_PREFIX = "visit_form_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one visit draft's
 * encrypted [VisitFormDraftPayload] is stored. Shared by [RoomVisitFormDraftRepository] (writes)
 * and [VisitFormSyncExecutor] (reads) so the two can never drift apart — same pattern as
 * `dynamicFormDraftPayloadKey` for the Mother Registration flow. */
internal fun visitFormDraftPayloadKey(localScheduleUuid: String): String =
  "$PAYLOAD_KEY_PREFIX$localScheduleUuid"

/** Everything about a queued visit submission that's PII/sensitive and therefore kept out of the
 * plain-SQLite Room table — just the answers. Kept as its own small type (rather than storing
 * [FormAnswers] directly) so a future field can be added without a migration, same rationale as
 * `DynamicFormDraftPayload`. */
data class VisitFormDraftPayload(
  val answers: FormAnswers,
)

internal val visitFormDraftGson = Gson()
