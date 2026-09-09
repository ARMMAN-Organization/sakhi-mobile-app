package org.armman.sakhi.data.dashboard

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
import java.time.Instant

class RemoteDashboardRepositoryTest {

  private class FakeDashboardApi(
    var response: (() -> Response<DashboardResponseDto>)? = null,
    private val expectedSakhiId: String = "sakhi-1",
  ) : DashboardApi {
    var callCount = 0
    override suspend fun getDashboard(sakhiId: String): Response<DashboardResponseDto> {
      callCount++
      assertEquals(expectedSakhiId, sakhiId)
      return response?.invoke() ?: throw IOException("offline")
    }
  }

  private fun ok(
    sakhiName: String? = "Test Sakhi",
    lastSyncedAt: String? = "2026-08-14T09:12:41.000Z",
    totalActive: Int? = 96,
    mothersCount: Int? = 42,
    childrenCount: Int? = 54,
    mothersHighRisk: Int? = 5,
    childrenHighRisk: Int? = 2,
    mothersPercent: Double? = 43.75,
    childrenPercent: Double? = 56.25,
    accompaniedReferrals: Int? = 7,
    pendingFollowUps: Int? = 3,
    dueVisits: Int? = 15,
    overdueVisits: Int? = 4,
    endingSoonVisits: Int? = 3,
    sakhiId: String = "sakhi-1",
  ) = Response.success(
    DashboardResponseDto(
      success = true,
      message = "OK",
      data = DashboardDataDto(
        sakhi = DashboardSakhiDto(id = sakhiId, name = sakhiName),
        lastSyncedAt = lastSyncedAt,
        beneficiarySummary = BeneficiarySummaryDto(
          totalActiveBeneficiaries = totalActive,
          activeMothersCount = mothersCount,
          activeChildrenCount = childrenCount,
          activeMothersHighRiskCount = mothersHighRisk,
          activeChildrenHighRiskCount = childrenHighRisk,
          activeMothersPercent = mothersPercent,
          activeChildrenPercent = childrenPercent,
        ),
        referralSummary = ReferralSummaryDto(
          accompaniedReferralsCount = accompaniedReferrals,
          pendingFollowUpsCount = pendingFollowUps,
        ),
        visitSummary = VisitSummaryDto(
          dueVisitsCount = dueVisits,
          overdueVisitsCount = overdueVisits,
          endingSoonVisitsCount = endingSoonVisits,
        ),
        version = 1,
      ),
    ),
  )

  private fun session(username: String = "sakhi1", subjectId: String = "sakhi-1") = UserSession(
    username = username,
    subjectId = subjectId,
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "token",
    refreshToken = "refresh",
    accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE,
  )

  private fun repo(
    api: DashboardApi,
    store: FakeSecureKeyValueStore = FakeSecureKeyValueStore(),
    subjectId: String = "sakhi-1",
    username: String = "sakhi1",
  ): RemoteDashboardRepository {
    val sessionKvStore = FakeSecureKeyValueStore()
    val sessionStore = SessionStore(sessionKvStore)
    sessionStore.saveSession(session(username = username, subjectId = subjectId))
    return RemoteDashboardRepository(api, sessionStore, store)
  }

  @Test
  fun `fetch success maps every field from the confirmed payload`() = runTest {
    val summary = repo(FakeDashboardApi(response = { ok() })).getSummary()

    assertEquals("Test Sakhi", summary.sakhiName)
    assertEquals(Instant.parse("2026-08-14T09:12:41.000Z"), summary.lastSyncedAt)
    assertEquals(96, summary.totalActiveBeneficiaries)
    assertEquals(42, summary.activeMothersCount)
    assertEquals(54, summary.activeChildrenCount)
    assertEquals(5, summary.activeMothersHighRiskCount)
    assertEquals(2, summary.activeChildrenHighRiskCount)
    assertEquals(43.75, summary.activeMothersPercent, 0.0)
    assertEquals(56.25, summary.activeChildrenPercent, 0.0)
    assertEquals(7, summary.accompaniedReferralsCount)
    assertEquals(3, summary.pendingFollowUpsCount)
    assertEquals(15, summary.dueVisitsCount)
    assertEquals(4, summary.overdueVisitsCount)
    assertEquals(3, summary.endingSoonVisitsCount)
  }

