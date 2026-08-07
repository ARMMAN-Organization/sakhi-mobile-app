package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.enrollment.CreateBeneficiaryRequestDto
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseData
import org.armman.sakhi.data.enrollment.CreateBeneficiaryResponseDto
import org.armman.sakhi.data.enrollment.EnrollmentApi
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

/**
 * Covers the real CR-018 submission sequence end to end at the [DynamicFormSubmissionCoordinator]
 * level (the layer [DynamicFormSyncExecutor] drives): `POST /beneficiaries` then
 * `POST /forms/MOTHER_REGISTRATION/submissions`, using the exact request/response DTO shapes the
 * live backend expects/returns (`create-beneficiary.dto.ts`, `FormSubmissionApi`). The third of
 * "the three APIs" — `GET /forms/:formCode/active-version` — is covered separately in
 * [RemoteFormsRepositoryTest], since [DynamicFormSubmissionCoordinator] doesn't call it (the
 * ViewModel fetches the schema before any of this runs).
 */
class DynamicFormSubmissionCoordinatorTest {

  private class FakeEnrollmentApi : EnrollmentApi {
    var response: Response<CreateBeneficiaryResponseDto>? = null
    var lastRequest: CreateBeneficiaryRequestDto? = null
    var callCount = 0

    override suspend fun createBeneficiary(
      request: CreateBeneficiaryRequestDto,
    ): Response<CreateBeneficiaryResponseDto> {
      callCount++
      lastRequest = request
      return response!!
    }
  }

  private class FakeFormSubmissionApi : FormSubmissionApi {
    var response: Response<CreateSubmissionResponseDto>? = null
    var lastRequest: CreateSubmissionRequestDto? = null
    var callCount = 0

    override suspend fun createSubmission(
      formCode: String,
      request: CreateSubmissionRequestDto,
    ): Response<CreateSubmissionResponseDto> {
      callCount++
      lastRequest = request
      return response!!
    }
  }

