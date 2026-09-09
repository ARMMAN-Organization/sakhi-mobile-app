package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.EnrollmentRecord
import org.armman.sakhi.data.enrollment.EnrollmentRepository
import org.armman.sakhi.data.enrollment.EnrollmentSubmitResult
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.DynamicFormDraftRepository
import org.armman.sakhi.data.forms.DynamicFormSubmitResult
import org.armman.sakhi.data.forms.EditableSubmissionInfo
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.CcvScheduleGenerator
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.IncScheduleGenerator
import org.armman.sakhi.data.schedule.NnScheduleGenerator
import org.armman.sakhi.data.schedule.PpScheduleGenerator
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import retrofit2.Response

/**
 * Covers [DeliveryFormSubmissionCoordinator] — CR-042 Chunk A. Exercises the real
 * [VisitScheduleCoordinator] (Hardcoded-rules path, same as [org.armman.sakhi.data.schedule
 * .VisitScheduleCoordinatorTest]'s non-GoRules cases) rather than a fake, so a passing test here
 * also proves the [DeliverySessionEntity] write and the `onDeliveryRecorded` call actually compose
 * correctly, not just that they were called.
 */
class DeliveryFormSubmissionCoordinatorTest {

  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var visitScheduleCoordinator: VisitScheduleCoordinator
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var dynamicFormDraftRepository: FakeDynamicFormDraftRepositoryForDelivery
  private lateinit var enrollmentRepository: FakeEnrollmentRepositoryForDelivery
  private lateinit var coordinator: DeliveryFormSubmissionCoordinator

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

  private val deliveryDate = LocalDate.of(2026, 8, 1)
  private val answers = FormAnswers(singleValues = mapOf("delivery_outcome" to "live_birth"))

