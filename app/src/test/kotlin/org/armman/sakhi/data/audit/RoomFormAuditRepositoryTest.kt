package org.armman.sakhi.data.audit

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Covers [RoomFormAuditRepository] and the [FakeFormAuditEventDao] fake it's exercised against —
 * CR-035's capture-only local audit trail. */
class RoomFormAuditRepositoryTest {

  private lateinit var dao: FakeFormAuditEventDao
  private lateinit var sessionStore: SessionStore
  private lateinit var repository: RoomFormAuditRepository

  private val fixedNowEpochMillis = Instant.parse("2026-08-18T10:15:00Z").toEpochMilli()
  private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(fixedNowEpochMillis), ZoneOffset.UTC)

  private val session = UserSession(
    username = "test.sakhi",
    subjectId = "sakhi-uuid-1",
    roles = listOf("SAKHI"),
    projectId = "project-uuid-1",
    geographyUnitId = null,
    accessToken = "token",
    refreshToken = "refresh",
    accessTokenExpiresAtEpochSeconds = 9_999_999_999L,
  )

  @Before
  fun setUp() {
    dao = FakeFormAuditEventDao()
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    repository = RoomFormAuditRepository(dao, sessionStore, fixedClock)
  }

  @Test
  fun `insert() appends a new row, does not overwrite prior events for the same form`() = runTest {
    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.OPENED,
        timestampEpochMillis = fixedNowEpochMillis,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )
    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.SAVED,
        timestampEpochMillis = fixedNowEpochMillis + 1_000,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )

    val rows = dao.getForForm("schedule-1", "ANC_VISIT")
    assertEquals(2, rows.size)
    assertEquals(FormAuditEventType.OPENED, rows[0].eventType)
    assertEquals(FormAuditEventType.SAVED, rows[1].eventType)
  }

  @Test
  fun `getForForm() returns only events matching subjectId+formCode, in chronological order`() = runTest {
    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.SAVED,
        timestampEpochMillis = fixedNowEpochMillis + 2_000,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )
    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.OPENED,
        timestampEpochMillis = fixedNowEpochMillis,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )
    // Different subjectId, same formCode — must not leak into schedule-1's results.
    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-2",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.OPENED,
        timestampEpochMillis = fixedNowEpochMillis + 500,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )
    // Same subjectId, different formCode — must not leak into ANC_VISIT's results.
    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "PP_VISIT",
        eventType = FormAuditEventType.OPENED,
        timestampEpochMillis = fixedNowEpochMillis + 500,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )

    val rows = dao.getForForm("schedule-1", "ANC_VISIT")
    assertEquals(2, rows.size)
    assertTrue(rows.all { it.subjectId == "schedule-1" && it.formCode == "ANC_VISIT" })
    // Chronological, not insertion order — the OPENED row was inserted second but timestamps
    // first.
    assertEquals(FormAuditEventType.OPENED, rows[0].eventType)
    assertEquals(FormAuditEventType.SAVED, rows[1].eventType)
  }

  @Test
  fun `observeForForm() emits an updated list after each insert`() = runTest {
    val firstSnapshot = dao.observeForForm("schedule-1", "ANC_VISIT").first()
    assertEquals(0, firstSnapshot.size)

    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.OPENED,
        timestampEpochMillis = fixedNowEpochMillis,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )
    val secondSnapshot = dao.observeForForm("schedule-1", "ANC_VISIT").first()
    assertEquals(listOf(FormAuditEventType.OPENED), secondSnapshot.map { it.eventType })

    dao.insert(
      FormAuditEventEntity(
        subjectId = "schedule-1",
        formCode = "ANC_VISIT",
        eventType = FormAuditEventType.SAVED,
        timestampEpochMillis = fixedNowEpochMillis + 1_000,
        performedBySakhiId = "sakhi-uuid-1",
      ),
    )
    val thirdSnapshot = dao.observeForForm("schedule-1", "ANC_VISIT").first()
    assertEquals(
      listOf(FormAuditEventType.OPENED, FormAuditEventType.SAVED),
      thirdSnapshot.map { it.eventType },
    )
  }

  @Test
  fun `recordOpened() writes eventType=OPENED with correct subjectId, formCode, performedBySakhiId, timestamp`() = runTest {
    sessionStore.saveSession(session)

    repository.recordOpened("schedule-1", "ANC_VISIT")

    val event = dao.getForForm("schedule-1", "ANC_VISIT").single()
    assertEquals("schedule-1", event.subjectId)
    assertEquals("ANC_VISIT", event.formCode)
    assertEquals(FormAuditEventType.OPENED, event.eventType)
    assertEquals("sakhi-uuid-1", event.performedBySakhiId)
    assertEquals(fixedNowEpochMillis, event.timestampEpochMillis)
  }

  @Test
  fun `recordSaved() writes eventType=SAVED with the same fields`() = runTest {
    sessionStore.saveSession(session)

    repository.recordSaved("local-beneficiary-1", "MOTHER_REGISTRATION")

    val event = dao.getForForm("local-beneficiary-1", "MOTHER_REGISTRATION").single()
    assertEquals("local-beneficiary-1", event.subjectId)
    assertEquals("MOTHER_REGISTRATION", event.formCode)
    assertEquals(FormAuditEventType.SAVED, event.eventType)
    assertEquals("sakhi-uuid-1", event.performedBySakhiId)
    assertEquals(fixedNowEpochMillis, event.timestampEpochMillis)
  }

  @Test
  fun `recordSubmitted() writes eventType=SUBMITTED with the same fields`() = runTest {
    sessionStore.saveSession(session)

    repository.recordSubmitted("schedule-1", "ANC_VISIT")

    val event = dao.getForForm("schedule-1", "ANC_VISIT").single()
    assertEquals("schedule-1", event.subjectId)
    assertEquals("ANC_VISIT", event.formCode)
    assertEquals(FormAuditEventType.SUBMITTED, event.eventType)
    assertEquals("sakhi-uuid-1", event.performedBySakhiId)
    assertEquals(fixedNowEpochMillis, event.timestampEpochMillis)
  }

  @Test
  fun `performedBySakhiId is null when no session is active`() = runTest {
    repository.recordOpened("schedule-1", "ANC_VISIT")

    val event = dao.getForForm("schedule-1", "ANC_VISIT").single()
    assertNull(event.performedBySakhiId)
  }

  @Test
  fun `multiple events for the same form are returned in timestamp order`() = runTest {
    sessionStore.saveSession(session)

    repository.recordOpened("schedule-1", "ANC_VISIT")
    repository.recordSaved("schedule-1", "ANC_VISIT")
    repository.recordSubmitted("schedule-1", "ANC_VISIT")

    val events = dao.getForForm("schedule-1", "ANC_VISIT")
    assertEquals(
      listOf(FormAuditEventType.OPENED, FormAuditEventType.SAVED, FormAuditEventType.SUBMITTED),
      events.map { it.eventType },
    )
  }
}
