package org.armman.sakhi.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSubmitResult
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The Home modal is the Sakhi's only view of what still needs uploading now that nothing syncs in
 * the background, so a queue missing from this merge is invisible work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CombinedUploadRecordsSourceTest {

  private val motherRecords = MutableStateFlow<List<FormUploadRecord>>(emptyList())
  private val childRecords = MutableStateFlow<List<FormUploadRecord>>(emptyList())

  private val source = CombinedUploadRecordsSource(
    dynamicFormDraftRepository = FakeMotherRepository(motherRecords),
    childFormDraftRepository = FakeChildRepository(childRecords),
  )

  private fun record(id: String, formCode: String, createdAt: Long) = FormUploadRecord(
    localBeneficiaryId = id,
    formCode = formCode,
    syncStatus = EnrollmentSyncStatus.PENDING,
    createdAtEpochMillis = createdAt,
  )

  @Test
  fun `emits immediately with both queues empty rather than waiting for a write`() = runTest {
    // combine() only produces a value once every upstream has emitted; both are Room-backed and emit
    // their current contents on collection, so an empty app must still render an empty modal.
    assertEquals(emptyList<FormUploadRecord>(), source.observeAll().first())
  }

  @Test
  fun `merges mother and child drafts into one list`() = runTest {
    motherRecords.value = listOf(record("m-1", "MOTHER_REGISTRATION", 100L))
    childRecords.value = listOf(record("c-1", "CHILD_REGISTRATION", 200L))

    val merged = source.observeAll().first()

    assertEquals(2, merged.size)
    assertEquals(setOf("m-1", "c-1"), merged.map { it.localBeneficiaryId }.toSet())
  }

  @Test
  fun `sorts the merged list newest-first across queues`() = runTest {
    // Each upstream is individually newest-first, but concatenating two sorted lists isn't sorted —
    // the interleaving here is what would break if the sort were dropped.
    motherRecords.value = listOf(
      record("m-new", "MOTHER_REGISTRATION", 400L),
      record("m-old", "MOTHER_REGISTRATION", 100L),
    )
    childRecords.value = listOf(
      record("c-new", "CHILD_REGISTRATION", 300L),
      record("c-old", "CHILD_REGISTRATION", 200L),
    )

    val merged = source.observeAll().first()

    assertEquals(listOf("m-new", "c-new", "c-old", "m-old"), merged.map { it.localBeneficiaryId })
  }

  @Test
  fun `re-emits when either queue changes`() = runTest {
    motherRecords.value = listOf(record("m-1", "MOTHER_REGISTRATION", 100L))
    assertEquals(1, source.observeAll().first().size)

    childRecords.value = listOf(record("c-1", "CHILD_REGISTRATION", 200L))
    assertEquals(2, source.observeAll().first().size)

    motherRecords.value = emptyList()
    assertEquals(1, source.observeAll().first().size)
  }

  /** Only [observeUploadRecords] matters here; the write path is covered by each repository's own
   * tests, so the rest is stubbed as narrowly as the interface allows. */
  private class FakeMotherRepository(
    private val records: MutableStateFlow<List<FormUploadRecord>>,
  ) : DynamicFormDraftRepository {
    override suspend fun saveDraft(
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      localSubmissionUuid: String,
      answers: FormAnswers,
      registrationDate: LocalDate,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun submitDraft(
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      localSubmissionUuid: String,
      answers: FormAnswers,
      registrationDate: LocalDate,
    ): DynamicFormSubmitResult = DynamicFormSubmitResult.Synced

    override suspend fun getUploadRecords(): List<FormUploadRecord> = records.value

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records
  }

  private class FakeChildRepository(
    private val records: MutableStateFlow<List<FormUploadRecord>>,
  ) : ChildFormDraftRepository {
    override suspend fun saveDraft(
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      localSubmissionUuid: String,
      answers: FormAnswers,
      registrationDate: LocalDate,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun submitDraft(
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      localSubmissionUuid: String,
      answers: FormAnswers,
      registrationDate: LocalDate,
    ): ChildFormSubmitResult = ChildFormSubmitResult.Synced

    override suspend fun getUploadRecords(): List<FormUploadRecord> = records.value

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records
  }
}
