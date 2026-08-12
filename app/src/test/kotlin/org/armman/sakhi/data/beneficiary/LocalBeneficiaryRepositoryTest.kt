package org.armman.sakhi.data.beneficiary

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.childregistration.ChildFormDraftEntity
import org.armman.sakhi.data.childregistration.ChildFormDraftPayload
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.childregistration.childFormDraftGson
import org.armman.sakhi.data.childregistration.childFormDraftPayloadKey
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * CR-022g. My Beneficiaries now lists the Sakhi's own enrolments and nothing else — the fourteen
 * seeded fixtures are gone, so an empty list on a fresh install is correct rather than a failure.
 *
 * Extended to cover CHILD_REGISTRATION drafts: they were originally filtered out entirely (a child
 * enrolled successfully but never appeared anywhere in the app), which is the defect these newer
 * tests guard against.
 */
class LocalBeneficiaryRepositoryTest {

  private lateinit var draftDao: FakeDynamicFormDraftDao
  private lateinit var childDraftDao: FakeChildFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var schedules: RoomVisitScheduleRepository
  private lateinit var generator: AncScheduleGenerator
  private lateinit var localEnrolments: LocalEnrolmentBeneficiarySource
  private lateinit var repository: LocalBeneficiaryRepository

  private val lmp = LocalDate.of(2026, 1, 1)
  private val edd = LocalDate.of(2026, 10, 8)

  @Before
  fun setUp() {
    draftDao = FakeDynamicFormDraftDao()
    childDraftDao = FakeChildFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    schedules = RoomVisitScheduleRepository(FakeVisitScheduleDao())
    generator = AncScheduleGenerator(HardcodedRuleSource())
    localEnrolments = LocalEnrolmentBeneficiarySource(
      draftDao,
      childDraftDao,
      secureStore,
      schedules,
      FakeFormsRepository(),
    )
    repository = LocalBeneficiaryRepository(localEnrolments)
  }

  /** A fresh install shows nothing, and that is the correct answer — the screen has an empty state. */
  @Test
  fun `a device with no enrolments returns an empty list`() = runTest {
    assertTrue(repository.getBeneficiaries().isEmpty())
  }

  @Test
  fun `no seeded fixtures leak into the list`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")

    val all = repository.getBeneficiaries()

