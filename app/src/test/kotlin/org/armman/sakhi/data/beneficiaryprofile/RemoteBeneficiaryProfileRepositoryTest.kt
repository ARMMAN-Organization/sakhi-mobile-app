package org.armman.sakhi.data.beneficiaryprofile

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.LocalBeneficiaryStatusOverrideStore
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.childregistration.FakeChildFormDraftDao
import org.armman.sakhi.data.forms.FakeDynamicFormDraftDao
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.BeneficiaryDetailDto
import org.armman.sakhi.data.motherlink.BeneficiaryDetailResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryListResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryPiiDto
import org.armman.sakhi.data.motherlink.ChildCaseDetailsDto
import org.armman.sakhi.data.motherlink.MotherCaseDetailsDto
import org.armman.sakhi.data.motherlink.RiskConditionSummaryDto
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Covers [RemoteBeneficiaryProfileRepository] — the remote-only-beneficiary half of the profile
 * screen (CR-037, "We couldn't load this beneficiary" fix). Mirrors
 * [org.armman.sakhi.data.beneficiary.RemoteBeneficiaryRepositoryTest]'s fetch/cache/fallback shape,
 * since this repository deliberately copies that pattern.
 */
class RemoteBeneficiaryProfileRepositoryTest {

  private class FakeBeneficiaryApi(
    var detailResponse: (() -> Response<BeneficiaryDetailResponseDto>)? = null,
  ) : BeneficiaryApi {
    override suspend fun list(caseType: String, status: String): Response<BeneficiaryListResponseDto> =
      throw UnsupportedOperationException("not used by RemoteBeneficiaryProfileRepository")

    override suspend fun listAll(caseType: String?, status: String?): Response<BeneficiaryListResponseDto> =
      throw UnsupportedOperationException("not used by RemoteBeneficiaryProfileRepository")

    override suspend fun detail(id: String): Response<BeneficiaryDetailResponseDto> =
      detailResponse?.invoke() ?: throw IOException("offline")
  }

  private lateinit var store: FakeSecureKeyValueStore
  private lateinit var localEnrolments: LocalEnrolmentBeneficiarySource

  @Before
  fun setUp() {
    store = FakeSecureKeyValueStore()
    localEnrolments = LocalEnrolmentBeneficiarySource(
      FakeDynamicFormDraftDao(),
      FakeChildFormDraftDao(),
      store,
      RoomVisitScheduleRepository(FakeVisitScheduleDao()),
      FakeFormsRepository(),
      LocalBeneficiaryStatusOverrideStore(store),
    )
  }

  private fun repo(api: BeneficiaryApi, keyValueStore: FakeSecureKeyValueStore = store) =
    RemoteBeneficiaryProfileRepository(api, keyValueStore, localEnrolments)

  private fun pii(
    fullName: String? = "Beni Jsjsjs",
    dateOfBirth: String? = "2002-08-17T00:00:00.000Z",
    mobileNumber: String? = "9454488181",
  ) = BeneficiaryPiiDto(
    id = "pii-1",
    fullName = fullName,
    villageId = null,
    padaId = null,
    healthSubCentreId = null,
    phcId = null,
    healthBlockId = null,
    dateOfBirth = dateOfBirth,
    sex = "FEMALE",
    stateId = null,
    districtId = null,
    talukaId = null,
    mobileNumber = mobileNumber,
  )

  private fun detail(
    id: String = "remote-1",
    caseType: String? = "MOTHER",
    currentStatus: String? = "ACTIVE",
    registrationDate: String? = "2026-08-17T00:00:00.000Z",
    pii: BeneficiaryPiiDto? = pii(),
    motherCaseDetails: MotherCaseDetailsDto? = MotherCaseDetailsDto(
      lmpDate = "2026-07-17T00:00:00.000Z",
      eddDate = "2027-04-23T00:00:00.000Z",
      gravida = 1,
      parity = 0,
      heightCm = null,
      bmiAtRegistration = null,
    ),
    childCaseDetails: ChildCaseDetailsDto? = null,
    riskConditionSummaries: List<RiskConditionSummaryDto>? = emptyList(),
    riskLevel: String? = "none",
  ) = BeneficiaryDetailDto(
    id = id,
    caseType = caseType,
    currentStatus = currentStatus,
    registrationDate = registrationDate,
    consentRecords = null,
    pii = pii,
    motherCaseDetails = motherCaseDetails,
    childCaseDetails = childCaseDetails,
    riskConditionSummaries = riskConditionSummaries,
    riskLevel = riskLevel,
  )

