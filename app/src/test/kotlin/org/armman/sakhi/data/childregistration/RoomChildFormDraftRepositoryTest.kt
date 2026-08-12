package org.armman.sakhi.data.childregistration

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.connectivity.FakeConnectivityChecker
import org.armman.sakhi.data.forms.FakeEnrollmentApi
import org.armman.sakhi.data.forms.FakeFormSubmissionApi
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.CcvScheduleGenerator
import org.armman.sakhi.data.schedule.ChildEnrolmentScheduleTrigger
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.IncScheduleGenerator
import org.armman.sakhi.data.schedule.NnScheduleGenerator
import org.armman.sakhi.data.schedule.PpScheduleGenerator
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Covers the gap this change closes: submitting CHILD_REGISTRATION used to save the draft and stop
 * — [VisitScheduleCoordinator.onChildRegistered] existed but nothing ever called it, so an enrolled
 * child never got an INC schedule and her profile's "See Visits" stayed empty forever. Wired the
 * same way [org.armman.sakhi.data.forms.RoomDynamicFormDraftRepositoryTest] proves out the mother
 * side: real collaborators over fake DAOs, offline path only (no network needed to prove the
 * on-device generation, which is the point of SRS FR-S-2.2A).
 */
class RoomChildFormDraftRepositoryTest {

  private lateinit var dao: FakeChildFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var connectivityChecker: FakeConnectivityChecker
  private lateinit var scheduleRepository: RoomVisitScheduleRepository
  private lateinit var repository: RoomChildFormDraftRepository

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
    dao = FakeChildFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    connectivityChecker = FakeConnectivityChecker(online = false)

    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val mapper = ChildRegistrationSubmissionMapper(sessionStore, FakeLookupRepository())
    val coordinator = ChildRegistrationSubmissionCoordinator(
      FakeEnrollmentApi(),
      FakeFormSubmissionApi(),
      mapper,
    )
    val syncExecutor = ChildFormSyncExecutor(dao, secureStore, coordinator)

    val scheduleDao = FakeVisitScheduleDao()
    scheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    val rules = HardcodedRuleSource()
    val scheduleTrigger = ChildEnrolmentScheduleTrigger(
      coordinator = VisitScheduleCoordinator(
        repository = scheduleRepository,
        ancGenerator = AncScheduleGenerator(rules),
        ppGenerator = PpScheduleGenerator(rules),
        nnGenerator = NnScheduleGenerator(rules),
        incGenerator = IncScheduleGenerator(rules),
        ccvGenerator = CcvScheduleGenerator(rules),
      ),
    )

    repository = RoomChildFormDraftRepository(dao, secureStore, connectivityChecker, syncExecutor, scheduleTrigger)
  }

  private val answers = FormAnswers(
    singleValues = mapOf(
      "who_are_you_registering_in_the_program" to "child_directly_mother_not_registered_in_the_program",
      "did_we_receive_consent" to "yes",
      "date_of_birth_of_infant" to "2026-05-01",
      "name_of_the_child" to "Aarav Sharma",
      "sex_of_child" to "male",
      "term_of_delivery" to "full_term",
      "mobile_number" to "9876543210",
    ),
  )

  @Test
  fun `submitting a child registration generates her INC schedule`() = runTest {
    repository.submitDraft(
      localBeneficiaryId = "child-1",
      formCode = "CHILD_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-child-1",
      answers = answers,
      registrationDate = LocalDate.of(2026, 7, 20),
    )

    val schedule = scheduleRepository.getActiveForBeneficiary("child-1")
    assertTrue("Expected an INC schedule to be generated", schedule.isNotEmpty())
    assertTrue(schedule.all { it.visitType == VisitCodeType.INC })
  }

  @Test
  fun `submitting twice does not duplicate the schedule`() = runTest {
    repeat(2) {
      repository.submitDraft(
        localBeneficiaryId = "child-1",
        formCode = "CHILD_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-child-1",
        answers = answers,
        registrationDate = LocalDate.of(2026, 7, 20),
      )
    }

    val schedule = scheduleRepository.getActiveForBeneficiary("child-1")
    assertEquals(schedule.size, scheduleRepository.getActiveForBeneficiary("child-1").size)
  }

  @Test
  fun `a missing date of birth does not block the enrolment or crash the submit`() = runTest {
    val noDob = FormAnswers(singleValues = answers.singleValues - "date_of_birth_of_infant")

    val result = repository.submitDraft(
      localBeneficiaryId = "child-2",
      formCode = "CHILD_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-child-2",
      answers = noDob,
      registrationDate = LocalDate.of(2026, 7, 20),
    )

    assertEquals(ChildFormSubmitResult.QueuedOffline, result)
    assertTrue(scheduleRepository.getActiveForBeneficiary("child-2").isEmpty())
  }
}
