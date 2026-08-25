package org.armman.sakhi.data.visitform

import com.google.gson.Gson
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.rules.RiskGradingResult

private const val PAYLOAD_KEY_PREFIX = "visit_form_draft_payload_"

/** The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one visit draft's
 * encrypted [VisitFormDraftPayload] is stored. Shared by [RoomVisitFormDraftRepository] (writes)
 * and [VisitFormSyncExecutor] (reads) so the two can never drift apart — same pattern as
 * `dynamicFormDraftPayloadKey` for the Mother Registration flow. */
internal fun visitFormDraftPayloadKey(localScheduleUuid: String): String =
  "$PAYLOAD_KEY_PREFIX$localScheduleUuid"

/** Everything about a queued visit submission that's PII/sensitive and therefore kept out of the
 * plain-SQLite Room table — the answers, and (Phase 5, CR — offline high-risk rule evaluation)
 * the visit-level risk result computed at submit time. Kept as its own small type (rather than
 * storing [FormAnswers] directly) so a future field can be added without a migration, same
 * rationale as `DynamicFormDraftPayload`. */
data class VisitFormDraftPayload(
  val answers: FormAnswers,
  /**
   * The final, authoritative [RiskGradingResult] for this visit — computed once, against the
   * complete final answer set, at submit time
   * ([org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.onFinish]), NOT the possibly-stale
   * live result from the last per-field recompute
   * ([org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.recheckGoRulesRisk]). Null for
   * every form code [org.armman.sakhi.data.rules.GoRulesRiskAdapter] doesn't grade (e.g.
   * `POSTPARTUM_VISIT` — no risk-grading pack exists for it), and for ANC/Infant forms where
   * evaluation itself returned null (no cached rule pack offline yet).
   *
   * Stored here so it survives fully offline, independent of whether/when the network submission
   * ever happens — but **not yet sent to the backend**: `POST /visits` /
   * `POST /forms/:formCode/submissions` has no confirmed field for it yet (see
   * `backend-prompt-risk-grading-ondevice.md` item 4 — awaiting backend's answer). Once that
   * contract is confirmed, wire this into [VisitFormSubmissionCoordinator]'s request body; until
   * then this field is read by nothing but exists so the value isn't lost by the time that answer
   * arrives.
   */
  val riskResult: RiskGradingResult? = null,
)

internal val visitFormDraftGson = Gson()
