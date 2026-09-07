package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.audit.FakeFormAuditRepository
import org.armman.sakhi.data.audit.FormAuditEventType
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes
import org.armman.sakhi.data.forms.CreateSubmissionResponseDto
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmissionResponseData
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.CcvScheduleGenerator
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.IncScheduleGenerator
import org.armman.sakhi.data.schedule.NnScheduleGenerator
import org.armman.sakhi.data.schedule.PpScheduleGenerator
import org.armman.sakhi.data.schedule.AnchorType
import org.armman.sakhi.data.schedule.EscalationPolicy
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import retrofit2.Response

/**
 * Covers [DeliveryChildRegistrationSubmissionCoordinator] — CR-042's CHILD_REGISTRATION-for-an-
 * already-existing-child path. Deliberately does NOT exercise
 * [org.armman.sakhi.data.childregistration.ChildRegistrationSubmissionCoordinator] (the standalone
 * flow) at all — this coordinator never calls `POST /beneficiaries`, which is the entire point.
 */
class DeliveryChildRegistrationSubmissionCoordinatorTest {

  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var deliverySessionDao: FakeDeliverySessionDao
  private lateinit var deliverySessionRepository: DeliverySessionRepository
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var sessionStore: SessionStore
  private lateinit var formAuditRepository: FakeFormAuditRepository
  private lateinit var childFormDraftDao: FakeChildFormDraftDao
  private lateinit var visitScheduleDao: FakeVisitScheduleDao
  private lateinit var visitScheduleRepository: RoomVisitScheduleRepository
  private lateinit var visitScheduleCoordinator: VisitScheduleCoordinator
  private lateinit var visitScheduleSyncExecutor: org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor
  private lateinit var coordinator: DeliveryChildRegistrationSubmissionCoordinator

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

  private val answers = FormAnswers(singleValues = mapOf("name_of_the_child" to "Test Baby"))