    assertEquals(1, all.size)
    assertTrue("No b01..b14 fixture may appear", all.none { it.id.matches(Regex("b\\d{2}")) })
  }

  @Test
  fun `an enrolled beneficiary appears with her name and next scheduled visit`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")
    generateAncFor("local-1")

    val beneficiary = repository.getBeneficiaries().single()

    assertEquals("Sunita Pawar", beneficiary.name)
    assertEquals("ANC1", beneficiary.visitLabel)
    assertEquals(lmp, beneficiary.scheduleDate)
    assertEquals(BeneficiaryType.MOTHER, beneficiary.type)
    assertEquals(BeneficiaryStatus.ACTIVE, beneficiary.status)
    // The Active tab's default sub-tab is Open; anything else and she would not be visible.
    assertEquals(VisitState.OPEN, beneficiary.visitState)
  }

  @Test
  fun `several enrolments all appear`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")
    saveMotherEnrolment("local-2", "Asha", "Jadhav")

    assertEquals(2, repository.getBeneficiaries().size)
  }

  /** A woman enrolled before CR-022 has no schedule; her card must still render. */
  @Test
  fun `an enrolment with no schedule still renders`() = runTest {
    saveMotherEnrolment("local-1", "Asha", "Jadhav")

    val beneficiary = repository.getBeneficiaries().single()

    assertEquals("Asha Jadhav", beneficiary.name)
    assertEquals(0, beneficiary.daysRemaining)
  }

  /** One unreadable draft must not empty the Sakhi's whole screen. */
  @Test
  fun `a draft with no readable payload is skipped, leaving the others intact`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")
    draftDao.upsert(draftRow("orphan"))

    val all = repository.getBeneficiaries()

    assertEquals(1, all.size)
    assertEquals("local-1", all.single().id)
  }

  @Test
  fun `an enrolment with no name answers falls back to a placeholder rather than a blank card`() =
    runTest {
      draftDao.upsert(draftRow("local-1"))
      secureStore.putString(
        dynamicFormDraftPayloadKey("local-1"),
        dynamicFormDraftGson.toJson(
          DynamicFormDraftPayload(answers = FormAnswers(), registrationDateIso = lmp.toString()),
        ),
      )

      assertEquals("Unnamed beneficiary", repository.getBeneficiaries().single().name)
    }

  // ---- Child registration coverage --------------------------------------------------------------

  @Test
  fun `a child registration draft appears in the list typed as an infant`() = runTest {
    saveChildEnrolment("child-1", "Om")

    val beneficiary = repository.getBeneficiaries().single()

    assertEquals("Om", beneficiary.name)
    assertEquals(BeneficiaryType.INFANT, beneficiary.type)
    assertEquals(BeneficiaryStatus.ACTIVE, beneficiary.status)
  }

  @Test
  fun `mother and child enrolments both appear, each with their own type`() = runTest {
    saveMotherEnrolment("local-1", "Sunita", "Pawar")
    saveChildEnrolment("child-1", "Om")

    val all = repository.getBeneficiaries()

    assertEquals(2, all.size)
    assertEquals(BeneficiaryType.MOTHER, all.single { it.id == "local-1" }.type)
    assertEquals(BeneficiaryType.INFANT, all.single { it.id == "child-1" }.type)
  }

  @Test
  fun `a child with no name answer falls back to the same placeholder as a mother`() = runTest {
    childDraftDao.upsert(childDraftRow("child-1"))
    secureStore.putString(
      childFormDraftPayloadKey("child-1"),
      childFormDraftGson.toJson(
        ChildFormDraftPayload(answers = FormAnswers(), registrationDateIso = lmp.toString()),
      ),
    )

    assertEquals("Unnamed beneficiary", repository.getBeneficiaries().single().name)
  }

  @Test
  fun `a child enrolled directly, with no mother link, still appears`() = runTest {
    // The direct-registration path (WHO_ARE_YOU_REGISTERING = direct) has no mother_beneficiary_id
    // answer at all; the child must not depend on one to show up.
    saveChildEnrolment("child-1", "Priya")

    assertEquals(1, repository.getBeneficiaries().size)
  }

  @Test
  fun `a child beneficiary is findable by id for the profile screen`() = runTest {
    saveChildEnrolment("child-1", "Om")

    val found = localEnrolments.findLocalBeneficiary("child-1")

    assertEquals("Om", found?.name)
    assertEquals(BeneficiaryType.INFANT, found?.type)
  }

  @Test
  fun `an unknown id still returns null`() = runTest {
    assertNull(localEnrolments.findLocalBeneficiary("does-not-exist"))
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun draftRow(id: String, formCode: String = "MOTHER_REGISTRATION") =
    DynamicFormDraftEntity(
      localBeneficiaryId = id,
      formCode = formCode,
      formVersionId = "version-1",
      localSubmissionUuid = "submission-$id",
      syncStatus = EnrollmentSyncStatus.PENDING,
      createdAtEpochMillis = 1_754_265_600_000L,
      lastAttemptAtEpochMillis = null,
      retryCount = 0,
      remoteBeneficiaryId = null,
      remoteSubmissionId = null,
      lastErrorMessage = null,
    )

  /** [ChildFormDraftEntity]'s own table (`child_registration_drafts`) — CHILD_REGISTRATION never
   * lives alongside mother rows in `dynamic_form_drafts`. See [LocalEnrolmentBeneficiarySource]. */
  private fun childDraftRow(id: String) =
    ChildFormDraftEntity(
      localBeneficiaryId = id,
      formCode = "CHILD_REGISTRATION",
      formVersionId = "version-1",
      localSubmissionUuid = "submission-$id",
      syncStatus = EnrollmentSyncStatus.PENDING,
      createdAtEpochMillis = 1_754_265_600_000L,
      lastAttemptAtEpochMillis = null,
      retryCount = 0,
      remoteBeneficiaryId = null,
      remoteSubmissionId = null,
      lastErrorMessage = null,
    )

  private suspend fun saveMotherEnrolment(
    id: String,
    firstName: String,
    lastName: String,
  ) {
    draftDao.upsert(draftRow(id, formCode = "MOTHER_REGISTRATION"))
    secureStore.putString(
      dynamicFormDraftPayloadKey(id),
      dynamicFormDraftGson.toJson(
        DynamicFormDraftPayload(
          answers = FormAnswers(
            singleValues = mapOf(
              "first_name" to firstName,
              "last_name" to lastName,
              "mobile_number" to "9876543210",
            ),
          ),
          registrationDateIso = lmp.toString(),
        ),
      ),
    )
  }

  private suspend fun saveChildEnrolment(id: String, childName: String) {
    childDraftDao.upsert(childDraftRow(id))
    secureStore.putString(
      childFormDraftPayloadKey(id),
      childFormDraftGson.toJson(
        ChildFormDraftPayload(
          answers = FormAnswers(
            singleValues = mapOf(
              "name_of_the_child" to childName,
            ),
          ),
          registrationDateIso = lmp.toString(),
        ),
      ),
    )
  }

  private suspend fun generateAncFor(id: String) {
    var counter = 0
    schedules.saveGenerated(
      generator.generateSeries(
        ScheduleContext(localBeneficiaryId = id, registrationDate = lmp, lmp = lmp, edd = edd),
        newUuid = { "$id-${counter++}" },
      ),
    )
  }
}
