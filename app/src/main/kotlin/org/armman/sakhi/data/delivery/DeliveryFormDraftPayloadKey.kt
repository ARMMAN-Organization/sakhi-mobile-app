package org.armman.sakhi.data.delivery

import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers

private const val PAYLOAD_KEY_PREFIX = "delivery_form_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one
 * `DELIVERY_VISIT` draft's encrypted [DeliveryFormDraftPayload] is stored. Shared by
 * [RoomDeliveryFormDraftRepository] (writes) and [DeliveryFormSyncExecutor] (reads) so the two can
 * never drift apart — same pattern as `adHocFormDraftPayloadKey`. */
internal fun deliveryFormDraftPayloadKey(localSubmissionUuid: String): String =
  "$PAYLOAD_KEY_PREFIX$localSubmissionUuid"

/** Everything about a queued delivery submission that's PII/sensitive and therefore kept out of
 * the plain-SQLite Room table — the answers, plus the two dates
 * [DeliveryFormSubmissionCoordinator.submit] needs to build a [org.armman.sakhi.data.schedule
 * .ScheduleContext] on whichever attempt actually succeeds (immediate or a later background
 * retry). Stored as ISO-8601 strings, not `LocalDate`, since [deliveryFormDraftGson] has no
 * `LocalDate` type adapter registered — same reason every other Gson payload in this app that
 * carries a date stores it as a string. */
data class DeliveryFormDraftPayload(
  val answers: FormAnswers,
  val deliveryDateIso: String,
  val deliveryFormFilledOnIso: String,
)

internal val deliveryFormDraftGson = Gson()
