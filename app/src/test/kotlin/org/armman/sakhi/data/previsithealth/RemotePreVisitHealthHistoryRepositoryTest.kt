package org.armman.sakhi.data.previsithealth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.schedule.AnchorType
import org.armman.sakhi.data.schedule.EscalationPolicy
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

/**
 * Unit tests for [RemotePreVisitHealthHistoryRepository] — the vitals-array-to-[RiskFactorTrend]
 * mapping and the local-id/server-id resolution it owns. Spec: docs/test-cases/visit-form.md
 * (PVH-8..14, added alongside CR for `GET /beneficiaries/:beneficiaryId/visit-history`).
 *
 * NOT compiled or run in this authoring environment (no Android SDK / JDK 11) — run
 * `./gradlew detekt :app:testDebugUnitTest` locally before merge.
 */
class RemotePreVisitHealthHistoryRepositoryTest {

  private fun scheduleEntity(
    localBeneficiaryId: String,
    serverBeneficiaryId: String?,
  ) = VisitScheduleEntity(
    localScheduleUuid = "sched-$localBeneficiaryId",
    localBeneficiaryId = localBeneficiaryId,
    serverBeneficiaryId = serverBeneficiaryId,
    visitCode = "ANC2",
    visitType = VisitCodeType.ANC,
    sequenceNo = 2,
    scheduledDate = LocalDate.of(2026, 8, 19),
    windowStartDate = LocalDate.of(2026, 8, 14),
    windowEndDate = LocalDate.of(2026, 8, 24),
    anchorType = AnchorType.LMP,
    anchorDate = LocalDate.of(2026, 7, 1),
    generatedByRuleVersion = "v1",
    escalationPolicy = EscalationPolicy.AFTER_TWO_CONSECUTIVE,
    createdAtEpochMillis = 0L,
  )

