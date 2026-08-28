package org.armman.sakhi.data.referral

import java.time.LocalDate

/** `items[].status` of `GET /sakhi/{sakhiId}/referrals/pending-followup`. Only
 * `"PENDING_FOLLOWUP"` has been observed; anything else (a future status value) maps to
 * [UNKNOWN] rather than failing to deserialize. */
enum class ReferralFollowUpStatus { PENDING_FOLLOWUP, UNKNOWN }

/**
 * One pending referral follow-up as returned by `GET /sakhi/{sakhiId}/referrals/pending-followup`
 * (confirmed against a live mock response, 2026-08-14).
 *
 * [daysRemaining] is preserved exactly as the backend sends it — `0` means due today, and a
 * hypothetical negative value means overdue; callers decide urgency styling, nothing is clamped
 * here.
 */
data class ReferralFollowUp(
  val referralId: String,
  val beneficiaryId: String,
  val beneficiaryName: String,
  val referralDate: LocalDate?,
  val followUpDueDate: LocalDate?,
  val daysRemaining: Int,
  val status: ReferralFollowUpStatus,
)

/**
 * CR-Referral-01: the two choices the Sakhi makes on the visit form's Referral tab. Sent to
 * `POST /referrals` as `referralTypeLookupValueId` — a resolved UUID from the `REFERRAL_TYPE`
 * lookup category, NOT this name as a literal string (backend-confirmed 2026-08-27: the id
 * differs per environment, must always be resolved via [org.armman.sakhi.data.lookup.LookupRepository]
 * at call time, never hardcoded). [name] doubles as the lookup's `valueCode` — confirmed
 * `"STANDARD"`/`"ACCOMPANIED"` live against `GET /lookups/REFERRAL_TYPE`.
 */
enum class ReferralType { STANDARD, ACCOMPANIED }

/**
 * CR-Referral-01: matches `POST /referrals`'s `facilityType` field exactly — a plain string enum,
 * NOT a lookup id (unlike [ReferralType] — backend-confirmed 2026-08-27 these two fields use
 * different representations, don't treat them the same).
 */
enum class FacilityType { PUBLIC, PRIVATE, PHC, RH, DH, OTHER }

/**
 * `Referral.status` — full Prisma enum, verbatim, backend-confirmed 2026-08-27. Only
 * [PENDING_FOLLOWUP] is ever sent by this app on create (required field, no server default;
 * backend confirmed it's the only value any existing endpoint can act on afterwards — sending
 * anything else would create a referral nothing downstream can process). The rest are read from
 * server responses only. [UNKNOWN] is this app's own defensive fallback, not a real backend value
 * — same pattern as [ReferralFollowUpStatus.UNKNOWN].
 */
enum class ReferralStatus { INITIATED, PENDING_FOLLOWUP, COMPLETED, LAPSED, SKIPPED, CANCELLED, UNKNOWN }

/**
 * `ReferralFollowup.followupStatus` — full Prisma enum, verbatim, backend-confirmed 2026-08-27.
 * Only [COMPLETED] (`visitedFacilityFlag: true`) and [INCOMPLETE] (`visitedFacilityFlag: false`)
 * are ever actually produced by any backend code path today — [PENDING] and [LAPSED] exist in the
 * schema but nothing writes them yet; don't build UI expecting to receive those two. [UNKNOWN] is
 * this app's own defensive fallback for a value it doesn't recognize.
 */
enum class ReferralFollowUpOutcomeStatus { PENDING, COMPLETED, INCOMPLETE, LAPSED, UNKNOWN }

/**
 * Everything the Sakhi fills in on the visit form's standalone Referral tab (date/facility/type),
 * captured as a typed bundle rather than loose ViewModel fields so it can be persisted verbatim
 * alongside [org.armman.sakhi.data.rules.RiskGradingResult] in the offline draft payload (see
 * [org.armman.sakhi.data.visitform.VisitFormDraftPayload.referralCapture]) and passed unchanged
 * all the way down to [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator], which is
 * the only place the server visit id / form-submission id needed to actually create the referral
 * are known.
 *
 * Never sent to the backend by itself — only used once the visit's own risk-assessment response
 * confirms at least one condition has `isReferralTrigger == true` (server-authoritative, not the
 * on-device evaluation — see [VisitFormSubmissionCoordinator.maybeCreateReferral]'s doc for why).
 *
 * [referralDate] is required by `POST /referrals` (backend-confirmed 2026-08-27) — the visit
 * form's Referral tab already requires the Sakhi to pick this before the tab is considered filled.
 */
data class ReferralCapture(
  val referralType: ReferralType,
  val facilityName: String,
  val facilityType: FacilityType,
  val referralDate: LocalDate,
)

/** One referral as returned by `POST /referrals` (backend-confirmed live 2026-08-27). Field names
 * mirror the backend's own Prisma model, not this app's earlier (incorrect) guesses —
 * [referralId] instead of a raw `id`, but everything else keeps the wire name's meaning:
 * [sourceSubmissionId] (not `formSubmissionId`), [triggeringConditionIds] (wire name
 * `triggerConditionListJson` — a real JSON array, NOT a JSON-encoded string, despite the `Json`
 * suffix; that suffix refers to the Postgres/Prisma `Json?` column type). */
