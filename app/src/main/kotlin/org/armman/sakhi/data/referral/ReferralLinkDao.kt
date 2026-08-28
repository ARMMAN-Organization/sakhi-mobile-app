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
}
