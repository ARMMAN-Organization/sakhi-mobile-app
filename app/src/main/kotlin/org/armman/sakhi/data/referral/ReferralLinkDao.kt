package org.armman.sakhi.data.referral

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReferralLinkDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: ReferralLinkEntity)

  @Query("SELECT * FROM referral_links WHERE localScheduleUuid = :localScheduleUuid")
  suspend fun getByLocalScheduleUuid(localScheduleUuid: String): ReferralLinkEntity?

  /** Bulk lookup for [org.armman.sakhi.data.beneficiaryprofile.ProfileVisitMapper] — one query
   * per profile load instead of one per visit card. */
  @Query("SELECT * FROM referral_links WHERE localScheduleUuid IN (:localScheduleUuids)")
  suspend fun getByLocalScheduleUuids(localScheduleUuids: List<String>): List<ReferralLinkEntity>

  /**
   * CR-Referral-01/02: [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s
   * `submitReferralFollowUp` only ever has the referral's own id (the ad-hoc form route carries
   * `referralId`, not `localScheduleUuid` — unlike the retired bespoke screen's route), so it
   * mirrors the referral's new status into this cache keyed by [ReferralLinkEntity.referralId]
   * rather than [getByLocalScheduleUuid]. [ReferralLinkEntity.referralId] is unique per row in
   * practice (one referral per visit — see [ReferralLinkEntity]'s own doc), so this is equivalent.
   */
  @Query("SELECT * FROM referral_links WHERE referralId = :referralId")
  suspend fun getByReferralId(referralId: String): ReferralLinkEntity?

  /**
   * CR-Referral-01 (in-visit "Visit name"/"Referral visit name" autopopulation fix) — backs
   * [org.armman.sakhi.data.referral.ReferralRepository.countReferralsForBeneficiary], which
   * [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel]'s in-visit Referral capture step
   * uses to auto-number a new referral "RV{n+1}", mirroring the standalone ad-hoc Referral form's
   * existing [org.armman.sakhi.ui.adhocform.AdHocFormViewModel] auto-numbering. Counts rows
   * cached on THIS device only (same on-device-only scope every other query on this DAO has) —
   * a Sakhi's referral history on a freshly re-installed app starts back at RV1, same tradeoff
   * [org.armman.sakhi.data.adhocform.AdHocFormDraftRepository.countByFormCode] already accepts
   * for the ad-hoc form's own auto-numbering.
   */
  @Query("SELECT COUNT(*) FROM referral_links WHERE beneficiaryId = :beneficiaryId")
  suspend fun countByBeneficiaryId(beneficiaryId: String): Int
}
