package org.armman.sakhi.data.delivery

import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers

private const val PAYLOAD_KEY_PREFIX = "delivery_child_registration_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one delivery-
 * session `CHILD_REGISTRATION` draft's encrypted [DeliveryChildRegistrationDraftPayload] is stored.
 * Shared by [RoomDeliveryChildRegistrationDraftRepository] (writes) and
 * [DeliveryChildRegistrationSyncExecutor] (reads) so the two can never drift apart — same pattern
 * as [deliveryFormDraftPayloadKey]. */
internal fun deliveryChildRegistrationDraftPayloadKey(localSubmissionUuid: String): String =
  "$PAYLOAD_KEY_PREFIX$localSubmissionUuid"

/** Everything about a queued delivery-session `CHILD_REGISTRATION` submission that's PII/sensitive
 * and therefore kept out of the plain-SQLite Room table — just the answers, unlike
 * [DeliveryFormDraftPayload] there is no schedule-context date to carry since this submission has
 * no `ScheduleContext`/PP-NN generation side effect of its own. Stored via
 * [deliveryChildRegistrationDraftGson], a plain [Gson] instance — same reason
 * [deliveryFormDraftGson] is: no [java.time.LocalDate] field here needing a type adapter. */
data class DeliveryChildRegistrationDraftPayload(
  val answers: FormAnswers,
)

internal val deliveryChildRegistrationDraftGson = Gson()
