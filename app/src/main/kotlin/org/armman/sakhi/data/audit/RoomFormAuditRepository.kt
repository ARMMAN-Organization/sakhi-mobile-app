package org.armman.sakhi.data.audit

import org.armman.sakhi.data.auth.session.SessionStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [FormAuditRepository]. [clock] is the same Hilt-provided `Clock.systemUTC()` singleton
 * [org.armman.sakhi.di.AuthModule] already provides for session-expiry checks — reused here instead
 * of a raw `System.currentTimeMillis()` call so this stays unit-testable with a fixed clock.
 */
@Singleton
class RoomFormAuditRepository @Inject constructor(
  private val dao: FormAuditEventDao,
  private val sessionStore: SessionStore,
  private val clock: Clock,
) : FormAuditRepository {

  override suspend fun recordOpened(subjectId: String, formCode: String) {
    record(subjectId, formCode, FormAuditEventType.OPENED)
  }

  override suspend fun recordSaved(subjectId: String, formCode: String) {
    record(subjectId, formCode, FormAuditEventType.SAVED)
  }

  override suspend fun recordSubmitted(subjectId: String, formCode: String) {
    record(subjectId, formCode, FormAuditEventType.SUBMITTED)
  }

  private suspend fun record(subjectId: String, formCode: String, eventType: FormAuditEventType) {
    dao.insert(
      FormAuditEventEntity(
        subjectId = subjectId,
        formCode = formCode,
        eventType = eventType,
        timestampEpochMillis = clock.millis(),
        performedBySakhiId = sessionStore.readSession()?.subjectId,
      ),
    )
  }
}
