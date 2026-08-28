package org.armman.sakhi.data.referral

/** In-memory fake, same conventions as every other `Fake*Dao` in this test source set — unit-
 * testable without a real Room database. */
class FakeReferralLinkDao : ReferralLinkDao {
  private val rows = mutableMapOf<String, ReferralLinkEntity>()

  override suspend fun upsert(entity: ReferralLinkEntity) {
    rows[entity.localScheduleUuid] = entity
  }

  override suspend fun getByLocalScheduleUuid(localScheduleUuid: String): ReferralLinkEntity? =
    rows[localScheduleUuid]

  override suspend fun getByLocalScheduleUuids(localScheduleUuids: List<String>): List<ReferralLinkEntity> =
    localScheduleUuids.mapNotNull { rows[it] }
}
