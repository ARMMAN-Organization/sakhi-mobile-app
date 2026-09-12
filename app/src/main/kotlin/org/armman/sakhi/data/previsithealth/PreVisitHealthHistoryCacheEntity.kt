package org.armman.sakhi.data.previsithealth

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Local cache of the last successful `GET /beneficiaries/:beneficiaryId/visit-history` response
 * (FR-S-4.6), keyed by the *server*-assigned beneficiary id — same id
 * [RemotePreVisitHealthHistoryRepository] already resolves before calling the API, so no extra
 * lookup is needed to read this back.
 *
 * [visitsJson] is the raw `List<VisitHistoryEntryDto>` from the response body, Gson-serialized
 * as-is (same "store the server's own shape, don't reinterpret it" convention as
 * [org.armman.sakhi.data.riskassessment.RiskFlagEntity.observedValueJson]) — caching the DTOs
 * rather than the derived [PreVisitHealthHistory] means a future change to the
 * factor-building/risk-classification logic in [RemotePreVisitHealthHistoryRepository] applies to
 * cached data exactly the same as to a fresh fetch, with nothing to keep in sync.
 *
 * One row per beneficiary — a new successful fetch replaces the previous cache entirely via
 * [PreVisitHealthHistoryCacheDao.upsert]. There is no expiry: a stale-but-present cache is still
 * far more useful offline than a hard error screen with no way past it (the gap this cache fixes)
 * — see [RemotePreVisitHealthHistoryRepository.getHealthHistory]'s own doc.
 */
@Entity(tableName = "pre_visit_health_history_cache")
data class PreVisitHealthHistoryCacheEntity(
  @PrimaryKey val serverBeneficiaryId: String,
  val visitsJson: String,
  val cachedAtEpochMillis: Long,
)

@Dao
interface PreVisitHealthHistoryCacheDao {
  @Query("SELECT * FROM pre_visit_health_history_cache WHERE serverBeneficiaryId = :serverBeneficiaryId")
  suspend fun get(serverBeneficiaryId: String): PreVisitHealthHistoryCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: PreVisitHealthHistoryCacheEntity)
}
