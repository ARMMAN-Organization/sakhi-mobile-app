package org.armman.sakhi.data.beneficiary

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.UserSession
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.armman.sakhi.data.motherlink.BeneficiaryApi
import org.armman.sakhi.data.motherlink.BeneficiaryDetailResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryListItemDto
import org.armman.sakhi.data.motherlink.BeneficiaryListResponseDto
import org.armman.sakhi.data.motherlink.BeneficiaryPiiDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/**
 * Covers [RemoteBeneficiaryRepository] — the remote half of My Beneficiaries' offline-first merge
 * (Phase 2). Mirrors [org.armman.sakhi.data.motherlink.RemoteMotherLinkRepositoryTest]'s
 * fetch/cache/fallback shape, since this repository deliberately copies that pattern.
 */
class RemoteBeneficiaryRepositoryTest {

  private class FakeBeneficiaryApi(
    var listAllResponse: (() -> Response<BeneficiaryListResponseDto>)? = null,
  ) : BeneficiaryApi {
    var listAllCallCount = 0

    override suspend fun list(caseType: String, status: String): Response<BeneficiaryListResponseDto> =
      throw UnsupportedOperationException("not used by RemoteBeneficiaryRepository")

    override suspend fun listAll(caseType: String?, status: String?): Response<BeneficiaryListResponseDto> {
      listAllCallCount++
      // RemoteBeneficiaryRepository must ask for every case type / every status, not a narrow slice.
      assertNull(caseType)
      assertNull(status)
      return listAllResponse?.invoke() ?: throw IOException("offline")
    }

    override suspend fun detail(id: String): Response<BeneficiaryDetailResponseDto> =
      throw UnsupportedOperationException("not used by RemoteBeneficiaryRepository")
  }

  private fun pii(fullName: String? = "Deepa T Test") = BeneficiaryPiiDto(
    id = "pii-1",
    fullName = fullName,
    villageId = "village-1",
    padaId = "pada-1",
    healthSubCentreId = "sc-1",
    phcId = "phc-1",
    healthBlockId = "block-1",
    dateOfBirth = "2001-07-29T00:00:00.000Z",
    sex = "FEMALE",
    stateId = "state-1",
    districtId = "district-1",
    talukaId = "taluka-1",
  )

  private fun row(
    id: String? = "remote-1",
    caseType: String? = "MOTHER",
    currentStatus: String? = "ACTIVE",
    registrationDate: String? = "2026-07-28T00:00:00.000Z",
    pii: BeneficiaryPiiDto? = pii(),
    villageName: String? = null,
  ) = BeneficiaryListItemDto(
    id = id,
    caseType = caseType,
    currentStatus = currentStatus,
    currentPhase = "ANC",
    registrationDate = registrationDate,
    motherBeneficiaryId = null,
    pii = pii,
    villageName = villageName,
  )

