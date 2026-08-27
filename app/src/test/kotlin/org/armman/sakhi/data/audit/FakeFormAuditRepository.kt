package org.armman.sakhi.data.audit

/** In-memory fake used by every test that just needs to assert an audit event was (or wasn't)
 * recorded, without pulling in a real Room DAO/SessionStore/Clock stack. */
class FakeFormAuditRepository : FormAuditRepository {
  data class RecordedEvent(val subjectId: String, val formCode: String, val eventType: FormAuditEventType)

  val recordedEvents = mutableListOf<RecordedEvent>()

  override suspend fun recordOpened(subjectId: String, formCode: String) {
    recordedEvents += RecordedEvent(subjectId, formCode, FormAuditEventType.OPENED)
  }

  override suspend fun recordSaved(subjectId: String, formCode: String) {
    recordedEvents += RecordedEvent(subjectId, formCode, FormAuditEventType.SAVED)
  }

  override suspend fun recordSubmitted(subjectId: String, formCode: String) {
    recordedEvents += RecordedEvent(subjectId, formCode, FormAuditEventType.SUBMITTED)
  }
}
