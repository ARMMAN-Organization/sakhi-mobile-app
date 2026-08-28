package org.armman.sakhi.data.enrollment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EnrollmentRiskBaselineDao {

  /** First-write-wins — see [EnrollmentRiskBaselineEntity]'s own doc for why `IGNORE`, not
   * `REPLACE`: a baseline is written once, at enrollment, and never rewritten afterwards. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(entity: EnrollmentRiskBaselineEntity)

  @Query("SELECT * FROM enrollment_risk_baselines WHERE localBeneficiaryId = :localBeneficiaryId")
  suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): EnrollmentRiskBaselineEntity?
}
