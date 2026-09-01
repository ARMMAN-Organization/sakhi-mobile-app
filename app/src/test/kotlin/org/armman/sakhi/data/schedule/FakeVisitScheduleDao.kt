package org.armman.sakhi.data.schedule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [VisitScheduleDao], following the same conventions as `FakeDynamicFormDraftDao` — the
 * repo has no Robolectric, so Room DAOs are exercised through a fake and the real SQL is validated
 * at compile time by Room's annotation processor plus manual QA on the migration.
 *
 * Query semantics (ordering, status filters, the `serverBeneficiaryId IS NOT NULL` guard on
 * [getUnsynced]) are mirrored exactly, because those are the parts the repository's callers depend
 * on. If a `@Query` changes, change it here too or the tests will pass against behaviour the real
 * DAO no longer has.
 */
class FakeVisitScheduleDao : VisitScheduleDao {

  private val rows = mutableMapOf<String, VisitScheduleEntity>()
  private val activeFlows = mutableMapOf<String, MutableStateFlow<List<VisitScheduleEntity>>>()
  private val unsyncedCountFlow = MutableStateFlow(0)

  override suspend fun upsert(entity: VisitScheduleEntity) {
    rows[entity.localScheduleUuid] = entity
    emitAll()
  }

  override suspend fun upsertAll(entities: List<VisitScheduleEntity>) {
    entities.forEach { rows[it.localScheduleUuid] = it }
    emitAll()
  }

  override suspend fun getByLocalUuid(localScheduleUuid: String): VisitScheduleEntity? =
    rows[localScheduleUuid]

  override suspend fun getForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity> =
    rows.values.filter { it.localBeneficiaryId == localBeneficiaryId }.sortedForDisplay()

  override suspend fun getActiveForBeneficiary(
    localBeneficiaryId: String,
  ): List<VisitScheduleEntity> = activeRows(localBeneficiaryId)

  override fun observeActiveForBeneficiary(
    localBeneficiaryId: String,
  ): Flow<List<VisitScheduleEntity>> = flowFor(localBeneficiaryId)

  override suspend fun getByStatus(status: VisitScheduleStatus): List<VisitScheduleEntity> =
    rows.values.filter { it.status == status }.sortedForDisplay()

  override suspend fun getOpenByType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): List<VisitScheduleEntity> = rows.values
    .filter {
      it.localBeneficiaryId == localBeneficiaryId &&
        it.visitType == visitType &&
        it.status in OPEN_STATUSES
    }
    .sortedForDisplay()

  override suspend fun getUnsynced(): List<VisitScheduleEntity> = rows.values
    .filter { it.serverScheduleId == null && it.serverBeneficiaryId != null }
    .sortedWith(compareBy({ it.localBeneficiaryId }, { it.scheduledDate }))

  override fun observeUnsyncedCount(): Flow<Int> = unsyncedCountFlow

  override suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String) {
    rows[localScheduleUuid]?.let {
      rows[localScheduleUuid] = it.copy(serverScheduleId = serverScheduleId)
      emitAll()
    }
  }

  override suspend fun attachServerBeneficiaryId(
    localBeneficiaryId: String,
    serverBeneficiaryId: String,
  ) {
    rows.values
      .filter { it.localBeneficiaryId == localBeneficiaryId }
      .forEach { rows[it.localScheduleUuid] = it.copy(serverBeneficiaryId = serverBeneficiaryId) }
    emitAll()
  }

  override suspend fun updateStatus(
    localScheduleUuid: String,
    status: VisitScheduleStatus,
    reasonCode: String?,
  ) {
    rows[localScheduleUuid]?.let {
      rows[localScheduleUuid] = it.copy(status = status, reasonCode = reasonCode)
      emitAll()
    }
  }

  override suspend fun lapseOpenAncVisits(localBeneficiaryId: String): Int = mutateWhere(
    { it.localBeneficiaryId == localBeneficiaryId && it.visitType in ANC_FAMILY && it.status in OPEN_STATUSES },
    { it.copy(status = VisitScheduleStatus.CANCELLED, reasonCode = REASON_LAPSED_ON_DELIVERY) },
  )

  override suspend fun lapseAllOpenVisits(localBeneficiaryId: String): Int = mutateWhere(
    { it.localBeneficiaryId == localBeneficiaryId && it.status in OPEN_STATUSES },
    { it.copy(status = VisitScheduleStatus.CANCELLED, reasonCode = REASON_LAPSED_ON_CLOSURE) },
  )

  override suspend fun supersedeOpenVisits(localBeneficiaryId: String): Int = mutateWhere(
    { it.localBeneficiaryId == localBeneficiaryId && it.status in OPEN_STATUSES },
    { it.copy(status = VisitScheduleStatus.SUPERSEDED) },
  )

  override suspend fun countForBeneficiary(localBeneficiaryId: String): Int =
    rows.values.count { it.localBeneficiaryId == localBeneficiaryId }

  override suspend fun countForBeneficiaryAndType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): Int = rows.values.count {
    it.localBeneficiaryId == localBeneficiaryId && it.visitType == visitType
  }

  private fun mutateWhere(
    predicate: (VisitScheduleEntity) -> Boolean,
    transform: (VisitScheduleEntity) -> VisitScheduleEntity,
  ): Int {
    val affected = rows.values.filter(predicate)
    affected.forEach { rows[it.localScheduleUuid] = transform(it) }
    if (affected.isNotEmpty()) emitAll()
    return affected.size
  }

  private fun activeRows(localBeneficiaryId: String) = rows.values
    .filter { it.localBeneficiaryId == localBeneficiaryId && it.status !in RETIRED_STATUSES }
    .sortedForDisplay()

  private fun flowFor(localBeneficiaryId: String) = activeFlows.getOrPut(localBeneficiaryId) {
    MutableStateFlow(activeRows(localBeneficiaryId))
  }

  private fun emitAll() {
    activeFlows.forEach { (beneficiaryId, flow) -> flow.value = activeRows(beneficiaryId) }
    unsyncedCountFlow.value = rows.values.count { it.serverScheduleId == null }
  }

  private fun Iterable<VisitScheduleEntity>.sortedForDisplay() =
    sortedWith(compareBy({ it.scheduledDate }, { it.sequenceNo }))

  private companion object {
    val OPEN_STATUSES = setOf(VisitScheduleStatus.GENERATED, VisitScheduleStatus.OPEN)
    val RETIRED_STATUSES = setOf(VisitScheduleStatus.SUPERSEDED, VisitScheduleStatus.CANCELLED)
    val ANC_FAMILY = setOf(
      VisitCodeType.ANC,
      VisitCodeType.ANC_HR,
      VisitCodeType.ANC_POST_EDD,
    )
  }
}