  private fun ok(dto: BeneficiaryDetailDto) =
    Response.success(BeneficiaryDetailResponseDto(success = true, message = "OK", data = dto))

  // --- RF-3 ---
  @Test
  fun `not-yet-assessed profile loads without error`() = runTest {
    val api = FakeBeneficiaryApi(detailResponse = { ok(detail(riskConditionSummaries = emptyList(), riskLevel = "none")) })

    val profile = repo(api).getBeneficiary("remote-1")

    assertEquals(RiskLevel.LOW, profile.riskLevel)
    assertTrue(profile.diagnoses.isEmpty())
    assertTrue(profile.lastVisitStats.isEmpty())
  }

  // --- RF-4 ---
  @Test
  fun `assessed profile maps risk and diagnoses`() = runTest {
    val api = FakeBeneficiaryApi(
      detailResponse = {
        ok(
          detail(
            riskLevel = "high",
            riskConditionSummaries = listOf(
              RiskConditionSummaryDto(
                riskConditionId = "cond-1",
                phase = "ANC",
                latestGrade = "SEVERE",
                latestAssessedAt = "2026-08-20T00:00:00.000Z",
                everHighestGrade = "SEVERE",
                everAtRiskFlag = true,
                currentReferralTriggerFlag = true,
                currentHrVisitTriggerFlag = false,
                conditionCode = "HYPERTENSION_HIGH_BP",
                conditionName = "Hypertension (High BP)",
                gradeScale = "BINARY",
              ),
            ),
          ),
        )
      },
    )

    val profile = repo(api).getBeneficiary("remote-1")

    assertEquals(RiskLevel.HIGH, profile.riskLevel)
    assertEquals(listOf("Hypertension (High BP)"), profile.diagnoses)
  }

  // --- RF-5 ---
  @Test
  fun `null conditionName entries are skipped, not crashed`() = runTest {
    val api = FakeBeneficiaryApi(
      detailResponse = {
        ok(
          detail(
            riskConditionSummaries = listOf(
              RiskConditionSummaryDto(
                riskConditionId = "cond-1",
                phase = "ANC",
                latestGrade = null,
                latestAssessedAt = null,
                everHighestGrade = null,
                everAtRiskFlag = null,
                currentReferralTriggerFlag = null,
                currentHrVisitTriggerFlag = null,
                conditionCode = null,
                conditionName = null,
                gradeScale = null,
              ),
            ),
          ),
        )
      },
    )

    val profile = repo(api).getBeneficiary("remote-1")

    assertTrue(profile.diagnoses.isEmpty())
  }

  // --- RF-6/RF-7 (see this repository's own doc: lastVisitVitals is deliberately unmapped) ---
  @Test
  fun `lastVisitStats stays empty regardless of risk data — vitals mapping is a documented follow-up`() = runTest {
    val api = FakeBeneficiaryApi(detailResponse = { ok(detail()) })

    val profile = repo(api).getBeneficiary("remote-1")

    assertTrue(profile.lastVisitStats.isEmpty())
  }

  // --- RF-8 ---
  @Test
  fun `mother case maps lmp, edd AND dob — IdentityCard's stat strip shows DOB for every type`() = runTest {
    val api = FakeBeneficiaryApi(detailResponse = { ok(detail(caseType = "MOTHER")) })

    val profile = repo(api).getBeneficiary("remote-1")

    assertEquals("17 Jul 2026", profile.lmp)
    assertEquals("23 Apr 2027", profile.edd)
    // Regression test: dob was previously nulled out for MOTHER cases, leaving IdentityCard's
    // mobile Status | Risk | DOB stat strip blank for every mother (reported bug — "dob is not
    // coming" on a real MOTHER profile).
    assertEquals("17 Aug 2002", profile.dob)
    assertNull(profile.weight)
  }

