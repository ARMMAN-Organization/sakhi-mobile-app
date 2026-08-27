package org.armman.sakhi.data.audit

/**
 * What happened to a form, for the capture-only local audit trail (CR-035). Stored on
 * [FormAuditEventEntity.eventType] the same way [org.armman.sakhi.data.enrollment.EnrollmentSyncStatus]
 * is stored on the draft entities — Room persists an enum column as TEXT by its name natively, no
 * [androidx.room.TypeConverter] needed.
 */
enum class FormAuditEventType {
  /** The form screen finished loading successfully — logged on EVERY open, not just the first, so
   * the trail reflects what actually happened rather than a simplified "first open only" summary. */
  OPENED,

  /** The form's answers were persisted as an offline draft — fired for both the online-immediate-
   * attempt path and the offline-queued path, since both write the same local draft row first. */
  SAVED,

  /** The form's submission reached the backend successfully (`POST /forms/:formCode/submissions`
   * returned success). Never logged on a failed attempt. */
  SUBMITTED,
}
