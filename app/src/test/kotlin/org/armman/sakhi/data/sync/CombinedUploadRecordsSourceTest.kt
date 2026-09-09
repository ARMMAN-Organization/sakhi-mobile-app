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
import org.armman.sakhi.data.forms.EditableSubmissionInfo
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.visitform.VisitFormDraftRepository
import org.armman.sakhi.data.visitform.VisitFormSubmitResult
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
  private val visitRecords = MutableStateFlow<List<FormUploadRecord>>(emptyList())

  private val source = CombinedUploadRecordsSource(
    dynamicFormDraftRepository = FakeMotherRepository(motherRecords),
    childFormDraftRepository = FakeChildRepository(childRecords),
    visitFormDraftRepository = FakeVisitRepository(visitRecords),
  )

  private fun record(id: String, formCode: String, createdAt: Long) = FormUploadRecord(
    localBeneficiaryId = id,
    formCode = formCode,
    syncStatus = EnrollmentSyncStatus.PENDING,
    createdAtEpochMillis = createdAt,
  )

  @Test
  fun `emits immediately with all queues empty rather than waiting for a write`() = runTest {
    // combine() only produces a value once every upstream has emitted; all three are Room-backed
    // and emit their current contents on collection, so an empty app must still render an empty
    // modal.
    assertEquals(emptyList<FormUploadRecord>(), source.observeAll().first())
  }

  @Test
  fun `merges mother, child and visit drafts into one list`() = runTest {
    motherRecords.value = listOf(record("m-1", "MOTHER_REGISTRATION", 100L))
    childRecords.value = listOf(record("c-1", "CHILD_REGISTRATION", 200L))
    visitRecords.value = listOf(record("v-1", "ANC_VISIT", 150L))

    val merged = source.observeAll().first()

    assertEquals(3, merged.size)
    assertEquals(setOf("m-1", "c-1", "v-1"), merged.map { it.localBeneficiaryId }.toSet())
  }

  @Test
  fun `sorts the merged list newest-first across queues`() = runTest {
    // Each upstream is individually newest-first, but concatenating three sorted lists isn't
    // sorted — the interleaving here is what would break if the sort were dropped.
    motherRecords.value = listOf(
      record("m-new", "MOTHER_REGISTRATION", 400L),
      record("m-old", "MOTHER_REGISTRATION", 100L),
    )
    childRecords.value = listOf(
      record("c-new", "CHILD_REGISTRATION", 300L),
      record("c-old", "CHILD_REGISTRATION", 200L),
    )
    visitRecords.value = listOf(record("v-mid", "ANC_VISIT", 250L))

    val merged = source.observeAll().first()

    assertEquals(
      listOf("m-new", "c-new", "v-mid", "c-old", "m-old"),
      merged.map { it.localBeneficiaryId },
    )
  }

  @Test
  fun `re-emits when any queue changes`() = runTest {
    motherRecords.value = listOf(record("m-1", "MOTHER_REGISTRATION", 100L))
    assertEquals(1, source.observeAll().first().size)

    childRecords.value = listOf(record("c-1", "CHILD_REGISTRATION", 200L))
    assertEquals(2, source.observeAll().first().size)

    visitRecords.value = listOf(record("v-1", "ANC_VISIT", 300L))
    assertEquals(3, source.observeAll().first().size)

    motherRecords.value = emptyList()
    assertEquals(2, source.observeAll().first().size)
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

    override suspend fun confirmNewPregnancy(
      localBeneficiaryId: String,
      existingBeneficiaryId: String,
    ): DynamicFormSubmitResult = DynamicFormSubmitResult.Synced

    override suspend fun dismissNewPregnancyPrompt(localBeneficiaryId: String) = Unit

    override suspend fun getUploadRecords(): List<FormUploadRecord> = records.value

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records

    override suspend fun getEditableSubmission(remoteBeneficiaryId: String): EditableSubmissionInfo? = null

    override suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>) = Unit

    override suspend fun getRemoteBeneficiaryId(localBeneficiaryId: String): String? = null
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

    override suspend fun getEditableSubmission(remoteBeneficiaryId: String): EditableSubmissionInfo? = null

    override suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>) = Unit
  }

  /** Only [observeUploadRecords] matters here; the write path is covered by
   * [org.armman.sakhi.data.visitform.RoomVisitFormDraftRepositoryTest]. */
  private class FakeVisitRepository(
    private val records: MutableStateFlow<List<FormUploadRecord>>,
  ) : VisitFormDraftRepository {
    override suspend fun submitDraft(
      localScheduleUuid: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      visitDate: LocalDate,
      riskResult: org.armman.sakhi.data.rules.RiskGradingResult?,
      referralCapture: org.armman.sakhi.data.referral.ReferralCapture?,
      lmpChangeCapture: org.armman.sakhi.data.lmpchange.LmpChangeCapture?,
    ): VisitFormSubmitResult = VisitFormSubmitResult.Synced()

    override suspend fun getUploadRecords(): List<FormUploadRecord> = records.value

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records

    override suspend fun getAnswers(localScheduleUuid: String): FormAnswers? = null
  }
}
