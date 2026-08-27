package org.armman.sakhi.data.referral

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class RemoteReferralRepositoryTest {

  private class FakeReferralApi(
    var response: (() -> Response<ReferralFollowUpListResponseDto>)? = null,
  ) : ReferralApi {
    override suspend fun getPendingFollowUps(sakhiId: String): Response<ReferralFollowUpListResponseDto> {
      assertEquals("sakhi-1", sakhiId)
      return response?.invoke() ?: throw IOException("offline")
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

  private fun repo(
    api: ReferralApi,
    store: FakeSecureKeyValueStore = FakeSecureKeyValueStore(),
  ): RemoteReferralRepository {
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session())
    return RemoteReferralRepository(api, sessionStore, store)
  }

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
}
