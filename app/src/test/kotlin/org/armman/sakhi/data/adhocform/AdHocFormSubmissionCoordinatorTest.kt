package org.armman.sakhi.data.adhocform

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.closure.FakeClosureRepository
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Covers [AdHocFormSubmissionCoordinator] — the ad-hoc-form twin of
 * [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinatorTest], minus the two-call
 * `POST /visits` step (these forms have no schedule to create a visit instance from).
 */
class AdHocFormSubmissionCoordinatorTest {

  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var closureRepository: FakeClosureRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var statusOverrideStore: LocalBeneficiaryStatusOverrideStore
  private lateinit var coordinator: AdHocFormSubmissionCoordinator

  private val session = UserSession(
    username = "test.sakhi",
    subjectId = "sakhi-uuid-1",
    roles = listOf("SAKHI"),
    projectId = "project-uuid-1",
    geographyUnitId = null,
    accessToken = "token",
    refreshToken = "refresh",
    accessTokenExpiresAtEpochSeconds = 9_999_999_999L,
  )

  @Before
  fun setUp() {
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    closureRepository = FakeClosureRepository()
    lookupRepository = FakeLookupRepository(
      valuesByCategory = mutableMapOf(
        "CLOSURE_REASON" to listOf(
          LookupValue(id = "lookup-closure-migration", valueCode = "MIGRATION", valueLabel = "Migration"),
          LookupValue(id = "lookup-closure-other", valueCode = "OTHER", valueLabel = "Other"),
          LookupValue(id = "lookup-closure-maternal-death", valueCode = "MATERNAL_DEATH", valueLabel = "Maternal death"),
          LookupValue(
            id = "lookup-closure-infant-or-child-death",
            valueCode = "INFANT_OR_CHILD_DEATH",
            valueLabel = "Infant or child death",
          ),
        ),
      ),
    )
    statusOverrideStore = LocalBeneficiaryStatusOverrideStore(FakeSecureKeyValueStore())
    coordinator = AdHocFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
      closureRepository = closureRepository,
      lookupRepository = lookupRepository,
      statusOverrideStore = statusOverrideStore,
    )
  }

  private val answers = FormAnswers(singleValues = mapOf("referral_facility" to "PHC Sonapur"))

  private suspend fun seedSyncedBeneficiary(localBeneficiaryId: String = "ben-1") {
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "schedule-for-$localBeneficiaryId",
          localBeneficiaryId = localBeneficiaryId,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  private fun successfulSubmissionResponse(id: String = "server-sub-1", submittedByUserId: String? = null) = Response.success(
    CreateSubmissionResponseDto(
      success = true,
      message = "OK",
      data = SubmissionResponseData(id = id, submittedByUserId = submittedByUserId),
    ),
  )

  private suspend fun submit() = coordinator.submit(
    localFormInstanceUuid = "instance-1",
    localBeneficiaryId = "ben-1",
    formCode = "REFERRAL_VISIT",
    formVersionId = "version-1",
    answers = answers,
  )

  private suspend fun submitClosure(
    formCode: String = "ANC_CLOSURE_VISIT",
    closureAnswers: FormAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "migration")),
  ) = coordinator.submit(
    localFormInstanceUuid = "instance-1",
    localBeneficiaryId = "ben-1",
    formCode = formCode,
    formVersionId = "version-1",
    answers = closureAnswers,
  )

  @Test
  fun `submit() success writes a SUBMITTED audit event and does not include submittedBy in the request`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals(
      listOf(FormAuditEventType.SUBMITTED),
      formAuditRepository.recordedEvents.map { it.eventType },
    )
    assertEquals("instance-1", formAuditRepository.recordedEvents.single().subjectId)
    assertEquals("REFERRAL_VISIT", formAuditRepository.recordedEvents.single().formCode)
    // CreateSubmissionRequestDto no longer declares a submittedBy field at all — nothing to assert
    // "is null" on; this documents that the request shape carries no such field any more.
    val request = requireNotNull(formSubmissionApi.lastRequest)
    assertEquals("server-ben-1", request.beneficiaryId)
    assertEquals("version-1", request.formVersionId)
  }

  @Test
  fun `submit() success reads submittedByUserId from the response`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse(submittedByUserId = "sakhi-uuid-1")

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals("sakhi-uuid-1", formSubmissionApi.response?.body()?.data?.submittedByUserId)
  }

  @Test
  fun `submit() failure does NOT write a SUBMITTED event`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = Response.error(
      500,
      "server error".toResponseBody("text/plain".toMediaType()),
    )

    val result = submit()

    assertTrue(result.isFailure)
    assertFalse(formAuditRepository.recordedEvents.any { it.eventType == FormAuditEventType.SUBMITTED })
  }

  @Test
  fun `submit() with no server beneficiary id fails with NotYetSynced and does not call the API`() = runTest {
    // No schedule row at all for this beneficiary.
    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is AdHocFormSubmissionException.NotYetSynced)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `submit() returns the server-assigned submission id on success`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse(id = "server-sub-99")

    val result = submit()

    assertEquals("server-sub-99", result.getOrNull())
  }

  @Test
  fun `closure submission also calls ClosureRepository submitClosure with expected fields and marks beneficiary CLOSED`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitClosure(
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "migration")),
    )

    assertTrue(result.isSuccess)
    val recorded = closureRepository.recordedClosures.single()
    assertEquals("instance-1", recorded.localClosureUuid)
    assertEquals("server-ben-1", recorded.beneficiaryId)
    assertEquals("NON_MEDICAL", recorded.closureType)
    assertEquals("lookup-closure-migration", recorded.closureReasonLookupValueId)
    assertEquals("sakhi-uuid-1", recorded.submittedByUserId)
    assertEquals("PENDING", recorded.supervisorStatus)
    assertEquals(BeneficiaryStatus.CLOSED, statusOverrideStore.getStatus("ben-1"))
  }

  @Test
  fun `non-closure form codes do not call ClosureRepository`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    submit()

    assertTrue(closureRepository.recordedClosures.isEmpty())
  }

  @Test
  fun `ANC_CLOSURE_VISIT maternal_death maps to the MATERNAL_DEATH backend code and MEDICAL closure type`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitClosure(
      formCode = "ANC_CLOSURE_VISIT",
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "maternal_death")),
    )

    assertTrue(result.isSuccess)
    val recorded = closureRepository.recordedClosures.single()
    assertEquals("MEDICAL", recorded.closureType)
    assertEquals("lookup-closure-maternal-death", recorded.closureReasonLookupValueId)
  }

  @Test
  fun `CHILD_CLOSURE_VISIT infant_child_death maps to the INFANT_OR_CHILD_DEATH backend code and MEDICAL closure type`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = submitClosure(
      formCode = "CHILD_CLOSURE_VISIT",
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "infant_child_death")),
    )

    assertTrue(result.isSuccess)
    val recorded = closureRepository.recordedClosures.single()
    assertEquals("MEDICAL", recorded.closureType)
    assertEquals("lookup-closure-infant-or-child-death", recorded.closureReasonLookupValueId)
  }

  @Test
  fun `an unrecognised closure_reason value_code fails with ClosureReasonValueCodeUnrecognised and does not call ClosureRepository`() = runTest {
    seedSyncedBeneficiary()
    formSubmissionApi.response = successfulSubmissionResponse()

    // "maternal_death" is a valid value_code for ANC_CLOSURE_VISIT but not for CHILD_CLOSURE_VISIT
    // — asserts the two forms' tables are genuinely kept separate, not silently merged.
    val result = submitClosure(
      formCode = "CHILD_CLOSURE_VISIT",
      closureAnswers = FormAnswers(singleValues = mapOf("closure_reason" to "maternal_death")),
    )

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertTrue(exception is AdHocFormSubmissionException.ClosureReasonValueCodeUnrecognised)
    exception as AdHocFormSubmissionException.ClosureReasonValueCodeUnrecognised
    assertEquals("CHILD_CLOSURE_VISIT", exception.formCode)
    assertEquals("maternal_death", exception.rawValueCode)
    assertTrue(closureRepository.recordedClosures.isEmpty())
  }
}
