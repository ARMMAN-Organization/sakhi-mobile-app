package org.armman.sakhi.data.schedule

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** CR-022a cases DB-1 … DB-9 (DB-8 migration is manual QA — no Robolectric in this repo). */
class RoomVisitScheduleRepositoryTest {

  private lateinit var dao: FakeVisitScheduleDao
  private lateinit var repository: RoomVisitScheduleRepository

  @Before
  fun setUp() {
    dao = FakeVisitScheduleDao()
    repository = RoomVisitScheduleRepository(dao)
  }

  // DB-1
  @Test
  fun `insert and read back a schedule row`() = runTest {
    val row = schedule("s1", visitCode = "ANC3", sequenceNo = 3)
    repository.saveGenerated(listOf(row))

    val stored = dao.getByLocalUuid("s1")
    assertNotNull(stored)
    assertEquals("ANC3", stored!!.visitCode)
    assertEquals(3, stored.sequenceNo)
    assertEquals(VisitScheduleStatus.GENERATED, stored.status)
    assertNull(stored.serverScheduleId)
  }

  @Test
  fun `getByLocalScheduleUuid returns the matching row`() = runTest {
    repository.saveGenerated(listOf(schedule("s1", visitCode = "ANC3", sequenceNo = 3)))

    val found = repository.getByLocalScheduleUuid("s1")

    assertNotNull(found)
    assertEquals("ANC3", found!!.visitCode)
  }

  @Test
  fun `getByLocalScheduleUuid returns null for an unknown uuid`() = runTest {
    assertNull(repository.getByLocalScheduleUuid("does-not-exist"))
  }

  // DB-2
  @Test
  fun `localScheduleUuid is the primary key so a re-save replaces rather than duplicates`() =
    runTest {
      repository.saveGenerated(listOf(schedule("s1", visitCode = "ANC1")))
      repository.saveGenerated(listOf(schedule("s1", visitCode = "ANC2")))

      assertEquals(1, repository.getForBeneficiary("ben-1").size)
      assertEquals("ANC2", dao.getByLocalUuid("s1")!!.visitCode)
    }