data class Referral(
  val referralId: String,
  val visitId: String?,
  val sourceSubmissionId: String?,
  val beneficiaryId: String,
  /** The resolved `REFERRAL_TYPE` lookup UUID this referral was created/converted with — kept as
   * the raw id rather than decoded back to a [ReferralType], since the app already knows which
   * type it requested and a reverse lookup isn't needed for anything built so far. */
  val referralTypeLookupValueId: String,
  val status: ReferralStatus,
  val facilityName: String,
  val facilityType: FacilityType,
  val triggeringConditionIds: List<String>,
  val createdAt: String?,
  val validTill: String?,
)

/** One `POST /referrals/{id}/follow-up` submission, as returned by the backend (backend-confirmed
 * live 2026-08-27). [notVisitedReason] is free text, not a lookup code — confirmed by an
 * exact-echo test against the live endpoint. */
data class ReferralFollowUpSubmission(
  val id: String,
  val referralId: String,
  val visitedFacilityFlag: Boolean,
  val notVisitedReason: String?,
  val diagnosis: String?,
  val treatmentGiven: String?,
  val outcome: String?,
  val followupStatus: ReferralFollowUpOutcomeStatus,
)

/** Result of submitting a referral follow-up — bundles the followup record itself with the
 * parent [Referral] as it now stands (its `status` may or may not have moved, depending on
 * [ReferralFollowUpSubmission.visitedFacilityFlag] — see [ReferralRepository.submitFollowUp]'s
 * doc). */
data class ReferralFollowUpResult(
  val followUp: ReferralFollowUpSubmission,
  val referral: Referral,
)

/**
 * Result of a create-referral attempt that distinguishes the one-referral-per-visit guard
 * (idempotent as of backend's #197 fix, 2026-08-27: a duplicate `visitId` create now returns
 * `200` with the EXISTING referral untouched, replacing the earlier `409 Conflict` behavior this
 * app was originally built against) from an actual new creation (`201`). Unexpected failures
 * (network, 5xx, malformed body) surface via [Result.failure] on the wrapping [Result], not
 * through this type.
 */
sealed interface CreateReferralOutcome {
  data class Created(val referral: Referral) : CreateReferralOutcome

  /** The backend's idempotent return for a `visitId` that already had a referral — the SAME
   * referral, not a fresh one, per backend's confirmed fix for issue #197. */
  data class AlreadyExists(val referral: Referral) : CreateReferralOutcome
}

/**
 * Referral-follow-up data boundary. UI depends only on this interface; the backing implementation
 * is bound in DI ([org.armman.sakhi.di.ReferralModule]).
 *
 * NOTE: [getPendingFollowUps] has no screen consuming it yet — the PRD places the referral
 * follow-up list inside Visit Tracker, whose tab structure (Open/Referral vs. per-visit-type) is
 * still an open product decision (tracked separately). The Dashboard's `pendingFollowUpsCount`
 * stat is sourced independently from [org.armman.sakhi.data.dashboard.DashboardSummary], not from
 * this.
 */
interface ReferralRepository {
  suspend fun getPendingFollowUps(): List<ReferralFollowUp>

  /**
   * `POST /referrals` (backend-confirmed live 2026-08-27). One referral per [visitId] is
   * server-enforced and idempotent (issue #197 fix) — a duplicate attempt returns the existing
   * referral as [CreateReferralOutcome.AlreadyExists], never an exception.
   */
  suspend fun createReferral(
    visitId: String?,
    beneficiaryId: String,
    sourceSubmissionId: String?,
    capture: ReferralCapture,
    triggeringConditionIds: List<String>,
  ): Result<CreateReferralOutcome>

  /**
   * `POST /referrals/{referralId}/follow-up` (backend-confirmed live 2026-08-27). When
   * [visitedFacilityFlag] is true, the parent referral's status moves to
   * [ReferralStatus.COMPLETED] in the same call — confirmed live. When false, the parent
   * referral's status stays [ReferralStatus.PENDING_FOLLOWUP] (a Supervisor decides next via the
   * existing decision endpoint, out of this app's scope) — also confirmed live, NOT assumed.
   */
  suspend fun submitFollowUp(
    referralId: String,
    visitedFacilityFlag: Boolean,
    followupDate: LocalDate,
    notVisitedReason: String? = null,
    diagnosis: String? = null,
    treatmentGiven: String? = null,
    outcome: String? = null,
  ): Result<ReferralFollowUpResult>

  /**
   * `PATCH /referrals/{referralId}/convert` (backend-confirmed live 2026-08-27) — Standard to
   * Accompanied only, no request body. `validTill` is unchanged from the original create (no
   * extension, confirmed live). Converting an already-Accompanied referral returns `409
   * Conflict` — surfaced as [Result.failure], since (unlike the create-referral duplicate case)
   * there is no established idempotent-return-existing behavior confirmed for this endpoint.
   */
  suspend fun convertToAccompanied(referralId: String): Result<Referral>
}
