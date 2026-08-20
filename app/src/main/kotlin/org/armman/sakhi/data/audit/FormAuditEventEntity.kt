package org.armman.sakhi.data.audit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One capture-only local audit-trail row (CR-035): a form was opened, saved as an offline draft,
 * or submitted. No screen reads this yet — it exists purely so the trail is being recorded
 * faithfully from day one, before any viewer is built.
 *
 * [subjectId] is whichever key the form family already uses as its own draft primary key —
 * `localScheduleUuid` for visit forms ([org.armman.sakhi.data.visitform.VisitFormDraftEntity]'s own
 * key), `localBeneficiaryId` for registration forms
 * ([org.armman.sakhi.data.forms.DynamicFormDraftEntity]'s own key). Deliberately not a foreign key
 * to either draft table — a row here must survive independently of whatever happens to the draft
 * afterwards (e.g. a re-save resetting the draft's own sync bookkeeping).
 */
@Entity(
  tableName = "form_audit_events",
  indices = [
    Index(value = ["subjectId", "formCode"]),
  ],
)
data class FormAuditEventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val subjectId: String,
  val formCode: String,
  val eventType: FormAuditEventType,
  val timestampEpochMillis: Long,
  /** The signed-in Sakhi at the time of the event, from [org.armman.sakhi.data.auth.session.SessionStore]
   * — null only in the (practically unreachable) case no session was active. */
  val performedBySakhiId: String?,
)
