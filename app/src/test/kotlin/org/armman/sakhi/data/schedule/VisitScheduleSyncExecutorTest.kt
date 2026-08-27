package org.armman.sakhi.data.schedule

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/** CR-022e cases SY-1 … SY-7. */
class VisitScheduleSyncExecutorTest {

  private lateinit var dao: FakeVisitScheduleDao
  private lateinit var repository: RoomVisitScheduleRepository
  private lateinit var api: FakeVisitScheduleApi
  private lateinit var executor: VisitScheduleSyncExecutor

  @Before
  fun setUp() {
    dao = FakeVisitScheduleDao()
    repository = RoomVisitScheduleRepository(dao)
    api = FakeVisitScheduleApi()
    executor = VisitScheduleSyncExecutor(repository, api)
  }

  // SY-1
  @Test
  fun `unsynced rows are batched into one request per beneficiary`() = runTest {
    repository.saveGenerated(
      listOf(
        synced("a1", beneficiary = "ben-1"),
        synced("a2", beneficiary = "ben-1"),
        synced("b1", beneficiary = "ben-2"),
      ),
    )

    executor.run()

    assertEquals(2, api.requests.size)
    assertEquals(setOf(2, 1), api.requests.map { it.schedules.size }.toSet())
  }

  // SY-2
  @Test
  fun `the server id is written back on success`() = runTest {
    repository.saveGenerated(listOf(synced("a1"), synced("a2", sequenceNo = 2)))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertEquals("srv-a1", dao.getByLocalUuid("a1")!!.serverScheduleId)
    assertEquals("srv-a2", dao.getByLocalUuid("a2")!!.serverScheduleId)
    assertTrue(repository.getUnsynced().isEmpty())
  }

  @Test
  fun `writing back the server id does not alter the schedule content`() = runTest {
    val row = synced("a1", scheduledDate = LocalDate.of(2026, 9, 3))
    repository.saveGenerated(listOf(row))

    executor.run()

    val stored = dao.getByLocalUuid("a1")!!
    assertEquals(row.scheduledDate, stored.scheduledDate)
    assertEquals(row.windowStartDate, stored.windowStartDate)
    assertEquals(row.visitCode, stored.visitCode)
  }

  /**
   * SY-3. The four queues run independently, so a schedule can be ready before its beneficiary has
   * reached the server. Deferring is the only safe answer: uploading would fail with an unknown
   * beneficiary on every pass, and failing the row would strand it.
   */
  @Test
  fun `SY-3 rows whose beneficiary has not synced are deferred, not failed`() = runTest {
    repository.saveGenerated(listOf(schedule("a1", localBeneficiaryId = "ben-1")))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertTrue("Nothing should have been sent", api.requests.isEmpty())
    assertNull("The row must survive for a later pass", dao.getByLocalUuid("a1")!!.serverScheduleId)
  }

  @Test
  fun `a deferred row uploads on the next pass once its beneficiary syncs`() = runTest {
    repository.saveGenerated(listOf(schedule("a1", localBeneficiaryId = "ben-1")))
    executor.run()

    repository.attachServerBeneficiaryId("ben-1", "srv-ben-1")
    executor.run()

    assertEquals(1, api.requests.size)
    assertEquals("srv-a1", dao.getByLocalUuid("a1")!!.serverScheduleId)
  }

  // SY-4
  @Test
  fun `a replay is recognised and does not duplicate rows locally`() = runTest {
    repository.saveGenerated(listOf(synced("a1")))
    executor.run()

    // Second pass: nothing is left unsynced, so no request is made at all.
    executor.run()

    assertEquals(1, api.requests.size)
    assertEquals(1, repository.getForBeneficiary("ben-1").size)
  }

  @Test
  fun `a server reporting alreadyExisted still records the returned ids`() = runTest {
    api.alreadyExisted = true
    repository.saveGenerated(listOf(synced("a1")))

    executor.run()

    assertEquals("srv-a1", dao.getByLocalUuid("a1")!!.serverScheduleId)
  }

  // SY-5
  @Test
  fun `a network failure leaves rows unsynced and asks for a retry`() = runTest {
    api.throwIoException = true
    repository.saveGenerated(listOf(synced("a1")))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    assertNull(dao.getByLocalUuid("a1")!!.serverScheduleId)
    assertEquals(1, repository.getUnsynced().size)
  }

