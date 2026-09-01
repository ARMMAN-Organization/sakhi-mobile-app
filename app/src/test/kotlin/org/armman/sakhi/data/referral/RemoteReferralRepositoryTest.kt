package org.armman.sakhi.data.referral

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.lookup.LookupValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class RemoteReferralRepositoryTest {

  private val s3Server = MockWebServer().apply { start() }

  @After
  fun tearDown() {
    s3Server.shutdown()
  }

  private class FakeReferralApi(
    var response: (() -> Response<ReferralFollowUpListResponseDto>)? = null,
    var createResponse: (() -> Response<CreateReferralResponseDto>)? = null,
    var followUpResponse: (() -> Response<SubmitReferralFollowUpResponseDto>)? = null,
    var convertResponse: (() -> Response<CreateReferralResponseDto>)? = null,
    var uploadUrlResponse: (() -> Response<MediaUploadUrlResponseDto>)? = null,
    var finalizeResponse: (() -> Response<FinalizeMediaResponseDto>)? = null,
  ) : ReferralApi {
    var lastCreateRequest: CreateReferralRequestDto? = null
    var lastFollowUpRequest: SubmitReferralFollowUpRequestDto? = null
    var lastUploadUrlRequest: RequestMediaUploadUrlDto? = null
    var lastFinalizeRequest: FinalizeMediaRequestDto? = null

    override suspend fun getPendingFollowUps(sakhiId: String): Response<ReferralFollowUpListResponseDto> {
      assertEquals("sakhi-1", sakhiId)
      return response?.invoke() ?: throw IOException("offline")
    }

    override suspend fun createReferral(request: CreateReferralRequestDto): Response<CreateReferralResponseDto> {
      lastCreateRequest = request
      return createResponse?.invoke() ?: throw IOException("offline")
    }

    override suspend fun submitFollowUp(
      referralId: String,
      request: SubmitReferralFollowUpRequestDto,
    ): Response<SubmitReferralFollowUpResponseDto> {
      lastFollowUpRequest = request
      return followUpResponse?.invoke() ?: throw IOException("offline")
    }

    override suspend fun convertToAccompanied(referralId: String): Response<CreateReferralResponseDto> =
      convertResponse?.invoke() ?: throw IOException("offline")

    override suspend fun requestMediaUploadUrl(request: RequestMediaUploadUrlDto): Response<MediaUploadUrlResponseDto> {
      lastUploadUrlRequest = request
      return uploadUrlResponse?.invoke() ?: throw IOException("offline")
    }

    override suspend fun finalizeMedia(request: FinalizeMediaRequestDto): Response<FinalizeMediaResponseDto> {
      lastFinalizeRequest = request
      return finalizeResponse?.invoke() ?: throw IOException("offline")
    }
  }

  private fun item(
    referralId: String? = "referral-1",
    beneficiaryId: String? = "ben-1",
    beneficiaryName: String? = "go rules a",
    referralDate: String? = "2026-08-10",
    followUpDueDate: String? = "2026-08-14",
    daysRemaining: Int? = 0,
    status: String? = "PENDING_FOLLOWUP",
  ) = ReferralFollowUpDto(referralId, beneficiaryId, beneficiaryName, referralDate, followUpDueDate, daysRemaining, status)

  private fun ok(vararg items: ReferralFollowUpDto) = Response.success(
    ReferralFollowUpListResponseDto(
      success = true,
      message = "OK",
      data = ReferralFollowUpListDataDto(pendingFollowUpCount = items.size, items = items.toList()),
    ),
  )

  private fun session() = UserSession(
    username = "sakhi1",
    subjectId = "sakhi-1",
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "token",
    refreshToken = "refresh",
    accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE,
  )

  private fun lookupRepository() = FakeLookupRepository(
    valuesByCategory = mutableMapOf(
      "REFERRAL_TYPE" to listOf(
        LookupValue(id = "lookup-standard-1", valueCode = "STANDARD", valueLabel = "Standard"),
        LookupValue(id = "lookup-accompanied-1", valueCode = "ACCOMPANIED", valueLabel = "Accompanied"),
      ),
    ),
  )

  private fun repo(
    api: ReferralApi,
    store: FakeSecureKeyValueStore = FakeSecureKeyValueStore(),
    lookupRepository: FakeLookupRepository = lookupRepository(),
  ): RemoteReferralRepository {
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session())
    return RemoteReferralRepository(api, sessionStore, store, lookupRepository, OkHttpClient(), FakeReferralLinkDao())
  }

  private fun capture(
    referralType: ReferralType = ReferralType.STANDARD,
    facilityName: String = "Civil Hospital",
    facilityType: String = "phc",
  ) = ReferralCapture(
    referralType = referralType,
    facilityName = facilityName,
    facilityType = facilityType,
    referralDate = LocalDate.of(2026, 8, 27),
  )

  private fun referralDataDto(
    id: String? = "ref-1",
    visitId: String? = "visit-1",
    sourceSubmissionId: String? = "sub-1",
    beneficiaryId: String? = "ben-1",
    referralTypeLookupValueId: String? = "lookup-standard-1",
    status: String? = "PENDING_FOLLOWUP",
    facilityType: String? = "PHC",
    facilityName: String? = "Civil Hospital",
    triggerConditionListJson: List<String>? = listOf("cond-1"),
    validTill: String? = "2026-09-03T00:00:00.000Z",
  ) = ReferralDataDto(
    id = id,
    beneficiaryId = beneficiaryId,
    visitId = visitId,
    sourceSubmissionId = sourceSubmissionId,
    referralTypeLookupValueId = referralTypeLookupValueId,
    referralDate = "2026-08-27T00:00:00.000Z",
    triggerConditionListJson = triggerConditionListJson,
    facilityType = facilityType,
    facilityName = facilityName,
    status = status,
    validTill = validTill,
    createdAt = "2026-08-27T06:19:19.251Z",
  )

  @Test
  fun `fetch success maps all fields per item`() = runTest {
    val result = repo(FakeReferralApi(response = { ok(item()) })).getPendingFollowUps()
    val followUp = result.single()

    assertEquals("referral-1", followUp.referralId)
    assertEquals("ben-1", followUp.beneficiaryId)
    assertEquals("go rules a", followUp.beneficiaryName)
    assertEquals(LocalDate.of(2026, 8, 10), followUp.referralDate)
    assertEquals(LocalDate.of(2026, 8, 14), followUp.followUpDueDate)
    assertEquals(0, followUp.daysRemaining)
    assertEquals(ReferralFollowUpStatus.PENDING_FOLLOWUP, followUp.status)
  }

  @Test
  fun `empty items array maps to empty list`() = runTest {
    val result = repo(FakeReferralApi(response = { ok() })).getPendingFollowUps()
    assertEquals(emptyList<ReferralFollowUp>(), result)
  }

  @Test
  fun `unrecognized status string maps to UNKNOWN, not a crash`() = runTest {
    val result = repo(FakeReferralApi(response = { ok(item(status = "ESCALATED")) })).getPendingFollowUps()
    assertEquals(ReferralFollowUpStatus.UNKNOWN, result.single().status)
  }

  @Test
  fun `daysRemaining of zero or negative is preserved, not clamped`() = runTest {
    val result = repo(FakeReferralApi(response = { ok(item(daysRemaining = -3)) })).getPendingFollowUps()
    assertEquals(-3, result.single().daysRemaining)
  }

  @Test
  fun `an entry missing referralId or beneficiaryId is dropped`() = runTest {
    val result = repo(
      FakeReferralApi(response = { ok(item(referralId = null), item(beneficiaryId = null)) }),
    ).getPendingFollowUps()
    assertEquals(emptyList<ReferralFollowUp>(), result)
  }

  @Test
  fun `network failure falls back to cache`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeReferralApi(response = { ok(item(beneficiaryName = "Cached Name")) }), store).getPendingFollowUps()

    val offline = repo(FakeReferralApi(response = null), store)
    assertEquals("Cached Name", offline.getPendingFollowUps().single().beneficiaryName)
  }

  @Test
  fun `no cache and fetch fails throws`() {
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(FakeReferralApi(response = null)).getPendingFollowUps() }
    }
  }

  @Test
  fun `a non-2xx response is treated as a failure`() {
    val api = FakeReferralApi(
      response = { Response.error(500, "boom".toResponseBody("application/json".toMediaType())) },
    )
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(api).getPendingFollowUps() }
    }
  }

  // --- createReferral (CR-Referral-01, corrected 2026-08-27 against the live contract) ---

  @Test
  fun `createReferral resolves referralType through the REFERRAL_TYPE lookup, never a hardcoded id`() = runTest {
    val api = FakeReferralApi(createResponse = { Response.success(CreateReferralResponseDto(true, null, referralDataDto())) })
    repo(api).createReferral(
      visitId = "visit-1",
      beneficiaryId = "ben-1",
      sourceSubmissionId = "sub-1",
      capture = capture(referralType = ReferralType.ACCOMPANIED),
      triggeringConditionIds = listOf("cond-1"),
    )

    assertEquals("lookup-accompanied-1", api.lastCreateRequest?.referralTypeLookupValueId)
    // CR-Referral-01 (2026-08-31): facilityType is now sent raw and verbatim from
    // ReferralCapture.facilityType (the schema's place_of_referral value_code, e.g. "phc") —
    // no enum, no casing transform, unlike referralType which still goes through a lookup.
    assertEquals("phc", api.lastCreateRequest?.facilityType)
    // status is always sent explicitly — required by the backend, no server default.
    assertEquals("PENDING_FOLLOWUP", api.lastCreateRequest?.status)
    // triggerConditionListJson is a real array in the request, not a JSON-encoded string.
    assertEquals(listOf("cond-1"), api.lastCreateRequest?.triggerConditionListJson)
  }

  @Test
  fun `createReferral 201 maps to Created`() = runTest {
    val api = FakeReferralApi(
      createResponse = { Response.success(201, CreateReferralResponseDto(true, null, referralDataDto(id = "ref-1"))) },
    )
    val outcome = repo(api).createReferral(
      visitId = "visit-1",
      beneficiaryId = "ben-1",
      sourceSubmissionId = "sub-1",
      capture = capture(),
      triggeringConditionIds = listOf("cond-1"),
    ).getOrThrow()

    assertTrue(outcome is CreateReferralOutcome.Created)
    assertEquals("ref-1", (outcome as CreateReferralOutcome.Created).referral.referralId)
  }

  @Test
  fun `createReferral 200 maps to AlreadyExists with the existing referral, not 409`() = runTest {
    val api = FakeReferralApi(
      createResponse = { Response.success(200, CreateReferralResponseDto(true, null, referralDataDto(id = "ref-existing"))) },
    )
    val outcome = repo(api).createReferral(
      visitId = "visit-1",
      beneficiaryId = "ben-1",
      sourceSubmissionId = "sub-1",
      capture = capture(),
      triggeringConditionIds = listOf("cond-1"),
    ).getOrThrow()

    assertTrue(outcome is CreateReferralOutcome.AlreadyExists)
    assertEquals("ref-existing", (outcome as CreateReferralOutcome.AlreadyExists).referral.referralId)
  }

  @Test
  fun `createReferral 500 surfaces as a Result failure with the backend message`() = runTest {
    val api = FakeReferralApi(
      createResponse = { Response.error(500, "{\"message\":\"Internal error\"}".toResponseBody("application/json".toMediaType())) },
    )
    val result = repo(api).createReferral(
      visitId = "visit-1",
      beneficiaryId = "ben-1",
      sourceSubmissionId = "sub-1",
      capture = capture(),
      triggeringConditionIds = listOf("cond-1"),
    )

    assertTrue(result.isFailure)
    assertEquals("Internal error", result.exceptionOrNull()?.message)
  }

  @Test
  fun `createReferral with no matching lookup value surfaces as a Result failure`() = runTest {
    val api = FakeReferralApi(createResponse = { Response.success(CreateReferralResponseDto(true, null, referralDataDto())) })
    val emptyLookups = FakeLookupRepository(valuesByCategory = mutableMapOf())
    val result = repo(api, lookupRepository = emptyLookups).createReferral(
      visitId = "visit-1",
      beneficiaryId = "ben-1",
      sourceSubmissionId = "sub-1",
      capture = capture(),
      triggeringConditionIds = listOf("cond-1"),
    )
    assertTrue(result.isFailure)
  }

  // --- submitFollowUp ---

  @Test
  fun `submitFollowUp with visitedFacilityFlag true maps to COMPLETED for both followup and referral`() = runTest {
    val api = FakeReferralApi(
      followUpResponse = {
        Response.success(
          SubmitReferralFollowUpResponseDto(
            success = true,
            message = "OK",
            data = SubmitReferralFollowUpDataDto(
              followup = ReferralFollowUpDataDto(
                id = "followup-1",
                referralId = "ref-1",
                visitedFacilityFlag = true,
                notVisitedReason = null,
                diagnosis = null,
                treatmentGiven = "Iron supplements prescribed",
                outcome = "Improving",
                followupStatus = "COMPLETED",
              ),
              referral = referralDataDto(id = "ref-1", status = "COMPLETED"),
            ),
          ),
        )
      },
    )
    val result = repo(api).submitFollowUp(
      referralId = "ref-1",
      visitedFacilityFlag = true,
      followupDate = LocalDate.of(2026, 8, 29),
      treatmentGiven = "Iron supplements prescribed",
      outcome = "Improving",
    ).getOrThrow()

    assertEquals(ReferralFollowUpOutcomeStatus.COMPLETED, result.followUp.followupStatus)
    assertEquals(ReferralStatus.COMPLETED, result.referral.status)
    assertEquals(true, api.lastFollowUpRequest?.visitedFacilityFlag)
  }

  @Test
  fun `submitFollowUp with visitedFacilityFlag false maps to INCOMPLETE, referral stays PENDING_FOLLOWUP`() = runTest {
    val api = FakeReferralApi(
      followUpResponse = {
        Response.success(
          SubmitReferralFollowUpResponseDto(
            success = true,
            message = "OK",
            data = SubmitReferralFollowUpDataDto(
              followup = ReferralFollowUpDataDto(
                id = "followup-2",
                referralId = "ref-1",
                visitedFacilityFlag = false,
                notVisitedReason = "Beneficiary was unavailable at home",
                diagnosis = null,
                treatmentGiven = null,
                outcome = null,
                followupStatus = "INCOMPLETE",
              ),
              // Confirmed live: the parent referral does NOT move to a terminal state here — a
              // Supervisor decides next via a separate, already-built endpoint out of this app's
              // scope.
              referral = referralDataDto(id = "ref-1", status = "PENDING_FOLLOWUP"),
            ),
          ),
        )
      },
    )
    val result = repo(api).submitFollowUp(
      referralId = "ref-1",
      visitedFacilityFlag = false,
      followupDate = LocalDate.of(2026, 8, 29),
      notVisitedReason = "Beneficiary was unavailable at home",
    ).getOrThrow()

    assertEquals(ReferralFollowUpOutcomeStatus.INCOMPLETE, result.followUp.followupStatus)
    assertEquals(ReferralStatus.PENDING_FOLLOWUP, result.referral.status)
    // Free text, not translated to/from a lookup code — exact echo.
    assertEquals("Beneficiary was unavailable at home", result.followUp.notVisitedReason)
  }

  // --- convertToAccompanied ---

  @Test
  fun `convertToAccompanied success keeps validTill unchanged — no extension`() = runTest {
    val api = FakeReferralApi(
      convertResponse = {
        Response.success(
          CreateReferralResponseDto(
            success = true,
            message = "OK",
            data = referralDataDto(
              id = "ref-1",
              referralTypeLookupValueId = "lookup-accompanied-1",
              status = "PENDING_FOLLOWUP",
              validTill = "2026-09-03T00:00:00.000Z",
            ),
          ),
        )
      },
    )
    val referral = repo(api).convertToAccompanied("ref-1").getOrThrow()

    assertEquals("lookup-accompanied-1", referral.referralTypeLookupValueId)
    assertEquals("2026-09-03T00:00:00.000Z", referral.validTill)
  }

  @Test
  fun `convertToAccompanied 409 (already Accompanied) surfaces as a Result failure`() = runTest {
    val api = FakeReferralApi(
      convertResponse = {
        Response.error(
          409,
          "{\"success\":false,\"message\":\"This referral is already Accompanied.\",\"errorCode\":\"CONFLICT\"}"
            .toResponseBody("application/json".toMediaType()),
        )
      },
    )
    val result = repo(api).convertToAccompanied("ref-1")

    assertTrue(result.isFailure)
    assertEquals("This referral is already Accompanied.", result.exceptionOrNull()?.message)
  }

  // CR-Referral-02 — uploadEvidence's real, backend-confirmed 3-step presigned-URL contract
  // (2026-08-31). Step 2 (the raw PUT) goes to s3Server, a MockWebServer standing in for S3 —
  // same pattern AuthInterceptorTest uses for a real OkHttp round trip.

  @Test
  fun `uploadEvidence success runs all 3 steps and returns the server media id`() = runTest {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
    s3Server.enqueue(MockResponse().setResponseCode(200))
    val api = FakeReferralApi(
      uploadUrlResponse = {
        Response.success(
          MediaUploadUrlResponseDto(
            success = true,
            message = "OK",
            data = MediaUploadUrlDataDto(
              uploadUrl = s3Server.url("/media/referral_case_paper/abc").toString(),
              s3Key = "media/referral_case_paper/abc",
              expiresInSeconds = 900,
              maxSizeBytes = 26214400,
            ),
          ),
        )
      },
      finalizeResponse = {
        Response.success(
          201,
          FinalizeMediaResponseDto(
            success = true,
            message = "OK",
            data = MediaAssetDataDto(
              id = "media-1",
              assetType = "REFERRAL_CASE_PAPER",
              storageUri = "s3://bucket/media/referral_case_paper/abc",
              mimeType = "image/jpeg",
              sizeBytes = "3",
              uploadedByUserId = null,
              uploadedAt = "2026-08-31T00:00:00.000Z",
              encryptedFlag = true,
              createdAt = "2026-08-31T00:00:00.000Z",
            ),
          ),
        )
      },
    )

    val result = repo(api).uploadEvidence("ref-1", "followup-1", ReferralEvidenceType.REFERRAL_CASE_PAPER, tempFile)

    assertEquals("media-1", result.getOrThrow())
    assertEquals("REFERRAL_CASE_PAPER", api.lastUploadUrlRequest?.assetType)
    assertEquals(3L, api.lastUploadUrlRequest?.sizeBytes)
    assertEquals("media/referral_case_paper/abc", api.lastFinalizeRequest?.s3Key)
    assertEquals("ref-1", api.lastFinalizeRequest?.referralId)
    assertEquals("followup-1", api.lastFinalizeRequest?.followupId)
    val s3Request = s3Server.takeRequest()
    assertEquals("PUT", s3Request.method)
    tempFile.delete()
  }

  @Test
  fun `uploadEvidence Step 1 non-2xx surfaces as a Result failure with the backend message, never reaches S3`() = runTest {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val api = FakeReferralApi(
      uploadUrlResponse = {
        Response.error(
          400,
          "{\"success\":false,\"message\":\"sizeBytes must not exceed 26214400 bytes\",\"errorCode\":\"VALIDATION_ERROR\"}"
            .toResponseBody("application/json".toMediaType()),
        )
      },
    )

    val result = repo(api).uploadEvidence("ref-1", "followup-1", ReferralEvidenceType.REFERRAL_INVESTIGATION_REPORT, tempFile)

    assertTrue(result.isFailure)
    assertEquals("sizeBytes must not exceed 26214400 bytes", result.exceptionOrNull()?.message)
    assertEquals(0, s3Server.requestCount)
    tempFile.delete()
  }

  @Test
  fun `uploadEvidence Step 2 (S3 PUT) failure surfaces as a Result failure, never calls finalize`() = runTest {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
    s3Server.enqueue(MockResponse().setResponseCode(500))
    val api = FakeReferralApi(
      uploadUrlResponse = {
        Response.success(
          MediaUploadUrlResponseDto(
            success = true,
            message = "OK",
            data = MediaUploadUrlDataDto(
              uploadUrl = s3Server.url("/media/x").toString(),
              s3Key = "media/x",
              expiresInSeconds = 900,
              maxSizeBytes = 26214400,
            ),
          ),
        )
      },
    )

    val result = repo(api).uploadEvidence("ref-1", "followup-1", ReferralEvidenceType.REFERRAL_HEALTH_FACILITY_PHOTO, tempFile)

    assertTrue(result.isFailure)
    assertEquals(null, api.lastFinalizeRequest)
    tempFile.delete()
  }

  @Test
  fun `uploadEvidence Step 3 (finalize) non-2xx surfaces as a Result failure with the backend message`() = runTest {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
    s3Server.enqueue(MockResponse().setResponseCode(200))
    val api = FakeReferralApi(
      uploadUrlResponse = {
        Response.success(
          MediaUploadUrlResponseDto(
            success = true,
            message = "OK",
            data = MediaUploadUrlDataDto(
              uploadUrl = s3Server.url("/media/x").toString(),
              s3Key = "media/x",
              expiresInSeconds = 900,
              maxSizeBytes = 26214400,
            ),
          ),
        )
      },
      finalizeResponse = {
        Response.error(
          422,
          "{\"success\":false,\"message\":\"Uploaded file size does not match expectedSizeBytes.\",\"errorCode\":\"UNPROCESSABLE\"}"
            .toResponseBody("application/json".toMediaType()),
        )
      },
    )

    val result = repo(api).uploadEvidence("ref-1", "followup-1", ReferralEvidenceType.REFERRAL_DISCHARGE_SUMMARY, tempFile)

    assertTrue(result.isFailure)
    assertEquals("Uploaded file size does not match expectedSizeBytes.", result.exceptionOrNull()?.message)
    tempFile.delete()
  }

  @Test
  fun `uploadEvidence network failure surfaces as a Result failure`() = runTest {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val result = repo(FakeReferralApi()).uploadEvidence(
      "ref-1",
      "followup-1",
      ReferralEvidenceType.REFERRAL_SAKHI_BENEFICIARY_PHOTO,
      tempFile,
    )

    assertTrue(result.isFailure)
    tempFile.delete()
  }
}
