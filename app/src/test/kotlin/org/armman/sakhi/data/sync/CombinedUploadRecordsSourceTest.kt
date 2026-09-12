package org.armman.sakhi.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.adhocform.AdHocFormDraftRepository
import org.armman.sakhi.data.adhocform.AdHocFormSubmitResult
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSubmitResult
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftRepository
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmitResult
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliveryFormSubmitResult
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
  private val adHocRecords = MutableStateFlow<List<FormUploadRecord>>(emptyList())
  private val deliveryRecords = MutableStateFlow<List<FormUploadRecord>>(emptyList())
  private val deliveryChildRegistrationRecords = MutableStateFlow<List<FormUploadRecord>>(emptyList())

  private val source = CombinedUploadRecordsSource(
    dynamicFormDraftRepository = FakeMotherRepository(motherRecords),
    childFormDraftRepository = FakeChildRepository(childRecords),
    visitFormDraftRepository = FakeVisitRepository(visitRecords),
    adHocFormDraftRepository = FakeAdHocRepository(adHocRecords),
    deliveryFormDraftRepository = FakeDeliveryRepository(deliveryRecords),
    deliveryChildRegistrationDraftRepository = FakeDeliveryChildRegistrationRepository(deliveryChildRegistrationRecords),
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
  fun `merges mother, child, visit and ad-hoc drafts into one list`() = runTest {
    motherRecords.value = listOf(record("m-1", "MOTHER_REGISTRATION", 100L))
    childRecords.value = listOf(record("c-1", "CHILD_REGISTRATION", 200L))
    visitRecords.value = listOf(record("v-1", "ANC_VISIT", 150L))
    adHocRecords.value = listOf(record("a-1", "REFERRAL_FOLLOWUP_VISIT", 175L))

    val merged = source.observeAll().first()

    assertEquals(4, merged.size)
    assertEquals(setOf("m-1", "c-1", "v-1", "a-1"), merged.map { it.localBeneficiaryId }.toSet())
  }

  @Test
  fun `a Referral Follow-up draft is visible on this queue even while still PENDING`() = runTest {
    // bharath, 2026-09-10 -- the actual reported bug: this queue was entirely absent from the
    // merge, so a Sakhi who submitted a Referral Follow-up offline and reopened the "Forms
    // Uploaded" modal after coming back online saw no row for it at all, with no way to tell
    // whether it was queued, uploading, or forgotten.
    adHocRecords.value = listOf(record("a-1", "REFERRAL_FOLLOWUP_VISIT", 500L))

    val merged = source.observeAll().first()

    assertEquals(1, merged.size)
    assertEquals("REFERRAL_FOLLOWUP_VISIT", merged.single().formCode)
    assertEquals(EnrollmentSyncStatus.PENDING, merged.single().syncStatus)
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

    adHocRecords.value = listOf(record("a-1", "REFERRAL_FOLLOWUP_VISIT", 350L))
    assertEquals(4, source.observeAll().first().size)

    motherRecords.value = emptyList()
    assertEquals(3, source.observeAll().first().size)
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

  /** Only [observeUploadRecords] matters here; the write path is covered by
   * [org.armman.sakhi.data.adhocform.RoomAdHocFormDraftRepositoryTest]. */
  private class FakeAdHocRepository(
    private val records: MutableStateFlow<List<FormUploadRecord>>,
  ) : AdHocFormDraftRepository {
    override suspend fun submitDraft(
      localFormInstanceUuid: String,
      localBeneficiaryId: String,
      formCode: String,
      formVersionId: String,
      answers: FormAnswers,
      referralId: String?,
      capturedImagePaths: Map<String, String>,
    ): AdHocFormSubmitResult = AdHocFormSubmitResult.Synced

    override suspend fun countByFormCode(localBeneficiaryId: String, formCode: String): Int = 0

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records
  }

  /** Bharath, 2026-09-11: same "queue absent from the merge" gap as the ad-hoc queue's own fake
   * above, this time for Delivery Form (CR-042). */
  private class FakeDeliveryRepository(
    private val records: MutableStateFlow<List<FormUploadRecord>>,
  ) : DeliveryFormDraftRepository {
    override suspend fun submitDraft(
      localSubmissionUuid: String,
      localSessionUuid: String,
      localBeneficiaryId: String,
      formVersionId: String,
      answers: FormAnswers,
      deliveryDate: LocalDate,
      deliveryFormFilledOn: LocalDate,
    ): DeliveryFormSubmitResult = DeliveryFormSubmitResult.Synced(childBeneficiaryIds = null)

    override suspend fun getAnswers(localSubmissionUuid: String): FormAnswers? = null

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records
  }

  private class FakeDeliveryChildRegistrationRepository(
    private val records: MutableStateFlow<List<FormUploadRecord>>,
  ) : DeliveryChildRegistrationDraftRepository {
    override suspend fun submitDraft(
      localSubmissionUuid: String,
      localSessionUuid: String,
      serverBeneficiaryId: String,
      formVersionId: String,
      answers: FormAnswers,
    ): DeliveryChildRegistrationSubmitResult = DeliveryChildRegistrationSubmitResult.Synced

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = records
  }
}
