package org.armman.sakhi.data.adhocform

import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers

private const val PAYLOAD_KEY_PREFIX = "ad_hoc_form_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one ad-hoc-form
 * draft's encrypted [AdHocFormDraftPayload] is stored. Shared by [RoomAdHocFormDraftRepository]
 * (writes) and [AdHocFormSyncExecutor] (reads) so the two can never drift apart — same pattern as
 * `visitFormDraftPayloadKey`. */
internal fun adHocFormDraftPayloadKey(localFormInstanceUuid: String): String =
  "$PAYLOAD_KEY_PREFIX$localFormInstanceUuid"

/** Everything about a queued ad-hoc submission that's PII/sensitive and therefore kept out of the
 * plain-SQLite Room table — just the answers. Kept as its own small type (rather than storing
 * [FormAnswers] directly) so a future field can be added without a migration, same rationale as
 * `VisitFormDraftPayload`. */
data class AdHocFormDraftPayload(
  val answers: FormAnswers,
)

internal val adHocFormDraftGson = Gson()