  // BeneficiaryListResponseDto.data is a raw JsonElement (see its KDoc — the backend has shipped
  // both a bare array and an `{ "items": [...] }` wrapper); built via Gson.toJsonTree so this
  // exercises the same normalization real responses go through.
  private fun ok(vararg rows: BeneficiaryListItemDto) =
    Response.success(
      BeneficiaryListResponseDto(success = true, message = "OK", data = Gson().toJsonTree(rows.toList())),
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

  private fun sessionStoreFor(subjectId: String, username: String = "sakhi1"): SessionStore {
    val sessionStore = SessionStore(FakeSecureKeyValueStore())
    sessionStore.saveSession(session(username = username, subjectId = subjectId))
    return sessionStore
  }

  /** [repository] is the same [RemoteBeneficiaryRepository] instance across calls so tests can
   * exercise its in-memory cache; pass a fresh one only when simulating a new process/app launch. */
  private fun repo(
    api: BeneficiaryApi,
    store: FakeSecureKeyValueStore = FakeSecureKeyValueStore(),
    subjectId: String = "sakhi-1",
  ) = RemoteBeneficiaryRepository(api, sessionStoreFor(subjectId), store)

  private val today = LocalDate.of(2026, 8, 7)

  @Test
  fun `maps a mother row into a Beneficiary marked unassessed`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row()) })

    val result = repo(api).fetchRemoteBeneficiaries(today)

    val beneficiary = result?.single()
    assertEquals("remote-1", beneficiary?.id)
    assertEquals("remote-1", beneficiary?.remoteBeneficiaryId)
    assertEquals("Deepa T Test", beneficiary?.name)
    assertEquals(BeneficiaryType.MOTHER, beneficiary?.type)
    assertEquals(RiskLevel.LOW, beneficiary?.riskLevel)
    assertFalse(requireNotNull(beneficiary?.isAssessed))
    assertEquals(BeneficiaryStatus.ACTIVE, beneficiary?.status)
    assertEquals(VisitState.OPEN, beneficiary?.visitState)
  }

  @Test
  fun `maps a CHILD case type to BeneficiaryType INFANT`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(caseType = "CHILD")) })

    val beneficiary = repo(api).fetchRemoteBeneficiaries(today)?.single()

    assertEquals(BeneficiaryType.INFANT, beneficiary?.type)
  }

  @Test
  fun `drops a row with no id — nothing to key it by`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(id = null)) })

    assertEquals(emptyList<Beneficiary>(), repo(api).fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `blank or missing name falls back to a placeholder rather than dropping the row`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(pii = pii(fullName = null))) })

    val beneficiary = repo(api).fetchRemoteBeneficiaries(today)?.single()

    assertEquals("Unnamed beneficiary", beneficiary?.name)
  }

  @Test
  fun `villageName enrichment from the backend becomes the card's pada display`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(villageName = "Test Village")) })

    val beneficiary = repo(api).fetchRemoteBeneficiaries(today)?.single()

    assertEquals("Test Village", beneficiary?.pada)
  }

  @Test
  fun `a null or blank villageName still falls back to the unresolved dash, not a blank string`() = runTest {
    val api = FakeBeneficiaryApi(
      listAllResponse = {
        ok(row(id = "a", villageName = null), row(id = "b", villageName = "   "))
      },
    )

    val byId = repo(api).fetchRemoteBeneficiaries(today)?.associateBy { it.id }

    assertEquals("—", byId?.get("a")?.pada)
    assertEquals("—", byId?.get("b")?.pada)
  }

  @Test
  fun `JOURNEY_COMPLETE and CLOSED map to their own status, everything else collapses to ACTIVE`() = runTest {
    val api = FakeBeneficiaryApi(
      listAllResponse = {
        ok(
          row(id = "a", currentStatus = "JOURNEY_COMPLETE"),
          row(id = "b", currentStatus = "CLOSED"),
          row(id = "c", currentStatus = "TRANSFERRED"),
          row(id = "d", currentStatus = "REOPEN_REQUESTED"),
          row(id = "e", currentStatus = "something-unrecognised"),
        )
      },
    )

    val byId = repo(api).fetchRemoteBeneficiaries(today)?.associateBy { it.id }

    assertEquals(BeneficiaryStatus.JOURNEY_COMPLETE, byId?.get("a")?.status)
    assertEquals(BeneficiaryStatus.CLOSED, byId?.get("b")?.status)
    assertEquals(BeneficiaryStatus.ACTIVE, byId?.get("c")?.status)
    assertEquals(BeneficiaryStatus.ACTIVE, byId?.get("d")?.status)
    assertEquals(BeneficiaryStatus.ACTIVE, byId?.get("e")?.status)
  }

  @Test
  fun `a closed row has no open visit state`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(currentStatus = "CLOSED")) })

    val beneficiary = repo(api).fetchRemoteBeneficiaries(today)?.single()

    assertNull(beneficiary?.visitState)
  }

  @Test
  fun `parses the registration date without a timezone shift`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(registrationDate = "2026-07-28T00:00:00.000Z")) })

    val beneficiary = repo(api).fetchRemoteBeneficiaries(today)?.single()

    assertEquals(LocalDate.of(2026, 7, 28), beneficiary?.scheduleDate)
  }

  @Test
  fun `an unparseable registration date falls back to today rather than dropping the row`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(registrationDate = "not-a-date")) })

    val beneficiary = repo(api).fetchRemoteBeneficiaries(today)?.single()

    assertEquals(today, beneficiary?.scheduleDate)
  }

  @Test
  fun `persists a successful fetch under a key scoped to the session's subjectId`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeBeneficiaryApi(listAllResponse = { ok(row()) }), store, subjectId = "sakhi-1")
      .fetchRemoteBeneficiaries(today)

    assertTrue(store.getString("remote_beneficiary_list_cache_sakhi-1") != null)
  }

  @Test
  fun `falls back to the persisted list when a later fetch fails, for the same Sakhi`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeBeneficiaryApi(listAllResponse = { ok(row(id = "cached-1")) }), store)
      .fetchRemoteBeneficiaries(today)

    val offline = repo(FakeBeneficiaryApi(listAllResponse = null), store)
    assertEquals(listOf("cached-1"), offline.fetchRemoteBeneficiaries(today)?.map { it.id })
  }

  @Test
  fun `caches in memory after the first successful fetch, independent of disk`() = runTest {
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row()) })
    val repository = repo(api)
    repository.fetchRemoteBeneficiaries(today)

    api.listAllResponse = null // any further network attempt now fails
    assertEquals(listOf("remote-1"), repository.fetchRemoteBeneficiaries(today)?.map { it.id })
  }

  @Test
  fun `a different Sakhi's session on the same disk store never reads the prior Sakhi's cache`() = runTest {
    // Regression test: the disk cache used to be one device-global key, so a device previously
    // used by "Meera" (or here, any prior Sakhi) would still surface her cached caseload after a
    // different Sakhi logged in on it and her own fetch failed/hadn't synced yet.
    val store = FakeSecureKeyValueStore()
    repo(FakeBeneficiaryApi(listAllResponse = { ok(row(id = "meera-case")) }), store, subjectId = "meera-id")
      .fetchRemoteBeneficiaries(today)

    val meenaOffline = repo(FakeBeneficiaryApi(listAllResponse = null), store, subjectId = "meena-id")
    assertNull(meenaOffline.fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `a different Sakhi's session never reads the prior Sakhi's in-memory cache either`() = runTest {
    // Regression test: this repository is an app-wide singleton, so the in-memory `cached` field
    // used to be unscoped too — a stale in-memory list would win over even a cold disk cache when
    // a new Sakhi's session logged in within the same app process.
    val api = FakeBeneficiaryApi(listAllResponse = { ok(row(id = "meera-case")) })
    val store = FakeSecureKeyValueStore()
    val sharedSessionSlot = FakeSecureKeyValueStore()

    val meeraSession = SessionStore(sharedSessionSlot).apply { saveSession(session(subjectId = "meera-id")) }
    val repository = RemoteBeneficiaryRepository(api, meeraSession, store)
    repository.fetchRemoteBeneficiaries(today)

    // Same repository instance, but the session underneath it now belongs to a different Sakhi
    // and her fetch fails — must not fall back to Meera's in-memory list.
    sharedSessionSlot.remove("session_json")
    SessionStore(sharedSessionSlot).saveSession(session(subjectId = "meena-id"))
    api.listAllResponse = null

    assertNull(repository.fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `returns null when the fetch fails with a cold cache — genuinely nothing to show`() = runTest {
    assertNull(repo(FakeBeneficiaryApi(listAllResponse = null)).fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `treats success false as a failure`() = runTest {
    val api = FakeBeneficiaryApi(
      listAllResponse = {
        Response.success(
          BeneficiaryListResponseDto(
            success = false,
            message = "nope",
            data = Gson().toJsonTree(emptyList<BeneficiaryListItemDto>()),
          ),
        )
      },
    )
    assertNull(repo(api).fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `treats a non-2xx response as a failure`() = runTest {
    val api = FakeBeneficiaryApi(
      listAllResponse = { Response.error(500, "boom".toResponseBody("application/json".toMediaType())) },
    )
    assertNull(repo(api).fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `an empty success overwrites the cache — a closed-out Sakhi's caseload must not stay stale`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeBeneficiaryApi(listAllResponse = { ok(row(id = "gone")) }), store).fetchRemoteBeneficiaries(today)

    val emptied = repo(FakeBeneficiaryApi(listAllResponse = { ok() }), store)
    assertEquals(emptyList<Beneficiary>(), emptied.fetchRemoteBeneficiaries(today))

    val fresh = repo(FakeBeneficiaryApi(listAllResponse = null), store)
    assertEquals(emptyList<Beneficiary>(), fresh.fetchRemoteBeneficiaries(today))
  }

  @Test
  fun `a corrupted disk cache reads as absent, not a crash`() = runTest {
    val store = FakeSecureKeyValueStore()
    store.putRawCorrupted("remote_beneficiary_list_cache_sakhi-1")

    assertNull(repo(FakeBeneficiaryApi(listAllResponse = null), store).fetchRemoteBeneficiaries(today))
  }
}
