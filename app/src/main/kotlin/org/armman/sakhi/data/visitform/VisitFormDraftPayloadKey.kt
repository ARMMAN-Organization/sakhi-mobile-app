package org.armman.sakhi.data.visitform

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import java.time.LocalDate
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.lmpchange.LmpChangeCapture
import org.armman.sakhi.data.referral.ReferralCapture
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
  /**
   * CR-Referral-01: whatever the Sakhi filled on the visit form's standalone Referral tab
   * (date/facility/type), captured at submit time alongside [answers]. Null when she left that
   * tab untouched. Stored here so it survives fully offline and is available to
   * [VisitFormSyncExecutor] on a resumed/background sync, exactly like [riskResult] — see
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.maybeCreateReferral] for how
   * it's actually used (only once the visit's server-side risk-assessment response confirms a
   * referral trigger; filling this tab in alone never creates a referral by itself).
   */
  val referralCapture: ReferralCapture? = null,
  /**
   * Task 2 (LMP/Reopen/Referral/Audit task list): whatever the Sakhi filled on ANC_VISIT's own
   * sonography-confirmation branch, captured at submit time alongside [answers] -- same
   * "survives fully offline, read back by a resumed sync" rationale as [referralCapture]. Null
   * when she left that branch untouched. See
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.maybeCreateLmpChangeRequest]
   * for how it's actually used.
   */
  val lmpChangeCapture: LmpChangeCapture? = null,
)

/** Plain [Gson] has no built-in support for [java.time.LocalDate] (no no-arg constructor, so
 * its default reflective adapter silently corrupts it instead of failing loudly — round-tripping
 * a real date through it can come back as year=0/month=0/day=0, which formats to the literal
 * string "0000-00-00" and gets rejected server-side as an invalid date; this is exactly what
 * happened to [org.armman.sakhi.data.referral.ReferralCapture.referralDate] before this adapter
 * was added, since this Gson round-trips [VisitFormDraftPayload] on every online submit (saved
 * to [org.armman.sakhi.data.auth.session.SecureKeyValueStore], then immediately read back by
 * [VisitFormSyncExecutor.loadPayload]) as well as on a resumed background sync. Same fix/pattern
 * already used by `enrollmentRecordGson`/`dynamicFormDraftGson` for the same reason — this
 * instance had simply been missed. */
internal val visitFormDraftGson: Gson = GsonBuilder()
  .registerTypeAdapter(
    LocalDate::class.java,
    JsonSerializer<LocalDate> { src, _, _ -> com.google.gson.JsonPrimitive(src.toString()) },
  )
  .registerTypeAdapter(
    LocalDate::class.java,
    JsonDeserializer { json, _, _ -> LocalDate.parse(json.asString) },
  )
  .create()