  private class FakeVisitScheduleRepository(
    private val schedules: List<VisitScheduleEntity>,
  ) : VisitScheduleRepository {
    override suspend fun saveGenerated(schedules: List<VisitScheduleEntity>) = Unit
    override suspend fun getForBeneficiary(localBeneficiaryId: String) =
      schedules.filter { it.localBeneficiaryId == localBeneficiaryId }
    override suspend fun getByLocalScheduleUuid(localScheduleUuid: String) = null
    override suspend fun getActiveForBeneficiary(localBeneficiaryId: String) = emptyList<VisitScheduleEntity>()
    override fun observeActiveForBeneficiary(localBeneficiaryId: String): Flow<List<VisitScheduleEntity>> =
      MutableStateFlow(emptyList())
    override suspend fun getOpenByType(localBeneficiaryId: String, visitType: VisitCodeType) = emptyList<VisitScheduleEntity>()
    override suspend fun hasSchedule(localBeneficiaryId: String) = schedules.any { it.localBeneficiaryId == localBeneficiaryId }
    override suspend fun hasScheduleOfType(localBeneficiaryId: String, visitType: VisitCodeType) = false
    override suspend fun getUnsynced() = emptyList<VisitScheduleEntity>()
    override fun observeUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
    override suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String) = Unit
    override suspend fun attachServerBeneficiaryId(localBeneficiaryId: String, serverBeneficiaryId: String) = Unit
    override suspend fun updateStatus(localScheduleUuid: String, status: VisitScheduleStatus, reasonCode: String?) = Unit
    override suspend fun lapseOpenAncVisits(localBeneficiaryId: String) = 0
    override suspend fun lapseAllOpenVisits(localBeneficiaryId: String) = 0
    override suspend fun supersedeOpenVisits(localBeneficiaryId: String) = 0
  }

  private class FakeApi(
    private val result: Response<VisitHistoryResponseDto>,
  ) : PreVisitHealthHistoryApi {
    var lastBeneficiaryId: String? = null
    var lastLimit: Int? = null

    override suspend fun getVisitHistory(beneficiaryId: String, limit: Int): Response<VisitHistoryResponseDto> {
      lastBeneficiaryId = beneficiaryId
      lastLimit = limit
      return result
    }
  }

  private fun successResponse(visits: List<VisitHistoryEntryDto>) =
    Response.success(VisitHistoryResponseDto(success = true, message = "OK", data = VisitHistoryDataDto(visits)))

  private fun errorResponse(code: Int) =
    Response.error<VisitHistoryResponseDto>(code, "".toResponseBody("application/json".toMediaType()))

  @Test
  fun `resolves the local beneficiary id to its server id before calling the api`() = runTest {
    val schedules = FakeVisitScheduleRepository(listOf(scheduleEntity("local-1", "server-1")))
    val api = FakeApi(successResponse(emptyList()))
    val repository = RemotePreVisitHealthHistoryRepository(api, schedules)

    repository.getHealthHistory("local-1", "sched-local-1")

    assertEquals("server-1", api.lastBeneficiaryId)
    assertEquals(2, api.lastLimit)
  }

  @Test(expected = BeneficiaryNotSyncedException::class)
  fun `throws BeneficiaryNotSyncedException when no schedule row has a server id yet`() = runTest {
    val schedules = FakeVisitScheduleRepository(listOf(scheduleEntity("local-1", serverBeneficiaryId = null)))
    val api = FakeApi(successResponse(emptyList()))
    val repository = RemotePreVisitHealthHistoryRepository(api, schedules)

    repository.getHealthHistory("local-1", "sched-local-1")
  }

  @Test
  fun `empty visits array maps to an empty history, not an error`() = runTest {
    val schedules = FakeVisitScheduleRepository(listOf(scheduleEntity("local-1", "server-1")))
    val api = FakeApi(successResponse(emptyList()))
    val repository = RemotePreVisitHealthHistoryRepository(api, schedules)

    val history = repository.getHealthHistory("local-1", "sched-local-1")

    assertTrue(history.riskFactors.isEmpty())
    assertTrue(history.nonRiskVitals.isEmpty())
  }

  @Test(expected = NoSuchElementException::class)
  fun `an unsuccessful http response throws`() = runTest {
    val schedules = FakeVisitScheduleRepository(listOf(scheduleEntity("local-1", "server-1")))
    val api = FakeApi(errorResponse(403))
    val repository = RemotePreVisitHealthHistoryRepository(api, schedules)

    repository.getHealthHistory("local-1", "sched-local-1")
  }

  @Test
  fun `high hemoglobin risk visit lands in riskFactors, oldest-to-newest with Last Visit label`() = runTest {
    // Newest-first, as the API returns them: the most recent (Aug) reading is 6.5 g/dl (HIGH,
    // <7.0), the older (July) one is 9.4 g/dl (MODERATE) — a worsening trend.
    val visits = listOf(
      visitEntry("v2", "2026-08-19T10:00:00.000Z", hemoglobin = "6.5"),
      visitEntry("v1", "2026-07-19T10:00:00.000Z", hemoglobin = "9.4"),
    )
    val history = mapVisits(visits)

    val anaemia = history.riskFactors.first { it.factorName == "Anaemia" }
    assertEquals(RiskLevel.HIGH, anaemia.riskLevel)
    assertEquals(2, anaemia.values.size)
    assertEquals("19 July", anaemia.values.first().label)
    assertEquals("9.4", anaemia.values.first().value)
    assertEquals("Last Visit", anaemia.values.last().label)
    assertEquals("6.5", anaemia.values.last().value)
    assertTrue(anaemia.values.last().abnormal)
  }

  @Test
  fun `weight has no threshold function and always lands in nonRiskVitals`() = runTest {
    val visits = listOf(visitEntry("v1", "2026-08-19T10:00:00.000Z", weight = "45.0"))
    val history = mapVisits(visits)

    val weight = history.nonRiskVitals.first { it.factorName == "Weight" }
    assertNull(weight.riskLevel)
    assertFalse(weight.values.first().abnormal)
  }

  @Test
  fun `a vital never captured by any visit is dropped entirely, not shown empty`() = runTest {
    val visits = listOf(visitEntry("v1", "2026-08-19T10:00:00.000Z", hemoglobin = "9.4"))
    val history = mapVisits(visits)

    val allFactorNames = (history.riskFactors + history.nonRiskVitals).map { it.factorName }
    assertFalse(allFactorNames.contains("Blood Sugar"))
    assertFalse(allFactorNames.contains("Temperature"))
  }

  @Test
  fun `a vital missing on one visit but present on another shows a placeholder for that column`() = runTest {
    val visits = listOf(
      visitEntry("v1", "2026-07-19T10:00:00.000Z", hemoglobin = null),
      visitEntry("v2", "2026-08-19T10:00:00.000Z", hemoglobin = "9.4"),
    )
    val history = mapVisits(visits.reversed()) // api returns newest-first

    val anaemia = history.riskFactors.first { it.factorName == "Anaemia" }
    assertEquals("—", anaemia.values.first().value)
    assertFalse(anaemia.values.first().abnormal)
  }

  @Test
  fun `blood pressure classifies from systolic and diastolic together`() = runTest {
    val visits = listOf(visitEntry("v1", "2026-08-19T10:00:00.000Z", systolic = 160, diastolic = 100))
    val history = mapVisits(visits)

    val bp = history.riskFactors.first { it.factorName == "Hypertension" }
    assertEquals(RiskLevel.HIGH, bp.riskLevel)
    assertEquals("160/100", bp.values.first().value)
  }

  // --- helpers ---------------------------------------------------------------------------------

  private fun visitEntry(
    visitCode: String,
    completedAt: String,
    hemoglobin: String? = null,
    weight: String? = null,
    systolic: Int? = null,
    diastolic: Int? = null,
  ) = VisitHistoryEntryDto(
    visitId = "id-$visitCode",
    visitCode = visitCode,
    completedAt = completedAt,
    vitals = VisitVitalsDto(
      hemoglobin = hemoglobin?.let { VitalValueDto(it, "g/dl") },
      bloodPressure = if (systolic != null || diastolic != null) {
        BloodPressureDto(systolic, diastolic, "mmHg")
      } else {
        null
      },
      weight = weight?.let { VitalValueDto(it, "kg") },
      bloodSugar = null,
      temperature = null,
    ),
  )

  /** Drives the same private mapping the repository uses, via its public entry point, with a
   * beneficiary that's always resolvable to a server id. */
  private suspend fun mapVisits(visits: List<VisitHistoryEntryDto>): PreVisitHealthHistory {
    val schedules = FakeVisitScheduleRepository(listOf(scheduleEntity("local-1", "server-1")))
    val api = FakeApi(successResponse(visits))
    return RemotePreVisitHealthHistoryRepository(api, schedules).getHealthHistory("local-1", "sched-local-1")
  }
}
