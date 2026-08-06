package org.armman.sakhi.data.beneficiaryprofile

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.DynamicFormDraftEntity
import org.armman.sakhi.data.forms.DynamicFormDraftPayload
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.dynamicFormDraftGson
import org.armman.sakhi.data.forms.dynamicFormDraftPayloadKey
import org.armman.sakhi.data.schedule.AncScheduleGenerator
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.ScheduleContext
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** CR-022f cases PR-1, PR-2, PR-3, PR-9, PR-10. */
class ScheduleBackedBeneficiaryProfileRepositoryTest {

  private lateinit var dao: FakeVisitScheduleDao
  private lateinit var schedules: RoomVisitScheduleRepository
  private lateinit var generator: AncScheduleGenerator
  private lateinit var draftDao: FakeDynamicFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var localEnrolments: LocalEnrolmentBeneficiarySource
  private lateinit var repository: ScheduleBackedBeneficiaryProfileRepository

  private val lmp = LocalDate.of(2026, 1, 1)
  private val edd = LocalDate.of(2026, 10, 8)

  @Before
  fun setUp() {
    dao = FakeVisitScheduleDao()
    schedules = RoomVisitScheduleRepository(dao)
    generator = AncScheduleGenerator(HardcodedRuleSource())
    draftDao = FakeDynamicFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    localEnrolments = LocalEnrolmentBeneficiarySource(draftDao, secureStore, schedules, FakeFormsRepository())
    repository = ScheduleBackedBeneficiaryProfileRepository(
      staticProfiles = StaticBeneficiaryProfileRepository(),
      scheduleRepository = schedules,
      localEnrolments = localEnrolments,
    )
  }

  /**
   * A locally enrolled woman's id is a UUID the static records know nothing about. Without the
   * local-first lookup, every tap from My Beneficiaries into a real enrolment lands on the profile
   * screen's error state — which is exactly what happened before CR-022g.
   */
  @Test
  fun `a locally enrolled beneficiary resolves to a profile with her own schedule`() = runTest {
    val localId = "local-uuid-1"
    saveLocalEnrolment(localId, firstName = "Sunita", lastName = "Pawar")
    generateAncFor(localId)

    val profile = repository.getBeneficiary(localId)

    assertEquals("Sunita Pawar", profile.name)
    assertEquals(10, profile.visits.size)
  }

  /**
   * `husbands_name` reached the MOTHER_REGISTRATION schema on 2026-08-05 (backend PR #105). Before
   * that the profile row was blank because the form never asked.
   */
  @Test
  fun `husband's name is read from the answers`() = runTest {
    val localId = "local-uuid-3"
    saveLocalEnrolment(
      localId,
      firstName = "Sunita",
      lastName = "Pawar",
      husbandsName = "Akash Pawar",
    )

    assertEquals("Akash Pawar", repository.getBeneficiary(localId).husbandName)
  }

  /** The question is optional, and anyone enrolled before PR #105 has no answer at all. */
  @Test
  fun `an unanswered husband's name renders blank rather than failing`() = runTest {
    val localId = "local-uuid-4"
    saveLocalEnrolment(localId, firstName = "Asha", lastName = "Jadhav")

    assertEquals("", repository.getBeneficiary(localId).husbandName)
  }

  @Test
  fun `a locally enrolled beneficiary with no schedule still resolves`() = runTest {
    val localId = "local-uuid-2"
    saveLocalEnrolment(localId, firstName = "Asha", lastName = "Jadhav")

    val profile = repository.getBeneficiary(localId)

    assertEquals("Asha Jadhav", profile.name)
    assertTrue(profile.visits.isEmpty())
  }

  // PR-1
  @Test
  fun `the profile shows the beneficiary's generated ANC schedule`() = runTest {
    generateAncFor(MOTHER_A)

    val profile = repository.getBeneficiary(MOTHER_A)

    assertEquals(10, profile.visits.size)
    // Soonest first — her next visit tops the list, not the one eight months out.
    assertEquals("ANC1", profile.visits.first().label)
    assertEquals("ANC10", profile.visits.last().label)
  }

  /**
   * PR-2, and the bug this CR exists to fix. The static repository returns the *same five-row visit
   * list for every beneficiary*, so today two different women show identical visit histories.
   */
  @Test
  fun `PR-2 two beneficiaries show different visit lists`() = runTest {
    generateAncFor(MOTHER_A, edd = edd)
    // A later EDD buys more ANC visits, so the two schedules differ in length as well as identity.
    generateAncFor(MOTHER_B, edd = edd.plusDays(60))

    val a = repository.getBeneficiary(MOTHER_A).visits
    val b = repository.getBeneficiary(MOTHER_B).visits

    assertNotEquals(a.size, b.size)
    assertTrue(
      "No visit id may be shared between two beneficiaries",
      a.map { it.id }.intersect(b.map { it.id }.toSet()).isEmpty(),
    )
  }