  @Before
  fun setUp() {
    formSubmissionApi = FakeFormSubmissionApi()
    scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    val rules = HardcodedRuleSource()
    visitScheduleCoordinator = VisitScheduleCoordinator(
      repository = scheduleRepository,
      ancGenerator = AncScheduleGenerator(rules),
      ppGenerator = PpScheduleGenerator(rules),
      nnGenerator = NnScheduleGenerator(rules),
      incGenerator = IncScheduleGenerator(rules),
      ccvGenerator = CcvScheduleGenerator(rules),
    )
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    dynamicFormDraftRepository = FakeDynamicFormDraftRepositoryForDelivery()
    enrollmentRepository = FakeEnrollmentRepositoryForDelivery()
    coordinator = DeliveryFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      visitScheduleCoordinator = visitScheduleCoordinator,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
      dynamicFormDraftRepository = dynamicFormDraftRepository,
      enrollmentRepository = enrollmentRepository,
    )
  }

  private suspend fun seedSyncedMother(localBeneficiaryId: String = "mother-1") {
    // An ANC schedule row already synced — this is what NotYetSynced/serverBeneficiaryId
    // resolution reuses, same as AdHocFormSubmissionCoordinatorTest's seedSyncedBeneficiary.
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "anc-schedule-$localBeneficiaryId",
          localBeneficiaryId = localBeneficiaryId,
          serverScheduleId = "server-schedule-1",
          serverBeneficiaryId = "server-$localBeneficiaryId",
        ),
      ),
    )
  }

  private fun successResponse(childIds: List<String>? = null) = Response.success(
    CreateSubmissionResponseDto(
      success = true,
      message = "OK",
      data = SubmissionResponseData(id = "server-sub-1", childBeneficiaryIds = childIds),
    ),
  )

  private suspend fun submit(
    localBeneficiaryId: String = "mother-1",
    localSessionUuid: String = "session-1",
    localSubmissionUuid: String = "submission-1",
  ) = coordinator.submit(
    localSubmissionUuid = localSubmissionUuid,
    localSessionUuid = localSessionUuid,
    localBeneficiaryId = localBeneficiaryId,
    formVersionId = "version-1",
    answers = answers,
    deliveryDate = deliveryDate,
    deliveryFormFilledOn = deliveryDate,
  )

  @Test
  fun `submit() with two live children advances the session to CHILD_REGISTRATION and stores both ids`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(listOf("child-a", "child-b"))

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals(listOf("child-a", "child-b"), result.getOrNull())

    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertNotNull(sessionRow)
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, sessionRow!!.step)
    assertEquals("child-a", sessionRow.child1BeneficiaryId)
    assertEquals("child-b", sessionRow.child2BeneficiaryId)
    assertNull(sessionRow.child3BeneficiaryId)
    assertEquals("submission-1", sessionRow.deliverySubmissionLocalUuid)
  }

  @Test
  fun `submit() persists deliveryFormFilledOn onto the session row`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    submit()

    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertEquals(deliveryDate, sessionRow?.deliveryFormFilledOn)
  }

  @Test
  fun `submit() with null childBeneficiaryIds (no live birth) advances straight to PP1`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())

    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertEquals(DeliverySessionStep.PP1, sessionRow!!.step)
    assertNull(sessionRow.child1BeneficiaryId)
  }

  @Test
  fun `submit() with an empty (non-null) childBeneficiaryIds list is treated the same as null`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = emptyList())

    val result = submit()

    assertTrue(result.isSuccess)
    val sessionRow = deliverySessionRepository.getBySessionUuid("session-1")
    assertEquals(DeliverySessionStep.PP1, sessionRow!!.step)
  }

  @Test
  fun `submit() success generates the PP series via onDeliveryRecorded`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    submit()

    val ppRows = scheduleRepository.getForBeneficiary("mother-1")
      .filter { it.visitType == org.armman.sakhi.data.schedule.VisitCodeType.PP }
    assertTrue("expected a PP series to be generated", ppRows.isNotEmpty())
  }

  @Test
  fun `submit() success records a SUBMITTED audit event`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    submit(localSubmissionUuid = "submission-audit-1")

    assertTrue(
      formAuditRepository.recordedEvents.any {
        it.subjectId == "submission-audit-1" &&
          it.formCode == "DELIVERY_VISIT" &&
          it.eventType == org.armman.sakhi.data.audit.FormAuditEventType.SUBMITTED
      },
    )
  }

  @Test
  fun `submit() fails with NotYetSynced when the mother has no server beneficiary id yet`() = runTest {
    // No seedSyncedMother() — no schedule row exists for this beneficiary at all.
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NotYetSynced)
    assertNull(deliverySessionRepository.getBySessionUuid("session-1"))
  }

  @Test
  fun `submit() fails with FormSubmissionFailed on a non-2xx response and writes no session row`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submit()

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(error is DeliveryFormSubmissionException.FormSubmissionFailed)
    assertEquals(422, (error as DeliveryFormSubmissionException.FormSubmissionFailed).httpCode)
    assertNull(deliverySessionRepository.getBySessionUuid("session-1"))
  }

  @Test
  fun `submit() fails with NoSubmissionIdReturned when the response body has no data`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = Response.success(
      CreateSubmissionResponseDto(success = true, message = "OK", data = null),
    )

    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NoSubmissionIdReturned)
  }

  @Test
  fun `submit() fails with NoActiveSession when no Sakhi is signed in`() = runTest {
    val loggedOutSessionStore = SessionStore(FakeSecureKeyValueStore())
    val loggedOutCoordinator = DeliveryFormSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      visitScheduleRepository = scheduleRepository,
      visitScheduleCoordinator = visitScheduleCoordinator,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = loggedOutSessionStore,
      formAuditRepository = formAuditRepository,
      dynamicFormDraftRepository = dynamicFormDraftRepository,
      enrollmentRepository = enrollmentRepository,
    )
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = null)

    val result = loggedOutCoordinator.submit(
      localSubmissionUuid = "submission-1",
      localSessionUuid = "session-1",
      localBeneficiaryId = "mother-1",
      formVersionId = "version-1",
      answers = answers,
      deliveryDate = deliveryDate,
      deliveryFormFilledOn = deliveryDate,
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NoActiveSession)
  }

  @Test
  fun `a resumed retry on the same session preserves createdAtEpochMillis`() = runTest {
    seedSyncedMother()
    formSubmissionApi.response = successResponse(childIds = listOf("child-a"))
    submit(localSessionUuid = "session-1", localSubmissionUuid = "submission-1")
    val firstRow = deliverySessionRepository.getBySessionUuid("session-1")!!

    // Simulate a later retry of the same submission (e.g. a background sync re-attempt after the
    // session row already exists from an earlier partial success).
    submit(localSessionUuid = "session-1", localSubmissionUuid = "submission-1")
    val secondRow = deliverySessionRepository.getBySessionUuid("session-1")!!

    assertEquals(firstRow.createdAtEpochMillis, secondRow.createdAtEpochMillis)
  }

  // --- serverBeneficiaryId resolution fallbacks (bug: "hasn't finished syncing" despite WiFi) ---

  @Test
  fun `submit() falls back to the mother's MOTHER_REGISTRATION draft when no schedule row has a server id yet`() = runTest {
    // No seedSyncedMother() — her ANC schedule rows were never linked (e.g. she registered before
    // that linking existed) — but her enrollment draft itself DID sync.
    dynamicFormDraftRepository.remoteBeneficiaryIds["mother-1"] = "server-mother-1"
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals("server-mother-1", formSubmissionApi.lastRequest?.beneficiaryId)
  }

  @Test
  fun `submit() falls back to the legacy enrollment draft when neither a schedule row nor a dynamic-form draft has a server id`() = runTest {
    enrollmentRepository.remoteBeneficiaryIds["mother-1"] = "server-mother-1"
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isSuccess)
    assertEquals("server-mother-1", formSubmissionApi.lastRequest?.beneficiaryId)
  }

  @Test
  fun `submit() prefers the dynamic-form draft over the legacy enrollment draft when both somehow have an id`() = runTest {
    dynamicFormDraftRepository.remoteBeneficiaryIds["mother-1"] = "from-dynamic-form"
    enrollmentRepository.remoteBeneficiaryIds["mother-1"] = "from-legacy"
    formSubmissionApi.response = successResponse(childIds = null)

    submit()

    assertEquals("from-dynamic-form", formSubmissionApi.lastRequest?.beneficiaryId)
  }

  @Test
  fun `submit() resolved via a fallback self-heals the mother's existing schedule rows for future lookups`() = runTest {
    // Realistic setup: her ANC schedule was generated at enrolment (CR-022 — always happens
    // immediately, offline or not) but never got linked to a server beneficiary id — the exact
    // "registered before linking existed, or the one-time attempt silently failed" case this fix
    // targets. Seeded directly (not via seedSyncedMother, which already carries a server id) so
    // the row starts genuinely unlinked.
    scheduleRepository.saveGenerated(
      listOf(
        schedule(
          "anc-schedule-mother-1",
          localBeneficiaryId = "mother-1",
          serverScheduleId = null,
          serverBeneficiaryId = null,
        ),
      ),
    )
    dynamicFormDraftRepository.remoteBeneficiaryIds["mother-1"] = "server-mother-1"
    formSubmissionApi.response = successResponse(childIds = null)

    submit()

    // The pre-existing ANC row — previously unlinked — is now stamped, proving the fallback
    // didn't just satisfy this one Delivery submission but actually re-linked her, so every other
    // row of hers (this one, and whatever PP/NN this same submit() just generated) is
    // upload-eligible from here on, not only the Delivery form.
    val scheduleRows = scheduleRepository.getForBeneficiary("mother-1")
    assertTrue(scheduleRows.isNotEmpty())
    assertTrue(scheduleRows.all { it.serverBeneficiaryId == "server-mother-1" })
  }

  @Test
  fun `submit() still fails with NotYetSynced when every source is empty`() = runTest {
    // No schedule row, no dynamic-form draft, no legacy enrollment draft — genuinely never synced.
    formSubmissionApi.response = successResponse(childIds = null)

    val result = submit()

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryFormSubmissionException.NotYetSynced)
  }

  /** Minimal fake — only [getRemoteBeneficiaryId] is exercised by these tests; every other member
   * is unused here and left as an empty/no-op implementation. */
  private class FakeDynamicFormDraftRepositoryForDelivery : DynamicFormDraftRepository {
    val remoteBeneficiaryIds = mutableMapOf<String, String>()

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
    ): DynamicFormSubmitResult = DynamicFormSubmitResult.QueuedOffline

    override suspend fun confirmNewPregnancy(
      localBeneficiaryId: String,
      existingBeneficiaryId: String,
    ): DynamicFormSubmitResult = DynamicFormSubmitResult.QueuedOffline

    override suspend fun dismissNewPregnancyPrompt(localBeneficiaryId: String) = Unit

    override suspend fun getUploadRecords(): List<FormUploadRecord> = emptyList()

    override fun observeUploadRecords(): Flow<List<FormUploadRecord>> = flowOf(emptyList())

    override suspend fun getEditableSubmission(beneficiaryId: String): EditableSubmissionInfo? = null

    override suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>) = Unit

    override suspend fun getRemoteBeneficiaryId(localBeneficiaryId: String): String? =
      remoteBeneficiaryIds[localBeneficiaryId]
  }

  /** Minimal fake — only [getRemoteBeneficiaryId] is exercised by these tests. */
  private class FakeEnrollmentRepositoryForDelivery : EnrollmentRepository {
    val remoteBeneficiaryIds = mutableMapOf<String, String>()

    override suspend fun saveEnrollment(record: EnrollmentRecord): Result<Unit> = Result.success(Unit)

    override suspend fun submitEnrollment(record: EnrollmentRecord): EnrollmentSubmitResult =
      EnrollmentSubmitResult.QueuedOffline

    override suspend fun getEnrollment(beneficiaryId: String): EnrollmentRecord? = null

    override suspend fun getRemoteBeneficiaryId(beneficiaryId: String): String? =
      remoteBeneficiaryIds[beneficiaryId]
  }
}