  private lateinit var enrollmentApi: FakeEnrollmentApi
  private lateinit var formSubmissionApi: FakeFormSubmissionApi
  private lateinit var coordinator: DynamicFormSubmissionCoordinator

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
    enrollmentApi = FakeEnrollmentApi()
    formSubmissionApi = FakeFormSubmissionApi()
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session)
    val mapper = DynamicFormSubmissionMapper(sessionStore, FakeLookupRepository())
    coordinator = DynamicFormSubmissionCoordinator(enrollmentApi, formSubmissionApi, mapper)
  }

  /** Answers matching a real gravida invariant
   * (liveBirths + stillbirths + abortions = gravida - 1, the -1 being the current pregnancy)
   * so the happy path doesn't trip the backend's own `superRefine` — see
   * `create-beneficiary.dto.ts`'s `motherDetailsSchema`. */
  private fun consistentAnswers() = FormAnswers(
    singleValues = mapOf(
      "did_we_receive_consent" to "yes",
      "lmp_date" to "2026-05-01",
      "gravida_total_number_of_pregnancies" to "3",
      "para_number_of_births_after_24_weeks" to "1",
      "living_children" to "1",
      "abortions_pregnancy_losses_before_24_weeks" to "1",
      "still_births" to "0",
      "first_name" to "Test",
      "last_name" to "Mother",
      "mobile_number" to "9876543210",
      "date_of_birth" to "1996-01-01",
      "enter_the_beneficiary_address" to "Pada 4, Dhadgaon",
      "input_rch_number" to "RCH-TEST-0003",
      "registrtion_date" to "2026-07-20",
      "name_of_the_state" to "state-1",
      "name_of_district" to "district-1",
      "name_of_block_taluka" to "block-1",
      "name_of_the_revenue_village_grampanchayat" to "village-1",
      "beneficary_pada_name" to "pada-1",
      "beneficary_phc_name" to "phc-1",
      "name_of_sub_center" to "subcentre-1",
    ),
  )

  private fun successfulBeneficiaryResponse(id: String = "server-beneficiary-1") = Response.success(
    CreateBeneficiaryResponseDto(success = true, message = "OK", data = CreateBeneficiaryResponseData(id = id)),
  )

  private fun successfulSubmissionResponse(id: String = "server-sub-1") = Response.success(
    CreateSubmissionResponseDto(success = true, message = "OK", data = SubmissionResponseData(id = id)),
  )

  @Test
  fun `happy path calls both APIs in order with the server-returned beneficiary id`() = runTest {
    enrollmentApi.response = successfulBeneficiaryResponse(id = "server-beneficiary-42")
    formSubmissionApi.response = successfulSubmissionResponse()

    val result = coordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = consistentAnswers(),
      fallbackRegistrationDate = LocalDate.of(2026, 7, 20),
    )

    assertTrue(result.isSuccess)
    assertEquals(1, enrollmentApi.callCount)
    assertEquals(1, formSubmissionApi.callCount)

    // POST /beneficiaries got the mapped PII/case/motherDetails payload.
    val beneficiaryRequest = requireNotNull(enrollmentApi.lastRequest)
    assertEquals("Test Mother", beneficiaryRequest.pii.fullName)
    assertEquals("local-case-1", beneficiaryRequest.case.localCaseUuid)

    // POST /forms/MOTHER_REGISTRATION/submissions used the SERVER's beneficiary id, not the
    // local one — confirmed against FormSubmission.beneficiaryId's real FK target.
    val submissionRequest = requireNotNull(formSubmissionApi.lastRequest)
    assertEquals("server-beneficiary-42", submissionRequest.beneficiaryId)
    assertEquals("version-v6", submissionRequest.formVersionId)
    assertEquals("local-submission-1", submissionRequest.localSubmissionUuid)

    // formData must carry the FULL answer set (including beneficiary-DTO fields) or the backend
    // 422s them as "Missing required field", and beneficiary_id must be injected from the server
    // id since the Sakhi never answers it.
    val formData = submissionRequest.formData
    assertEquals("Test", formData["first_name"])
    assertEquals("1996-01-01", formData["date_of_birth"])
    assertEquals("2026-05-01", formData["lmp_date"])
    assertEquals("state-1", formData["name_of_the_state"])
    assertEquals("server-beneficiary-42", formData["beneficiary_id"])
  }

  @Test
  fun `mapping failure short-circuits before either API is called`() = runTest {
    val noSessionSessionStore = SessionStore(FakeSecureKeyValueStore()) // never saved a session
    val brokenCoordinator = DynamicFormSubmissionCoordinator(
      enrollmentApi,
      formSubmissionApi,
      DynamicFormSubmissionMapper(noSessionSessionStore, FakeLookupRepository()),
    )

    val result = brokenCoordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = consistentAnswers(),
      fallbackRegistrationDate = LocalDate.now(),
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DynamicFormSubmissionException.MappingFailed)
    assertEquals(0, enrollmentApi.callCount)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `real 400 validation error from beneficiaries is captured with code and body, submission never called`() = runTest {
    // Reproduces the exact response a real device hit: invalid geography uuids, missing
    // healthBlockId/rchNumber, unmet gravida invariant.
    val body = """
      {"success":false,"message":"pii.villageId: Invalid uuid; pii.healthBlockId: Required",
      "errorCode":"VALIDATION_ERROR","fieldErrors":{"pii.villageId":"Invalid uuid"}}
    """.trimIndent()
    enrollmentApi.response = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    val result = coordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = consistentAnswers(),
      fallbackRegistrationDate = LocalDate.now(),
    )

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull() as DynamicFormSubmissionException.BeneficiaryCreationFailed
    assertEquals(400, error.httpCode)
    assertTrue(error.body.orEmpty().contains("Invalid uuid"))
    // The parsed envelope is now carried on the exception for inline attribution downstream.
    assertEquals("VALIDATION_ERROR", error.errorCode)
    assertEquals("Invalid uuid", error.fieldErrors["pii.villageId"])
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `a 422 unprocessable from beneficiaries carries the message but no fieldErrors`() = runTest {
    // The real geography case: 422 with a plain message and no per-field map — must stay
    // banner-only (empty fieldErrors), never attributed to a field.
    val body = """
      {"success":false,"message":"pii.phcId does not refer to a known geography unit.",
      "errorCode":"UNPROCESSABLE","traceId":"abc123"}
    """.trimIndent()
    enrollmentApi.response = Response.error(422, body.toResponseBody("application/json".toMediaType()))

    val result = coordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = consistentAnswers(),
      fallbackRegistrationDate = LocalDate.now(),
    )

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull() as DynamicFormSubmissionException.BeneficiaryCreationFailed
    assertEquals(422, error.httpCode)
    assertEquals("UNPROCESSABLE", error.errorCode)
    assertTrue(error.fieldErrors.isEmpty())
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `beneficiary created with no id in the response body fails without calling submissions`() = runTest {
    enrollmentApi.response = Response.success(
      CreateBeneficiaryResponseDto(success = true, message = "OK", data = null),
    )

    val result = coordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = consistentAnswers(),
      fallbackRegistrationDate = LocalDate.now(),
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DynamicFormSubmissionException.NoBeneficiaryIdReturned)
    assertEquals(0, formSubmissionApi.callCount)
  }

  @Test
  fun `form submission failure is captured with code and body after a successful beneficiary create`() = runTest {
    enrollmentApi.response = successfulBeneficiaryResponse()
    formSubmissionApi.response = Response.error(
      422,
      "{\"message\":\"formVersionId not found\"}".toResponseBody("application/json".toMediaType()),
    )

    val result = coordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = consistentAnswers(),
      fallbackRegistrationDate = LocalDate.now(),
    )

    assertTrue(result.isFailure)
    val error = result.exceptionOrNull() as DynamicFormSubmissionException.FormSubmissionFailed
    assertEquals(422, error.httpCode)
    assertFalse(error.body.isNullOrBlank())
    // Beneficiary WAS created (first call succeeded) — only the second call failed.
    assertEquals(1, enrollmentApi.callCount)
    assertEquals(1, formSubmissionApi.callCount)
  }

  @Test
  fun `consent not given fails mapping before any API call`() = runTest {
    val answers = consistentAnswers().let {
      FormAnswers(singleValues = it.singleValues + ("did_we_receive_consent" to "no"))
    }

    val result = coordinator.submit(
      formVersionId = "version-v6",
      localCaseUuid = "local-case-1",
      localSubmissionUuid = "local-submission-1",
      answers = answers,
      fallbackRegistrationDate = LocalDate.now(),
    )

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is DynamicFormSubmissionException.MappingFailed)
    assertEquals(0, enrollmentApi.callCount)
    assertNull(formSubmissionApi.lastRequest)
  }
}
