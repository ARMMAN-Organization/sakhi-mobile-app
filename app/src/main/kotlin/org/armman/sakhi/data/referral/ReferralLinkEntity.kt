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
)
