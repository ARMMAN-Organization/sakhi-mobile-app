package org.armman.sakhi.data.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FormAuditEventDao {

  @Insert
  suspend fun insert(event: FormAuditEventEntity)

  @Query(
    "SELECT * FROM form_audit_events WHERE subjectId = :subjectId AND formCode = :formCode " +
      "ORDER BY timestampEpochMillis ASC",
  )
  suspend fun getForForm(subjectId: String, formCode: String): List<FormAuditEventEntity>

  @Query(
    "SELECT * FROM form_audit_events WHERE subjectId = :subjectId AND formCode = :formCode " +
      "ORDER BY timestampEpochMillis ASC",
  )
  fun observeForForm(subjectId: String, formCode: String): Flow<List<FormAuditEventEntity>>
}
