package org.armman.sakhi.data.referral

/**
 * CR-Referral-01: local cache of "does this visit have a referral, and what state is it in" —
 * keyed by [localScheduleUuid] (the same id [org.armman.sakhi.data.beneficiaryprofile.ProfileVisit
 * .id] uses), NOT the server [visitId], so the Beneficiary Profile's visit-card lookup
 * ([org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper]) never has to resolve one from
 * the other.
 *
 * Written once by [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator
 * .maybeCreateReferral] right after `POST /referrals` succeeds (Created or the idempotent
 * AlreadyExists — both write the same row, see that function's doc), and updated in place by the
 * follow-up/convert screens once they exist. This is a read-through cache for UI display only —
 * [org.armman.sakhi.data.referral.ReferralRepository] remains the source of truth for anything
 * that actually mutates a referral; this table just remembers the last-known state so the profile
 * screen doesn't need a network call to decide which button/chip to render.
 *
 * No sync queue semantics here (no `syncStatus`/`retryCount`) — unlike every `*_drafts` table in
 * this database, nothing here is ever uploaded; it is only ever written from a response the
 * backend already accepted.
 */
@androidx.room.Entity(tableName = "referral_links")
data class ReferralLinkEntity(
  @androidx.room.PrimaryKey val localScheduleUuid: String,
  val referralId: String,
  val visitId: String,
  /** [ReferralStatus] stored by enum name, same TEXT-by-name convention as every other enum
   * column in this database. */
  val status: String,
  /** The resolved `REFERRAL_TYPE` lookup UUID this referral currently has — the same raw id
   * [Referral.referralTypeLookupValueId] carries, NOT a [ReferralType] enum name (the create/
   * convert responses only ever return the id, never the human-readable code). Updated in place
   * by the Standard→Accompanied conversion action. Callers that need to know "is this currently
   * Standard or Accompanied" resolve it back via [org.armman.sakhi.data.lookup.LookupRepository]
   * at read time — same "never hardcode, always resolve" convention [ReferralType]'s own doc
   * establishes for the create call. */
  val referralTypeLookupValueId: String,
  /** ISO-8601 timestamp from the server, unchanged across the referral's lifetime (no extension
   * on conversion, backend-confirmed) — null only if the server ever omits it, which has not been
   * observed. */
  val validTill: String?,
  val createdAtEpochMillis: Long,
  /** CR-Referral-02 — added so the follow-up screen's Step 1 (visit data/summary review) can
   * show what was recorded at referral creation without a second network call (no `GET
   * /referrals/{id}` exists to fetch it fresh). Nullable/blank-default so existing rows from
   * before this migration read back as empty strings rather than crashing Room's NOT NULL
   * constraint. */
  val facilityName: String = "",
  val facilityType: String = "",
  /** CR-Referral-01 (2026-09-02) — the `referral_visit_name` answer captured on the referral
   * itself (e.g. "RV1"), so the Referral Follow-up form can autopopulate its own "Referral visit
   * name" question with which referral it's following up on, without a network call. Same
   * blank-default-on-migration convention as [facilityName]/[facilityType] — a row cached before
   * this column existed just reads back empty rather than crashing Room's NOT NULL constraint. */
  val referralVisitName: String = "",
  /**
   * Task 8 (LMP/Reopen/Referral/Audit task list) — mirrors [Referral.decidedByUserId]/
   * [Referral.decidedAt]/[Referral.decisionNotes], written by
   * [org.armman.sakhi.data.referral.RemoteReferralRepository.refreshReferralStatuses] once a
   * Supervisor's REFILL decision is observed on `GET /referrals`. Null on every row until then —
   * including every row cached before this migration, and every row for a referral no Supervisor
   * has acted on yet. Not yet surfaced in any UI (see [Referral.decidedByUserId]'s doc for why:
   * the resubmission-clearing semantics aren't confirmed) — stored now so that UI can be added
   * later without another migration.
   */
  val decidedByUserId: String? = null,
  val decidedAt: String? = null,
  val decisionNotes: String? = null,
  /** CR-Referral-01 (in-visit "Visit name"/"Referral visit name" autopopulation fix) -- the
   * beneficiary this referral was created for, so [ReferralLinkDao.countByBeneficiaryId] can
   * count how many referrals she already has on this device and label the next one "RV{n+1}" in
   * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel]'s in-visit Referral capture step.
   * Blank-default-on-migration, same convention as every other column added to this table after
   * v1 -- a row cached before this migration just doesn't count toward any beneficiary's total
   * (acceptable: no real users on the app yet). */
  val beneficiaryId: String = "",
)