  @Test
  fun `successful fetch persists to a cache key scoped by the session's subjectId`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeDashboardApi(response = { ok() }), store, subjectId = "sakhi-1").getSummary()

    assertEquals(true, store.getString("dashboard_summary_cache_sakhi-1") != null)
  }

  @Test
  fun `network failure falls back to last cached summary for the same Sakhi`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeDashboardApi(response = { ok(sakhiName = "Cached Sakhi") }), store).getSummary()

    val offline = repo(FakeDashboardApi(response = null), store)
    val summary = offline.getSummary()

    assertEquals("Cached Sakhi", summary.sakhiName)
  }

  @Test
  fun `a different Sakhi logging in on the same device never sees the prior Sakhi's cached name`() = runTest {
    // Regression test: the on-disk cache used to be one device-global key, so a device
    // previously used by "Meera" would still show her cached name on the dashboard after
    // "Meena" logged in on it, until Meena's first successful fetch completed.
    val store = FakeSecureKeyValueStore()
    repo(
      FakeDashboardApi(response = { ok(sakhiName = "Meera", sakhiId = "meera-id") }, expectedSakhiId = "meera-id"),
      store,
      subjectId = "meera-id",
      username = "meera.sakhi",
    ).getSummary()

    // Meena logs in on the same device (same underlying cache store), but her own fetch is
    // offline / not yet synced.
    val meenaOffline = repo(
      FakeDashboardApi(response = null, expectedSakhiId = "meena-id"),
      store,
      subjectId = "meena-id",
      username = "meena.sakhi",
    )

    assertThrows(NoDashboardCacheAvailableException::class.java) {
      kotlinx.coroutines.runBlocking { meenaOffline.getSummary() }
    }
  }

  @Test
  fun `no cache and fetch fails throws NoDashboardCacheAvailableException, distinct from a generic failure`() = runTest {
    assertThrows(NoDashboardCacheAvailableException::class.java) {
      kotlinx.coroutines.runBlocking { repo(FakeDashboardApi(response = null)).getSummary() }
    }
  }

  @Test
  fun `corrupted persisted cache falls through to NoDashboardCacheAvailableException, not a crash`() = runTest {
    val store = FakeSecureKeyValueStore()
    store.putRawCorrupted("dashboard_summary_cache_sakhi-1")

    assertThrows(NoDashboardCacheAvailableException::class.java) {
      kotlinx.coroutines.runBlocking { repo(FakeDashboardApi(response = null), store).getSummary() }
    }
  }

  @Test
  fun `malformed response body treated as failure, not a crash`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeDashboardApi(response = { ok(sakhiName = "Cached Sakhi") }), store).getSummary()

    // A response with no usable sakhi name maps to null in toDomain(), same fallback path.
    val degraded = repo(FakeDashboardApi(response = { ok(sakhiName = null) }), store)
    val summary = degraded.getSummary()

    assertEquals("Cached Sakhi", summary.sakhiName)
  }

  @Test
  fun `success=false envelope treated as failure`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeDashboardApi(response = { ok(sakhiName = "Cached Sakhi") }), store).getSummary()

    val api = FakeDashboardApi(
      response = {
        Response.success(DashboardResponseDto(success = false, message = "nope", data = null))
      },
    )
    val summary = repo(api, store).getSummary()

    assertEquals("Cached Sakhi", summary.sakhiName)
  }

  @Test
  fun `a non-2xx response is treated as a failure`() = runTest {
    val api = FakeDashboardApi(
      response = { Response.error(500, "boom".toResponseBody("application/json".toMediaType())) },
    )
    assertThrows(NoDashboardCacheAvailableException::class.java) {
      kotlinx.coroutines.runBlocking { repo(api).getSummary() }
    }
  }
}