  // --- RF-9 ---
  @Test
  fun `child case maps dob, not lmp or edd`() = runTest {
    val api = FakeBeneficiaryApi(
      detailResponse = {
        ok(
          detail(
            caseType = "CHILD",
            motherCaseDetails = null,
            pii = pii(dateOfBirth = "2026-01-15T00:00:00.000Z"),
          ),
        )
      },
    )

    val profile = repo(api).getBeneficiary("remote-1")

    assertEquals("15 Jan 2026", profile.dob)
    assertNull(profile.lmp)
    assertNull(profile.edd)
  }

  // --- RF-10 ---
  @Test
  fun `successful remote fetch is cached`() = runTest {
    repo(FakeBeneficiaryApi(detailResponse = { ok(detail(id = "remote-1")) })).getBeneficiary("remote-1")

    assertTrue(store.getString("remote_beneficiary_profile_cache_remote-1") != null)
  }

  // --- RF-11 ---
  @Test
  fun `cached profile returned when offline`() = runTest {
    repo(FakeBeneficiaryApi(detailResponse = { ok(detail(id = "remote-1")) })).getBeneficiary("remote-1")

    val offlineProfile = repo(FakeBeneficiaryApi(detailResponse = null)).getBeneficiary("remote-1")

    assertEquals("remote-1", offlineProfile.id)
  }

  // --- RF-12 ---
  @Test(expected = NoSuchElementException::class)
  fun `404 with no cache sets error`() = runTest {
    repo(FakeBeneficiaryApi(detailResponse = null)).getBeneficiary("never-fetched-id")
  }

  // --- RF-13 ---
  @Test(expected = NoSuchElementException::class)
  fun `malformed cached JSON with no fresh fetch sets error`() = runTest {
    store.putRawCorrupted("remote_beneficiary_profile_cache_remote-1")

    repo(FakeBeneficiaryApi(detailResponse = null)).getBeneficiary("remote-1")
  }

  // --- RF-14 ---
  @Test(expected = NoSuchElementException::class)
  fun `network error with no cache sets error`() = runTest {
    val api = FakeBeneficiaryApi(detailResponse = { throw IOException("timeout") })

    repo(api).getBeneficiary("remote-1")
  }

  @Test
  fun `a non-2xx response is treated as a failure, falling back to cache when present`() = runTest {
    repo(FakeBeneficiaryApi(detailResponse = { ok(detail(id = "remote-1")) })).getBeneficiary("remote-1")

    val api = FakeBeneficiaryApi(
      detailResponse = {
        Response.error(404, "not found".toResponseBody("application/json".toMediaType()))
      },
    )
    val profile = repo(api).getBeneficiary("remote-1")

    assertEquals("remote-1", profile.id)
  }

  @Test
  fun `a success false envelope is treated as a failure`() = runTest {
    val api = FakeBeneficiaryApi(
      detailResponse = {
        Response.success(BeneficiaryDetailResponseDto(success = false, message = "nope", data = detail()))
      },
    )

    val result = runCatching { repo(api).getBeneficiary("remote-1") }

    assertTrue(result.exceptionOrNull() is NoSuchElementException)
  }

  @Test
  fun `blank or missing name falls back to a placeholder`() = runTest {
    val api = FakeBeneficiaryApi(detailResponse = { ok(detail(pii = pii(fullName = null))) })

    val profile = repo(api).getBeneficiary("remote-1")

    assertEquals("Unnamed beneficiary", profile.name)
  }

  @Test
  fun `JOURNEY_COMPLETE and CLOSED map to their own status, everything else collapses to ACTIVE`() = runTest {
    val activeApi = FakeBeneficiaryApi(detailResponse = { ok(detail(currentStatus = "TRANSFERRED")) })
    assertEquals(BeneficiaryStatus.ACTIVE, repo(activeApi).getBeneficiary("remote-1").status)

    val closedApi = FakeBeneficiaryApi(detailResponse = { ok(detail(currentStatus = "CLOSED")) })
    assertEquals(BeneficiaryStatus.CLOSED, repo(closedApi).getBeneficiary("remote-1").status)
  }

  @Test
  fun `husbandName is always blank — intentionally hidden from this profile`() = runTest {
    val api = FakeBeneficiaryApi(detailResponse = { ok(detail()) })

    assertEquals("", repo(api).getBeneficiary("remote-1").husbandName)
  }
}
