package org.armman.sakhi.data.forms

import com.google.gson.Gson
import org.armman.sakhi.data.enrollment.DuplicateAcknowledgement

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
  /**
   * Set only after the Sakhi has confirmed an FR-S-2.5 "is this a new pregnancy?" prompt — it makes
   * the next submission attempt send `acknowledgeDuplicate` plus the link back to the earlier case.
   *
   * Lives here rather than in the Room row so the acknowledgement survives a later manual Data
   * Upload without a schema migration: Gson reads a payload written before this field existed as
   * null, so old drafts stay readable.
   */
  val duplicateAcknowledgement: DuplicateAcknowledgement? = null,
  /**
   * Set when a submission attempt came back with an FR-S-2.5 new-pregnancy prompt that nobody has
   * answered yet — it holds the earlier (completed) case's server id.
   *
   * Needed because a `409` can arrive during a manual Data Upload, long after the Sakhi left the
   * form: without this the prompt would be lost and the draft would sit in DUPLICATE_CONFLICT
   * forever. Home reads it to offer the same confirmation (see `FormUploadRecord`), and it is
   * cleared the moment the answer is recorded either way.
   */
  val pendingNewPregnancyBeneficiaryId: String? = null,
)

internal val dynamicFormDraftGson = Gson()