  // DB-3
  @Test
  fun `query by beneficiary returns only that beneficiary`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", localBeneficiaryId = "ben-1"),
        schedule("s2", localBeneficiaryId = "ben-2"),
        schedule("s3", localBeneficiaryId = "ben-1"),
      ),
    )

    assertEquals(2, repository.getForBeneficiary("ben-1").size)
    assertEquals(1, repository.getForBeneficiary("ben-2").size)
  }

  // DB-4 — retired rows are hidden from the active query the profile screen uses.
  @Test
  fun `active query excludes superseded and cancelled rows`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", status = VisitScheduleStatus.GENERATED),
        schedule("s2", status = VisitScheduleStatus.COMPLETED),
        schedule("s3", status = VisitScheduleStatus.SUPERSEDED),
        schedule("s4", status = VisitScheduleStatus.CANCELLED),
      ),
    )

    val active = repository.getActiveForBeneficiary("ben-1").map { it.localScheduleUuid }
    assertEquals(listOf("s1", "s2"), active.sorted())
  }

  // DB-5
  @Test
  fun `rows sort by scheduled date then sequence`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s3", sequenceNo = 3, scheduledDate = LocalDate.of(2026, 10, 3)),
        schedule("s1", sequenceNo = 1, scheduledDate = LocalDate.of(2026, 8, 4)),
        // Same date as s1 — sequenceNo is the tiebreak.
        schedule("s2", sequenceNo = 2, scheduledDate = LocalDate.of(2026, 8, 4)),
      ),
    )

    assertEquals(
      listOf("s1", "s2", "s3"),
      repository.getForBeneficiary("ben-1").map { it.localScheduleUuid },
    )
  }

  // DB-6
  @Test
  fun `unsynced query returns only rows with no server id`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", serverBeneficiaryId = "srv-ben"),
        schedule("s2", serverBeneficiaryId = "srv-ben", serverScheduleId = "srv-1"),
      ),
    )

    assertEquals(listOf("s1"), repository.getUnsynced().map { it.localScheduleUuid })
  }

  /**
   * The guard that prevents orphan schedules: a row whose beneficiary has not synced yet is not
   * upload-eligible, because the server would reject it with BENEFICIARY_NOT_FOUND on every pass.
   */
  @Test
  fun `unsynced query skips rows whose beneficiary has not synced`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", serverBeneficiaryId = null),
        schedule("s2", serverBeneficiaryId = "srv-ben"),
      ),
    )

    assertEquals(listOf("s2"), repository.getUnsynced().map { it.localScheduleUuid })
  }

  @Test
  fun `attaching a server beneficiary id makes that beneficiary's rows upload eligible`() =
    runTest {
      repository.saveGenerated(
        listOf(schedule("s1"), schedule("s2")),
      )
      assertTrue(repository.getUnsynced().isEmpty())

      repository.attachServerBeneficiaryId("ben-1", "srv-ben")

      assertEquals(2, repository.getUnsynced().size)
    }

  // DB-7
  @Test
  fun `marking synced writes the server id and touches nothing else`() = runTest {
    val row = schedule("s1", visitCode = "ANC4", scheduledDate = LocalDate.of(2026, 11, 1))
    repository.saveGenerated(listOf(row))

    repository.markSynced("s1", "srv-42")

    val stored = dao.getByLocalUuid("s1")!!
    assertEquals("srv-42", stored.serverScheduleId)
    assertEquals(row.visitCode, stored.visitCode)
    assertEquals(row.scheduledDate, stored.scheduledDate)
    assertEquals(row.status, stored.status)
  }

  @Test
  fun `hasSchedule reports whether generation has already run`() = runTest {
    assertFalse(repository.hasSchedule("ben-1"))
    repository.saveGenerated(listOf(schedule("s1")))
    assertTrue(repository.hasSchedule("ben-1"))
  }

  @Test
  fun `saving an empty list is a no-op`() = runTest {
    repository.saveGenerated(emptyList())
    assertFalse(repository.hasSchedule("ben-1"))
  }

  @Test
  fun `observing active rows re-emits when a schedule is generated`() = runTest {
    val flow = repository.observeActiveForBeneficiary("ben-1")
    assertTrue(flow.first().isEmpty())

    repository.saveGenerated(listOf(schedule("s1"), schedule("s2", sequenceNo = 2)))

    assertEquals(2, flow.first().size)
  }

  // ---- FR-S-3.7 lapsing ------------------------------------------------------------------------

  @Test
  fun `delivery lapses open ANC visits and records the reason`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", visitType = VisitCodeType.ANC, status = VisitScheduleStatus.GENERATED),
        schedule("s2", visitType = VisitCodeType.ANC, status = VisitScheduleStatus.OPEN),
        schedule("s3", visitType = VisitCodeType.ANC_HR, status = VisitScheduleStatus.GENERATED),
      ),
    )

    assertEquals(3, repository.lapseOpenAncVisits("ben-1"))

    repository.getForBeneficiary("ben-1").forEach {
      assertEquals(VisitScheduleStatus.CANCELLED, it.status)
      assertEquals(REASON_LAPSED_ON_DELIVERY, it.reasonCode)
    }
  }

  @Test
  fun `delivery does not lapse completed ANC visits`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", status = VisitScheduleStatus.COMPLETED),
        schedule("s2", status = VisitScheduleStatus.GENERATED),
      ),
    )

    assertEquals(1, repository.lapseOpenAncVisits("ben-1"))
    assertEquals(VisitScheduleStatus.COMPLETED, dao.getByLocalUuid("s1")!!.status)
  }

  @Test
  fun `delivery does not lapse non-ANC families`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", visitType = VisitCodeType.ANC),
        schedule("s2", visitType = VisitCodeType.INC),
        schedule("s3", visitType = VisitCodeType.PP),
      ),
    )

    assertEquals(1, repository.lapseOpenAncVisits("ben-1"))
    assertEquals(VisitScheduleStatus.GENERATED, dao.getByLocalUuid("s2")!!.status)
    assertEquals(VisitScheduleStatus.GENERATED, dao.getByLocalUuid("s3")!!.status)
  }

  @Test
  fun `lapsing never deletes a row`() = runTest {
    repository.saveGenerated(List(5) { schedule("s$it", sequenceNo = it + 1) })
    val before = repository.getForBeneficiary("ben-1").size

    repository.lapseOpenAncVisits("ben-1")

    assertEquals(before, repository.getForBeneficiary("ben-1").size)
  }

  // ---- Supersession ----------------------------------------------------------------------------

  @Test
  fun `supersession retires open rows but never completed ones`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s1", status = VisitScheduleStatus.GENERATED),
        schedule("s2", status = VisitScheduleStatus.OPEN),
        schedule("s3", status = VisitScheduleStatus.COMPLETED),
      ),
    )

    assertEquals(2, repository.supersedeOpenVisits("ben-1"))

    assertEquals(VisitScheduleStatus.SUPERSEDED, dao.getByLocalUuid("s1")!!.status)
    assertEquals(VisitScheduleStatus.SUPERSEDED, dao.getByLocalUuid("s2")!!.status)
    assertEquals(VisitScheduleStatus.COMPLETED, dao.getByLocalUuid("s3")!!.status)
  }

  @Test
  fun `supersession never deletes a row`() = runTest {
    repository.saveGenerated(List(10) { schedule("s$it", sequenceNo = it + 1) })

    repository.supersedeOpenVisits("ben-1")

    assertEquals(10, repository.getForBeneficiary("ben-1").size)
  }

  @Test
  fun `superseded rows disappear from the active list but remain in the full list`() = runTest {
    repository.saveGenerated(listOf(schedule("s1"), schedule("s2", sequenceNo = 2)))

    repository.supersedeOpenVisits("ben-1")

    assertTrue(repository.getActiveForBeneficiary("ben-1").isEmpty())
    assertEquals(2, repository.getForBeneficiary("ben-1").size)
  }

  @Test
  fun `open-by-type returns only open rows of the requested family oldest first`() = runTest {
    repository.saveGenerated(
      listOf(
        schedule("s2", visitType = VisitCodeType.ANC, sequenceNo = 2, scheduledDate = LocalDate.of(2026, 9, 3)),
        schedule("s1", visitType = VisitCodeType.ANC, sequenceNo = 1, scheduledDate = LocalDate.of(2026, 8, 4)),
        schedule("s3", visitType = VisitCodeType.ANC, status = VisitScheduleStatus.COMPLETED),
        schedule("s4", visitType = VisitCodeType.INC),
      ),
    )

    assertEquals(
      listOf("s1", "s2"),
      repository.getOpenByType("ben-1", VisitCodeType.ANC).map { it.localScheduleUuid },
    )
  }
}