  @Test
  fun `a server error asks for a retry`() = runTest {
    api.responseCode = 503
    repository.saveGenerated(listOf(synced("a1")))

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, executor.run())
  }

  // SY-6
  @Test
  fun `a conflict is permanent and is not retried forever`() = runTest {
    api.responseCode = 409
    repository.saveGenerated(listOf(synced("a1")))

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.COMPLETED, outcome)
    assertNull("The row is not marked synced on a conflict", dao.getByLocalUuid("a1")!!.serverScheduleId)
  }

  @Test
  fun `a validation rejection is permanent, not retryable`() = runTest {
    api.responseCode = 400
    repository.saveGenerated(listOf(synced("a1")))

    assertEquals(EnrollmentSyncOutcome.COMPLETED, executor.run())
  }

  @Test
  fun `one beneficiary failing does not block another from syncing`() = runTest {
    api.failFor = "srv-ben-1"
    repository.saveGenerated(
      listOf(
        synced("a1", beneficiary = "ben-1"),
        synced("b1", beneficiary = "ben-2"),
      ),
    )

    val outcome = executor.run()

    assertEquals(EnrollmentSyncOutcome.RETRYABLE_FAILURE, outcome)
    assertNull(dao.getByLocalUuid("a1")!!.serverScheduleId)
    assertEquals("srv-b1", dao.getByLocalUuid("b1")!!.serverScheduleId)
  }

  /** A partial response costs a retry rather than losing track of a visit. */
  @Test
  fun `rows absent from the response stay unsynced`() = runTest {
    api.omitFrom = 1
    repository.saveGenerated(listOf(synced("a1"), synced("a2", sequenceNo = 2)))

    executor.run()

    assertEquals("srv-a1", dao.getByLocalUuid("a1")!!.serverScheduleId)
    assertNull(dao.getByLocalUuid("a2")!!.serverScheduleId)
    assertEquals(1, repository.getUnsynced().size)
  }

  @Test
  fun `an empty queue makes no request`() = runTest {
    assertEquals(EnrollmentSyncOutcome.COMPLETED, executor.run())
    assertTrue(api.requests.isEmpty())
  }

  @Test
  fun `the payload carries the rule version and date-only strings`() = runTest {
    repository.saveGenerated(
      listOf(synced("a1", scheduledDate = LocalDate.of(2026, 9, 3))),
    )

    executor.run()

    val request = api.requests.single()
    // The row's own stamped version, not a fresh rule-source lookup — see
    // VisitScheduleSyncExecutor.uploadBatch's comment for why re-deriving it at sync time would
    // be wrong. schedule()'s default generatedByRuleVersion is "test-v1".
    assertEquals("test-v1", request.generatedByRuleVersionId)
    assertEquals("srv-ben-1", request.beneficiaryId)

    val dto = request.schedules.single()
    assertEquals("2026-09-03", dto.scheduledDate)
    assertTrue("Dates must be date-only, never timestamps", !dto.scheduledDate.contains("T"))
    assertEquals("ANC", dto.visitType)
  }

  @Test
  fun `HR rows carry their trigger's local uuid so the server can resolve the anchor`() = runTest {
    repository.saveGenerated(
      listOf(
        synced(
          "hr1",
          visitType = VisitCodeType.ANC_HR,
          anchorVisitLocalUuid = "anc3",
        ),
      ),
    )

    executor.run()

    assertEquals("anc3", api.requests.single().schedules.single().anchorVisitLocalUuid)
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  /** A row whose beneficiary has already synced, so it is upload-eligible. */
  private fun synced(
    id: String,
    beneficiary: String = "ben-1",
    sequenceNo: Int = 1,
    visitType: VisitCodeType = VisitCodeType.ANC,
    scheduledDate: LocalDate = LocalDate.of(2026, 8, 4),
    anchorVisitLocalUuid: String? = null,
  ) = schedule(
    localScheduleUuid = id,
    localBeneficiaryId = beneficiary,
    serverBeneficiaryId = "srv-$beneficiary",
    sequenceNo = sequenceNo,
    visitType = visitType,
    scheduledDate = scheduledDate,
    anchorVisitLocalUuid = anchorVisitLocalUuid,
  )
}

/** Records what was sent and lets each test choose one failure mode. */
internal class FakeVisitScheduleApi : VisitScheduleApi {

  val requests = mutableListOf<BulkVisitScheduleRequestDto>()

  var responseCode: Int = 201
  var throwIoException: Boolean = false
  var alreadyExisted: Boolean = false

  /** Fail only for this server beneficiary id, to prove one batch does not block another. */
  var failFor: String? = null

  /** Omit this many trailing rows from the response, simulating a partial reply. */
  var omitFrom: Int? = null

  override suspend fun uploadSchedules(
    request: BulkVisitScheduleRequestDto,
  ): Response<BulkVisitScheduleResponseDto> {
    requests += request
    if (throwIoException) throw IOException("offline")

    val shouldFail = failFor == request.beneficiaryId || responseCode !in 200..299
    if (shouldFail) {
      val code = if (failFor == request.beneficiaryId) 503 else responseCode
      return Response.error(code, "".toResponseBody("application/json".toMediaType()))
    }

    val returned = omitFrom?.let { request.schedules.take(it) } ?: request.schedules
    return Response.success(
      BulkVisitScheduleResponseDto(
        success = true,
        data = BulkVisitScheduleResponseDataDto(
          beneficiaryId = request.beneficiaryId,
          created = if (alreadyExisted) 0 else returned.size,
          alreadyExisted = if (alreadyExisted) returned.size else 0,
          schedules = returned.map {
            VisitScheduleUploadResultDto(
              localScheduleUuid = it.localScheduleUuid,
              scheduleId = "srv-${it.localScheduleUuid}",
              status = "GENERATED",
            )
          },
        ),
      ),
    )
  }
}
