package org.armman.sakhi.data.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory fake mirroring `FakeVisitFormDraftDao`'s conventions, so [RoomFormAuditRepository] is
 * unit-testable without a real Room database. [observeForForm] is a hot [MutableStateFlow]
 * re-emitted on every [insert], mirroring how real Room invalidates its observable queries. */
class FakeFormAuditEventDao : FormAuditEventDao {
  private val rows = mutableListOf<FormAuditEventEntity>()
  private var nextId = 1L
  private val flowsBySubjectAndForm = mutableMapOf<Pair<String, String>, MutableStateFlow<List<FormAuditEventEntity>>>()

  override suspend fun insert(event: FormAuditEventEntity) {
    rows += event.copy(id = nextId++)
    emitCurrent(event.subjectId, event.formCode)
  }

  override suspend fun getForForm(subjectId: String, formCode: String): List<FormAuditEventEntity> =
    forForm(subjectId, formCode)

  override fun observeForForm(subjectId: String, formCode: String): Flow<List<FormAuditEventEntity>> =
    flowsBySubjectAndForm.getOrPut(subjectId to formCode) { MutableStateFlow(forForm(subjectId, formCode)) }

  private fun emitCurrent(subjectId: String, formCode: String) {
    flowsBySubjectAndForm.getOrPut(subjectId to formCode) { MutableStateFlow(emptyList()) }.value =
      forForm(subjectId, formCode)
  }

  private fun forForm(subjectId: String, formCode: String): List<FormAuditEventEntity> =
    rows.filter { it.subjectId == subjectId && it.formCode == formCode }
      .sortedBy { it.timestampEpochMillis }
}
