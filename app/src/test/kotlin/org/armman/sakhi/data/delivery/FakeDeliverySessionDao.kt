package org.armman.sakhi.data.delivery

/**
 * In-memory [DeliverySessionDao], same convention as [org.armman.sakhi.data.schedule.FakeVisitScheduleDao]
 * — no Robolectric in this repo, so Room DAOs are exercised through a fake and the real SQL is
 * validated at compile time by Room's annotation processor plus manual QA on the migration.
 *
 * The `step != 'DONE'` filter on [getActiveForBeneficiary] is mirrored here exactly, in Kotlin
 * rather than SQL — if that filter ever changes on the real `@Query`, change it here too or the
 * tests will pass against behaviour the real DAO no longer has.
 */
class FakeDeliverySessionDao : DeliverySessionDao {

  private val rows = mutableMapOf<String, DeliverySessionEntity>()

  override suspend fun upsert(entity: DeliverySessionEntity) {
    rows[entity.localSessionUuid] = entity
  }

  override suspend fun getBySessionUuid(localSessionUuid: String): DeliverySessionEntity? =
    rows[localSessionUuid]

  override suspend fun getActiveForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity? =
    rows.values.firstOrNull {
      it.localBeneficiaryId == localBeneficiaryId && it.step != DeliverySessionStep.DONE
    }

  override suspend fun getMostRecentForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity? =
    rows.values
      .filter { it.localBeneficiaryId == localBeneficiaryId }
      .maxByOrNull { it.createdAtEpochMillis }

  override suspend fun getAll(): List<DeliverySessionEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
