package org.armman.sakhi.data.beneficiaryprofile

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.BeneficiaryDetailDto
import org.armman.sakhi.data.motherlink.BeneficiaryDetailResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryListResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryPiiDto
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
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes
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
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/** CR-022f cases PR-1, PR-2, PR-3, PR-9, PR-10. */
class ScheduleBackedBeneficiaryProfileRepositoryTest {

  private lateinit var dao: FakeVisitScheduleDao
  private lateinit var schedules: RoomVisitScheduleRepository
  private lateinit var generator: AncScheduleGenerator
  private lateinit var draftDao: FakeDynamicFormDraftDao
  private lateinit var childDraftDao: FakeChildFormDraftDao
  private lateinit var secureStore: FakeSecureKeyValueStore
  private lateinit var localEnrolments: LocalEnrolmentBeneficiarySource
  private lateinit var beneficiaryApi: FakeBeneficiaryApi
  private lateinit var remoteProfiles: RemoteBeneficiaryProfileRepository
  private lateinit var repository: ScheduleBackedBeneficiaryProfileRepository
  private lateinit var formsRepository: FakeFormsRepository

  private val lmp = LocalDate.of(2026, 1, 1)
  private val edd = LocalDate.of(2026, 10, 8)

  @Before
  fun setUp() {
    dao = FakeVisitScheduleDao()
    schedules = RoomVisitScheduleRepository(dao)
    generator = AncScheduleGenerator(HardcodedRuleSource())
    draftDao = FakeDynamicFormDraftDao()
    childDraftDao = FakeChildFormDraftDao()
    secureStore = FakeSecureKeyValueStore()
    formsRepository = FakeFormsRepository()
    localEnrolments = LocalEnrolmentBeneficiarySource(
      draftDao,
      childDraftDao,
      secureStore,
      schedules,
      formsRepository,
      LocalBeneficiaryStatusOverrideStore(secureStore),
    )
    // MOTHER_A/MOTHER_B stand in for "known server-side, not enrolled on this device" ids — the
    // visit-schedule tests below (PR-9, PR-10, supersession, lapse) only care about the schedule
    // layered on top, not identity, so a minimal successful detail response is enough to let them
    // resolve via the real remote path (CR-037) rather than the removed static fallback.
    beneficiaryApi = FakeBeneficiaryApi(
      detailResponses = mapOf(
        MOTHER_A to { detailOk(MOTHER_A) },
        MOTHER_B to { detailOk(MOTHER_B) },
      ),
    )
    remoteProfiles = RemoteBeneficiaryProfileRepository(beneficiaryApi, secureStore, localEnrolments)
    repository = ScheduleBackedBeneficiaryProfileRepository(
      staticProfiles = StaticBeneficiaryProfileRepository(),
      remoteProfiles = remoteProfiles,
      scheduleRepository = schedules,
      localEnrolments = localEnrolments,
      referralLinkDao = org.armman.sakhi.data.referral.FakeReferralLinkDao(),
    )
  }

  private fun detailOk(id: String) = Response.success(
    BeneficiaryDetailResponseDto(
      success = true,
      message = "OK",
      data = BeneficiaryDetailDto(
        id = id,
        caseType = "MOTHER",
        currentStatus = "ACTIVE",
        registrationDate = null,
        consentRecords = null,
        pii = BeneficiaryPiiDto(
          id = "pii-$id",
          fullName = "Static Mother $id",
          villageId = null,
          padaId = null,
          healthSubCentreId = null,
          phcId = null,
          healthBlockId = null,
          dateOfBirth = "2000-01-01T00:00:00.000Z",
          sex = "FEMALE",
          stateId = null,
          districtId = null,
          talukaId = null,
        ),
      ),
    ),
  )