  @Before
  fun setUp() {
    formSubmissionApi = FakeFormSubmissionApi()
    deliverySessionDao = FakeDeliverySessionDao()
    deliverySessionRepository = RoomDeliverySessionRepository(deliverySessionDao)
    secureStore = FakeSecureKeyValueStore()
    sessionStore = SessionStore(secureStore)
    sessionStore.saveSession(session)
    formAuditRepository = FakeFormAuditRepository()
    childFormDraftDao = FakeChildFormDraftDao()
    visitScheduleDao = FakeVisitScheduleDao()
    visitScheduleRepository = RoomVisitScheduleRepository(visitScheduleDao)
    val rules = HardcodedRuleSource()
    visitScheduleCoordinator = VisitScheduleCoordinator(
      repository = visitScheduleRepository,
      ancGenerator = AncScheduleGenerator(rules),
      ppGenerator = PpScheduleGenerator(rules),
      nnGenerator = NnScheduleGenerator(rules),
      incGenerator = IncScheduleGenerator(rules),
      ccvGenerator = CcvScheduleGenerator(rules),
    )
    visitScheduleSyncExecutor = org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor(
      visitScheduleRepository,
      org.armman.sakhi.data.schedule.FakeVisitScheduleApi(),
      org.armman.sakhi.data.visitform.FakeVisitFormSyncScheduler(),
    )
    coordinator = DeliveryChildRegistrationSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = sessionStore,
      formAuditRepository = formAuditRepository,
      childFormDraftDao = childFormDraftDao,
      secureStore = secureStore,
      visitScheduleCoordinator = visitScheduleCoordinator,
      visitScheduleRepository = visitScheduleRepository,
      visitScheduleSyncExecutor = visitScheduleSyncExecutor,
    )
  }

  private suspend fun seedSession(
    localSessionUuid: String = "session-1",
    child1: String? = "child-a",
    child2: String? = null,
    child3: String? = null,
    nextChildIndexToRegister: Int = 0,
    deliveryFormFilledOn: LocalDate? = null,
  ): DeliverySessionEntity {
    val entity = DeliverySessionEntity(
      localSessionUuid = localSessionUuid,
      localBeneficiaryId = "mother-1",
      step = DeliverySessionStep.CHILD_REGISTRATION,
      deliverySubmissionLocalUuid = "delivery-submission-1",
      deliveryFormFilledOn = deliveryFormFilledOn,
      child1BeneficiaryId = child1,
      child2BeneficiaryId = child2,
      child3BeneficiaryId = child3,
      nextChildIndexToRegister = nextChildIndexToRegister,
      createdAtEpochMillis = 1_000L,
      updatedAtEpochMillis = 1_000L,
    )
    deliverySessionRepository.save(entity)
    return entity
  }

  private fun successResponse() = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = "server-sub-1")),
  )

  private suspend fun submit(
    localSessionUuid: String = "session-1",
    serverBeneficiaryId: String = "child-a",
    localSubmissionUuid: String = "child-reg-submission-1",
    answersOverride: FormAnswers = answers,
  ) = coordinator.submit(
    localSessionUuid = localSessionUuid,
    serverBeneficiaryId = serverBeneficiaryId,
    localSubmissionUuid = localSubmissionUuid,
    formVersionId = "child-reg-version-1",
    answers = answersOverride,
  )

  @Test
  fun `submit() never calls a beneficiary-creation endpoint - only the form submission API`() = runTest {
    seedSession()
    formSubmissionApi.response = successResponse()

    val result = submit()

    assertTrue(result.isSuccess)
    // FakeFormSubmissionApi only models createSubmission - there is no beneficiary-creation call
    // site anywhere in this coordinator to accidentally invoke, which is the whole point of this
    // class existing separately from the standalone ChildRegistrationSubmissionCoordinator.
    assertEquals("child-reg-version-1", formSubmissionApi.lastRequest?.formVersionId)
    assertEquals("child-a", formSubmissionApi.lastRequest?.beneficiaryId)
  }

  @Test
  fun `submit() for the only child advances the session straight to PP1`() = runTest {
    seedSession(child1 = "child-a", child2 = null, child3 = null)
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-a")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.PP1, row.step)
    assertEquals(1, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() for the first of twins stays at CHILD_REGISTRATION for the second`() = runTest {
    seedSession(child1 = "child-a", child2 = "child-b", nextChildIndexToRegister = 0)
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-a")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, row.step)
    assertEquals(1, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() for the second of twins advances to PP1`() = runTest {
    seedSession(child1 = "child-a", child2 = "child-b", nextChildIndexToRegister = 1)
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-b")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.PP1, row.step)
    assertEquals(2, row.nextChildIndexToRegister)
  }

  // ---- Bug fix (2026-09-02): PP1 submitted out of sequence, before child registration ----------

  @Test
  fun `submit() does not regress to PP1 when the Sakhi already completed PP1 out of sequence`() = runTest {
    val deliveryFormFilledOn = LocalDate.of(2026, 6, 1)
    seedSession(child1 = "child-a", deliveryFormFilledOn = deliveryFormFilledOn)
    // The Sakhi opened PP1 straight from "See Visits" and submitted it before finishing the
    // child's own form — VisitFormSubmissionCoordinator.submit already flipped this row to
    // COMPLETED, same as it would for any other visit.
    visitScheduleRepository.saveGenerated(listOf(pp1Row(status = VisitScheduleStatus.COMPLETED)))
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-a")

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    // No same-session NN row exists for this child, so the session resolves straight to DONE —
    // never back to PP1, which would have left it stuck (nothing will ever submit that already-
    // completed PP1 a second time to advance it further).
    assertEquals(DeliverySessionStep.DONE, row.step)
  }

  @Test
  fun `submit() resolves to NN when PP1 is already done and a same-session NN visit is open`() = runTest {
    val deliveryFormFilledOn = LocalDate.of(2026, 6, 1)
    seedSession(child1 = "child-a", deliveryFormFilledOn = deliveryFormFilledOn)
    visitScheduleRepository.saveGenerated(listOf(pp1Row(status = VisitScheduleStatus.COMPLETED)))
    formSubmissionApi.response = successResponse()

    // submit() itself generates this child's own NN/INC schedule (anchored to "child-a"), and
    // NnScheduleGenerator's Scenario A clamps NN1's scheduledDate to the delivery-form-filled
    // date — so the freshly generated NN1 row already qualifies as the same-session visit without
    // any extra seeding here.
    submit(
      serverBeneficiaryId = "child-a",
      answersOverride = FormAnswers(
        singleValues = mapOf(
          "name_of_the_child" to "Test Baby",
          ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT to deliveryFormFilledOn.toString(),
        ),
      ),
    )

    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.NN, row.step)
  }

  private fun pp1Row(status: VisitScheduleStatus) = VisitScheduleEntity(
    localScheduleUuid = "pp1-schedule-1",
    localBeneficiaryId = "mother-1",
    visitCode = "PP1",
    visitType = VisitCodeType.PP,
    sequenceNo = 1,
    scheduledDate = LocalDate.of(2026, 6, 1),
    windowStartDate = LocalDate.of(2026, 6, 1),
    windowEndDate = LocalDate.of(2026, 6, 8),
    anchorType = AnchorType.DELIVERY_DATE,
    anchorDate = LocalDate.of(2026, 6, 1),
    status = status,
    generatedByRuleVersion = "v1",
    escalationPolicy = EscalationPolicy.IMMEDIATE,
    createdAtEpochMillis = 1_000L,
  )

  @Test
  fun `submit() success records a SUBMITTED audit event`() = runTest {
    seedSession()
    formSubmissionApi.response = successResponse()

    submit(localSubmissionUuid = "audit-submission-1")

    assertTrue(
      formAuditRepository.recordedEvents.any {
        it.subjectId == "audit-submission-1" &&
          it.formCode == "CHILD_REGISTRATION" &&
          it.eventType == FormAuditEventType.SUBMITTED
      },
    )
  }

  @Test
  fun `submit() fails with FormSubmissionFailed on a non-2xx response and does not advance the session`() = runTest {
    seedSession()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"Validation failed\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = submit()

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(error is DeliveryChildRegistrationSubmissionException.FormSubmissionFailed)
    assertEquals(422, (error as DeliveryChildRegistrationSubmissionException.FormSubmissionFailed).httpCode)
    val row = deliverySessionRepository.getBySessionUuid("session-1")!!
    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, row.step)
    assertEquals(0, row.nextChildIndexToRegister)
  }

  @Test
  fun `submit() fails with NoActiveSession when no Sakhi is signed in`() = runTest {
    val loggedOutSessionStore = SessionStore(FakeSecureKeyValueStore())
    val loggedOutCoordinator = DeliveryChildRegistrationSubmissionCoordinator(
      formSubmissionApi = formSubmissionApi,
      deliverySessionRepository = deliverySessionRepository,
      sessionStore = loggedOutSessionStore,
      formAuditRepository = formAuditRepository,
      childFormDraftDao = childFormDraftDao,
      secureStore = secureStore,
      visitScheduleCoordinator = visitScheduleCoordinator,
      visitScheduleRepository = visitScheduleRepository,
      visitScheduleSyncExecutor = visitScheduleSyncExecutor,
    )
    seedSession()
    formSubmissionApi.response = successResponse()

    val result = loggedOutCoordinator.submit(
      localSessionUuid = "session-1",
      serverBeneficiaryId = "child-a",
      localSubmissionUuid = "child-reg-submission-1",
      formVersionId = "child-reg-version-1",
      answers = answers,
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryChildRegistrationSubmissionException.NoActiveSession)
  }

  @Test
  fun `submit() fails with NoActiveDeliverySession when no session exists for localSessionUuid`() = runTest {
    // Deliberately no seedSession() call. CR-042 defect fix: this used to silently no-op (the
    // session read only happened inside advanceSessionAfterChildRegistered, after the form had
    // already been submitted to the backend with nothing local to show for it) — now the session
    // is required up front, since deliveryFormFilledOn from it drives this child's own schedule
    // generation below.
    formSubmissionApi.response = successResponse()

    val result = submit(localSessionUuid = "missing-session")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DeliveryChildRegistrationSubmissionException.NoActiveDeliverySession)
    assertEquals(null, deliverySessionRepository.getBySessionUuid("missing-session"))
  }

  // ---- CR-042 defect fix: local child draft + own-anchored schedule ----------------------------

  @Test
  fun `submit() saves a local child draft so the child appears on My Beneficiaries`() = runTest {
    seedSession(child1 = "child-a")
    formSubmissionApi.response = successResponse()

    submit(serverBeneficiaryId = "child-a")

    val draft = childFormDraftDao.getByLocalBeneficiaryId("child-a")
    assertTrue("Expected a local ChildFormDraftEntity for the auto-created child", draft != null)
    assertEquals(EnrollmentSyncStatus.SYNCED, draft!!.syncStatus)
    assertEquals("child-a", draft.remoteBeneficiaryId)
    assertEquals("CHILD_REGISTRATION", draft.formCode)
  }

  @Test
  fun `submit() generates NN and INC anchored to the child's own id, not the mother's`() = runTest {
    val dob = LocalDate.of(2026, 6, 1)
    val deliveryFormFilledOn = dob.plusDays(2)
    seedSession(child1 = "child-a", deliveryFormFilledOn = deliveryFormFilledOn)
    formSubmissionApi.response = successResponse()

    submit(
      serverBeneficiaryId = "child-a",
      answersOverride = FormAnswers(
        singleValues = mapOf(
          "name_of_the_child" to "Test Baby",
          ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT to dob.toString(),
        ),
      ),
    )

    val childSchedule = visitScheduleRepository.getForBeneficiary("child-a")
    assertEquals(2, childSchedule.count { it.visitType == VisitCodeType.NN })
    assertTrue(childSchedule.any { it.visitType == VisitCodeType.INC })
    // The bug this fixes: NN/INC must never land on the mother's own local id.
    assertTrue(visitScheduleRepository.getForBeneficiary("mother-1").none { it.visitType == VisitCodeType.NN })
    // Regression test for the "hasn't finished syncing yet" bug found in manual QA: a freshly
    // generated row for a brand-new beneficiary has no earlier synced row to backfill from, so it
    // must be explicitly stamped here rather than relying on VisitScheduleCoordinator's own
    // backfill.
    assertTrue(childSchedule.all { it.serverBeneficiaryId == "child-a" })
  }

  @Test
  fun `submit() does not crash and generates no schedule when deliveryFormFilledOn is missing`() = runTest {
    val dob = LocalDate.of(2026, 6, 1)
    // Deliberately no deliveryFormFilledOn — a pre-migration session row.
    seedSession(child1 = "child-a", deliveryFormFilledOn = null)
    formSubmissionApi.response = successResponse()

    val result = submit(
      serverBeneficiaryId = "child-a",
      answersOverride = FormAnswers(
        singleValues = mapOf(
          "name_of_the_child" to "Test Baby",
          ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT to dob.toString(),
        ),
      ),
    )

    assertTrue(result.isSuccess)
    assertTrue(visitScheduleRepository.getForBeneficiary("child-a").isEmpty())
    // The draft still saves even when the schedule can't be generated yet — a missing schedule is
    // fixable later, a lost registration is not.
    assertTrue(childFormDraftDao.getByLocalBeneficiaryId("child-a") != null)
  }
}
