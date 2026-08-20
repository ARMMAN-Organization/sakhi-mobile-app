package org.armman.sakhi.data.audit

/**
 * Capture-only local audit trail for form open/save/submit events (CR-035). No screen reads this
 * yet — see [FormAuditEventEntity]'s doc for the full rationale.
 */
interface FormAuditRepository {
  suspend fun recordOpened(subjectId: String, formCode: String)
  suspend fun recordSaved(subjectId: String, formCode: String)
  suspend fun recordSubmitted(subjectId: String, formCode: String)
}