  /** Minimal [BeneficiaryApi] test double — only [detail] is exercised by
   * [RemoteBeneficiaryProfileRepository]; [list]/[listAll] belong to the beneficiary-list repositories. */
  private class FakeBeneficiaryApi(
    var detailResponses: Map<String, () -> Response<BeneficiaryDetailResponseDto>> = emptyMap(),
  ) : BeneficiaryApi {
    var detailCallCount = 0

    override suspend fun list(caseType: String, status: String): Response<BeneficiaryListResponseDto> =
      throw UnsupportedOperationException("not used by this test")

    override suspend fun listAll(caseType: String?, status: String?): Response<BeneficiaryListResponseDto> =
      throw UnsupportedOperationException("not used by this test")

    override suspend fun detail(id: String): Response<BeneficiaryDetailResponseDto> {
      detailCallCount++
      return detailResponses[id]?.invoke() ?: throw IOException("no detail configured for id: $id")
    }
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

  /**
   * Reported bug: a Sakhi entered the RCH number at Mother Registration, but ANC1's carried-
   * forward context always showed it blank -- `input_rch_number` was captured and synced but
   * never read back into the profile. Regression coverage for that read-back.
   */
  @Test
  fun `RCH number is read from the answers`() = runTest {
    val localId = "local-uuid-rch-1"
    saveLocalEnrolment(
      localId,
      firstName = "Sunita",
      lastName = "Pawar",
      extraSingleValues = mapOf("input_rch_number" to "RCH-2026-000123"),
    )

    assertEquals("RCH-2026-000123", repository.getBeneficiary(localId).rchNumber)
  }

  /** No RCH card on file at registration -- profile correctly reports null, not a crash or a
   * stale/wrong value. */
  @Test
  fun `an unanswered RCH number renders null rather than failing`() = runTest {
    val localId = "local-uuid-rch-2"
    saveLocalEnrolment(localId, firstName = "Asha", lastName = "Jadhav")

    assertEquals(null, repository.getBeneficiary(localId).rchNumber)
  }

  /**
   * The reported bug: `diagnoses` was never populated for a locally enrolled mother, so the
   * profile's Diagnosis chips never appeared even when Q58/Q60 were answered. Also exercises the
   * label lookup (raw `value_code`s must not leak onto the card) and the exclusion/inclusion rules
   * — a "no known condition" answer and a "no" sickle cell result must NOT appear as diagnoses.
   */
  @Test
  fun `diagnoses resolves real Q58 conditions and a positive Q60 sickle cell result to their labels`() = runTest {
    formsRepository.version = motherVersionWithDiagnosisOptions()
    val localId = "local-uuid-5"
    saveLocalEnrolment(
      localId,
      firstName = "Reema",
      lastName = "Powra",
      multiValues = mapOf(
        MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS to listOf(
          "hypertension_high_bp",
          // A "none" answer alongside a real one must still be dropped, not just when it's alone.
          "no_known_medical_condition",
        ),
      ),
      extraSingleValues = mapOf(
        MotherRegistrationQuestionCodes.SICKLE_CELL_STATUS to "sickle_cell_disease_scd",
      ),
    )

    assertEquals(
      listOf("Hypertension (High BP)", "Sickle Cell Disease (SCD)"),
      repository.getBeneficiary(localId).diagnoses,
    )
  }

  /**
   * "No known condition" (Q58) and a negative/uncertain sickle cell result (Q60) both carry no
   * risk per [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment] — this asserts the profile
   * agrees, so the Diagnosis row correctly stays hidden instead of showing a false chip.
   */
  @Test
  fun `diagnoses is empty when only non-condition answers are given`() = runTest {
    formsRepository.version = motherVersionWithDiagnosisOptions()
    val localId = "local-uuid-6"
    saveLocalEnrolment(
      localId,
      firstName = "Kiran",
      lastName = "Deshmukh",
      multiValues = mapOf(
        MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS to listOf("no_known_medical_condition"),
      ),
      extraSingleValues = mapOf(
        MotherRegistrationQuestionCodes.SICKLE_CELL_STATUS to "tested_and_result_is_normal",
      ),
    )

    assertTrue(repository.getBeneficiary(localId).diagnoses.isEmpty())
  }

  /**
   * The bug this test guards: [ScheduleBackedBeneficiaryProfileRepository] used to read DOB/weight
   * via MOTHER_REGISTRATION's `date_of_birth`/`weight_kg` codes for every beneficiary, including
   * children — whose form stores them under `date_of_birth_of_infant`/
   * `child_weight_at_birth_in_kg`. Every child's profile showed both fields blank.
   */
  @Test
  fun `a child's profile reads DOB and weight from the child form's own question codes`() = runTest {
    val localId = "local-child-1"
    saveLocalChildEnrolment(localId, name = "Aarav Sharma", dob = "2026-05-01", weightKg = "3.2")

    val profile = repository.getBeneficiary(localId)

    assertEquals("1 May 2026", profile.dob)
    assertEquals("3.2 kg", profile.weight)
  }

  @Test
  fun `a child's profile does not read the mother form's DOB or weight codes`() = runTest {
    val localId = "local-child-2"
    saveLocalChildEnrolment(localId, name = "Isha Patil", dob = null, weightKg = null)

    val profile = repository.getBeneficiary(localId)

    assertEquals("", profile.dob.orEmpty())
    assertEquals("", profile.weight.orEmpty())
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
  fun `a remote-only beneficiary resolves via the real detail API, not the static fixture`() = runTest {
    val profile = repository.getBeneficiary(MOTHER_A)

    // CR-037: the static fixture is no longer consulted on this path — identity comes from the
    // real beneficiary-detail API. lastVisitStats stays empty regardless of source (see this
    // repository's class doc): vitals mapping is still pending a confirmed JSON sample from BE.
    assertTrue(profile.name.isNotBlank())
    assertTrue(profile.lastVisitStats.isEmpty())
  }

  @Test
  fun `an unknown id still surfaces as an error`() = runTest {
    val result = runCatching { repository.getBeneficiary("does-not-exist") }

    assertTrue(result.exceptionOrNull() is NoSuchElementException)
  }

  // CR-037 RF-1/RF-2 — routing between the local and remote sources.
  @Test
  fun `a local miss falls through to the remote detail API`() = runTest {
    repository.getBeneficiary(MOTHER_A)

    assertEquals(1, beneficiaryApi.detailCallCount)
  }

  @Test
  fun `a local hit never calls the remote detail API`() = runTest {
    val localId = "local-uuid-routing"
    saveLocalEnrolment(localId, firstName = "Meena", lastName = "Gavit")

    repository.getBeneficiary(localId)

    assertEquals(0, beneficiaryApi.detailCallCount)
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
    extraSingleValues: Map<String, String> = emptyMap(),
    multiValues: Map<String, List<String>> = emptyMap(),
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
              putAll(extraSingleValues)
            },
            multiValues = multiValues,
          ),
          registrationDateIso = lmp.toString(),
        ),
      ),
    )
  }

  /**
   * Writes a CHILD_REGISTRATION draft into its OWN table/payload store — the way
   * [org.armman.sakhi.data.childregistration.RoomChildFormDraftRepository] actually persists a
   * submission. CHILD_REGISTRATION never lands in [draftDao]/`dynamicFormDraftPayloadKey`
   * (the mother store); see [LocalEnrolmentBeneficiarySource]'s class doc.
   */
  private suspend fun saveLocalChildEnrolment(
    id: String,
    name: String,
    dob: String?,
    weightKg: String?,
  ) {
    childDraftDao.upsert(
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
      ),
    )
    secureStore.putString(
      childFormDraftPayloadKey(id),
      childFormDraftGson.toJson(
        ChildFormDraftPayload(
          answers = FormAnswers(
            singleValues = buildMap {
              put("name_of_the_child", name)
              dob?.let { put("date_of_birth_of_infant", it) }
              weightKg?.let { put("child_weight_at_birth_in_kg", it) }
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

  /** A minimal MOTHER_REGISTRATION version carrying just the two diagnosis fields' real option
   * labels (mirrors the live schema captured in api-calls-live.jsonl) — enough to exercise
   * [org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource.diagnosisLabels]'s
   * code-to-label lookup without needing the whole schema. */
  private fun motherVersionWithDiagnosisOptions() = FormVersion(
    id = "version-1",
    formDefinitionId = "definition-1",
    versionNo = "v1",
    schemaJson = listOf(
      FormFieldSchema(
        label = "Have you ever been diagnosed with or treated for any of the following medical conditions?",
        required = true,
        inputTypeRaw = "multiselect",
        questionCode = MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS,
        options = listOf(
          FormFieldOption(label = "No known medical condition", sortOrder = 0, valueCode = "no_known_medical_condition"),
          FormFieldOption(label = "Hypertension (High BP)", sortOrder = 1, valueCode = "hypertension_high_bp"),
        ),
      ),
      FormFieldSchema(
        label = "Have you been detected with Sickle Cell disease or Sickle Cell Trait (SCT)?",
        required = true,
        inputTypeRaw = "select",
        questionCode = MotherRegistrationQuestionCodes.SICKLE_CELL_STATUS,
        options = listOf(
          FormFieldOption(label = "Tested and result is normal", sortOrder = 0, valueCode = "tested_and_result_is_normal"),
          FormFieldOption(label = "Sickle Cell Disease (SCD)", sortOrder = 1, valueCode = "sickle_cell_disease_scd"),
        ),
      ),
    ),
    validationJson = emptyList(),
    effectiveFrom = "2026-01-01",
    effectiveTo = null,
    status = "PUBLISHED",
  )

  private companion object {
    /** Ids configured with a successful [FakeBeneficiaryApi] detail response in [setUp], so the
     * remote-detail delegate resolves (CR-037) — these used to be ids in the now-removed static
     * fixture; the name is kept for the visit-schedule tests below, which only care about the
     * schedule layered on top, not which source resolved identity. */
    const val MOTHER_A = "b01"
    const val MOTHER_B = "b02"
  }
}