  // PR-3
  @Test
  fun `a beneficiary with no schedule gets an empty visit list, not another woman's visits`() =
    runTest {
      val profile = repository.getBeneficiary(MOTHER_A)

      assertTrue(profile.visits.isEmpty())
    }

  @Test
  fun `the static half of the profile is still served`() = runTest {
    val profile = repository.getBeneficiary(MOTHER_A)

    // Identity and vitals remain static until a beneficiary-detail API exists — this CR replaces
    // only the visit list.
    assertTrue(profile.name.isNotBlank())
    assertTrue(profile.lastVisitStats.isNotEmpty())
  }

  @Test
  fun `an unknown id still surfaces as an error`() = runTest {
    val result = runCatching { repository.getBeneficiary("does-not-exist") }

    assertTrue(result.exceptionOrNull() is NoSuchElementException)
  }

  // PR-10
  @Test
  fun `after a supersession only the new schedule is shown`() = runTest {
    generateAncFor(MOTHER_A)
    val before = repository.getBeneficiary(MOTHER_A).visits.map { it.id }

    schedules.supersedeOpenVisits(MOTHER_A)
    generateAncFor(MOTHER_A, edd = edd.plusDays(60))

    val after = repository.getBeneficiary(MOTHER_A).visits

    assertEquals(12, after.size)
    assertTrue(
      "No superseded visit may remain visible",
      after.map { it.id }.intersect(before.toSet()).isEmpty(),
    )
  }

  @Test
  fun `lapsed ANC visits disappear from the profile after delivery`() = runTest {
    generateAncFor(MOTHER_A)

    schedules.lapseOpenAncVisits(MOTHER_A)

    assertTrue(repository.getBeneficiary(MOTHER_A).visits.isEmpty())
  }

  @Test
  fun `a completed visit stays visible after the rest are lapsed`() = runTest {
    generateAncFor(MOTHER_A)
    val anc1 = schedules.getForBeneficiary(MOTHER_A).first()
    schedules.updateStatus(anc1.localScheduleUuid, VisitScheduleStatus.COMPLETED)

    schedules.lapseOpenAncVisits(MOTHER_A)

    val visits = repository.getBeneficiary(MOTHER_A).visits
    assertEquals(1, visits.size)
    assertEquals(ProfileVisitState.COMPLETED, visits.single().state)
  }

  /** PR-9 — the profile reads Room only; nothing in this path touches the network. */
  @Test
  fun `the visit list is served entirely from local storage`() = runTest {
    generateAncFor(MOTHER_A)

    // No API collaborator exists in the constructor, so a passing read proves the offline path.
    assertEquals(10, repository.getBeneficiary(MOTHER_A).visits.size)
  }

  /**
   * Ids are unique across *every* call, not just within one series.
   *
   * A per-call counter would make a regenerated schedule reuse `b01-0`, `b01-1`… and the fake DAO
   * is a map keyed by uuid — so the new rows would overwrite the superseded ones instead of sitting
   * alongside them, and the supersession test would silently pass for the wrong reason.
   */
  private var uuidCounter = 0

  /** Writes the Room metadata row and the encrypted payload the real enrolment flow would leave. */
  private suspend fun saveLocalEnrolment(
    id: String,
    firstName: String,
    lastName: String,
    husbandsName: String? = null,
  ) {
    draftDao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = id,
        formCode = "MOTHER_REGISTRATION",
        formVersionId = "version-1",
        localSubmissionUuid = "submission-$id",
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = 1_754_265_600_000L,
        lastAttemptAtEpochMillis = null,
        retryCount = 0,
        remoteBeneficiaryId = null,
        remoteSubmissionId = null,
        lastErrorMessage = null,
      ),
    )
    secureStore.putString(
      dynamicFormDraftPayloadKey(id),
      dynamicFormDraftGson.toJson(
        DynamicFormDraftPayload(
          answers = FormAnswers(
            singleValues = buildMap {
              put("first_name", firstName)
              put("last_name", lastName)
              put("mobile_number", "9876543210")
              // Optional since PR #105 — omitted entirely when unanswered, as the real form does.
              husbandsName?.let { put("husbands_name", it) }
            },
          ),
          registrationDateIso = lmp.toString(),
        ),
      ),
    )
  }

  private suspend fun generateAncFor(beneficiaryId: String, edd: LocalDate = this.edd) {
    val visits = generator.generateSeries(
      ScheduleContext(
        localBeneficiaryId = beneficiaryId,
        registrationDate = lmp,
        lmp = lmp,
        edd = edd,
      ),
      newUuid = { "$beneficiaryId-${uuidCounter++}" },
    )
    schedules.saveGenerated(visits)
  }

  private companion object {
    /** Ids that exist in the static profile records, so the delegate resolves. */
    const val MOTHER_A = "b01"
    const val MOTHER_B = "b02"
  }
}
