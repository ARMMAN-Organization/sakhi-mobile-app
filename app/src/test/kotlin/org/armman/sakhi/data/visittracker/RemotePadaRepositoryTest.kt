package org.armman.sakhi.data.visittracker

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

class RemotePadaRepositoryTest {

  private class FakePadaApi(var response: (() -> Response<PadaListResponseDto>)? = null) : PadaApi {
    override suspend fun getPadas(sakhiId: String): Response<PadaListResponseDto> {
      assertEquals("sakhi-1", sakhiId)
      return response?.invoke() ?: throw IOException("offline")
    }
  }

  private fun bucket(
    women: Int? = 3,
    womenOverdue: Int? = 1,
    child: Int? = 3,
    childOverdue: Int? = 0,
  ) = PadaVisitBucketDto(women, womenOverdue, child, childOverdue)

  private fun pada(
    padaId: String? = "pada-1",
    padaName: String? = "Test Pada",
    villageName: String? = "Test Village",
    open: PadaVisitBucketDto? = bucket(),
    referralFollowUp: PadaVisitBucketDto? = bucket(women = 3, womenOverdue = 0, child = 3, childOverdue = 0),
    visitsRemaining: Int? = 2,
  ) = PadaDto(padaId, padaName, villageName, open, referralFollowUp, visitsRemaining)

  private fun ok(vararg padas: PadaDto) = Response.success(
    PadaListResponseDto(success = true, message = "OK", data = PadaListDataDto(padas = padas.toList())),
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

  private fun repo(api: PadaApi, store: FakeSecureKeyValueStore = FakeSecureKeyValueStore()): RemotePadaRepository {
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session())
    return RemotePadaRepository(api, sessionStore, store)
  }

  @Test
  fun `fetch success maps padaId padaName villageName and both buckets`() = runTest {
    val result = repo(FakePadaApi(response = { ok(pada(), pada(padaId = "pada-2", padaName = "Ambewadi Pada")) }))
      .getPadaSummaries()

    assertEquals(2, result.size)
    assertEquals("pada-1", result[0].padaId)
    assertEquals("Test Pada", result[0].padaName)
    assertEquals("Test Village", result[0].villageName)
    assertEquals(2, result[0].visitsRemainingCount)
    assertEquals(3, result[0].open.womenCount)
    assertEquals(1, result[0].open.womenOverdueCount)
    assertEquals(3, result[0].open.childCount)
    assertEquals(0, result[0].open.childOverdueCount)
    assertEquals(3, result[0].referralFollowUp.womenCount)
    assertEquals(0, result[0].referralFollowUp.womenOverdueCount)
  }

  @Test
  fun `a null bucket maps to all-zero counts, not a dropped row`() = runTest {
    val result = repo(FakePadaApi(response = { ok(pada(open = null, referralFollowUp = null, visitsRemaining = null)) }))
      .getPadaSummaries()

    val summary = result.single()
    assertEquals(0, summary.open.womenCount)
    assertEquals(0, summary.open.womenOverdueCount)
    assertEquals(0, summary.open.childCount)
    assertEquals(0, summary.open.childOverdueCount)
    assertEquals(0, summary.referralFollowUp.womenCount)
    assertEquals(0, summary.visitsRemainingCount)
  }

  @Test
  fun `empty padas array maps to empty list, not an error`() = runTest {
    val result = repo(FakePadaApi(response = { ok() })).getPadaSummaries()
    assertEquals(emptyList<PadaSummary>(), result)
  }

  @Test
  fun `a pada missing its id is dropped`() = runTest {
    val result = repo(FakePadaApi(response = { ok(pada(padaId = null)) })).getPadaSummaries()
    assertEquals(emptyList<PadaSummary>(), result)
  }

  @Test
  fun `network failure falls back to cached padas`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakePadaApi(response = { ok(pada(padaName = "Cached Pada")) }), store).getPadaSummaries()

    val offline = repo(FakePadaApi(response = null), store)
    assertEquals("Cached Pada", offline.getPadaSummaries().single().padaName)
  }

  @Test
  fun `no cache and fetch fails throws so the caller's existing hasError path is used`() {
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(FakePadaApi(response = null)).getPadaSummaries() }
    }
  }

  @Test
  fun `a non-2xx response is treated as a failure`() {
    val api = FakePadaApi(response = { Response.error(500, "boom".toResponseBody("application/json".toMediaType())) })
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(api).getPadaSummaries() }
    }
  }
}
