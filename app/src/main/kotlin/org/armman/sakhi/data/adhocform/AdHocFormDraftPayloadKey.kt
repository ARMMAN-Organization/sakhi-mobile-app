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
  /** REFERRAL_FOLLOWUP_VISIT only — see [AdHocFormSubmissionCoordinator.submit]'s
   * `capturedImagePaths` doc. Defaults to empty so a payload written before this field existed
   * still deserializes (Gson leaves a missing JSON field at its Kotlin default only when the
   * property has one, which this does). */
  val capturedImagePaths: Map<String, String> = emptyMap(),
)

internal val adHocFormDraftGson = Gson()
