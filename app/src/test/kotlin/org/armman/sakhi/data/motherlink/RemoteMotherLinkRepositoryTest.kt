package org.armman.sakhi.data.motherlink

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/** Covers REPO-01 … REPO-15 of `docs/test-cases/child-mother-link.md`. */
class RemoteMotherLinkRepositoryTest {

  private class InMemoryStore : SecureKeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
  }

  private class FakeBeneficiaryApi(
    var listResponse: (() -> Response<BeneficiaryListResponseDto>)? = null,
    var detailResponse: (() -> Response<BeneficiaryDetailResponseDto>)? = null,
  ) : BeneficiaryApi {
    var listCallCount = 0

    override suspend fun list(caseType: String, status: String): Response<BeneficiaryListResponseDto> {
      listCallCount++
      return listResponse?.invoke() ?: throw IOException("offline")
    }

    override suspend fun detail(id: String): Response<BeneficiaryDetailResponseDto> =
      detailResponse?.invoke() ?: throw IOException("offline")
  }

  private fun pii(
    fullName: String? = "Deepa T Test",
    dateOfBirth: String? = "2001-07-29T00:00:00.000Z",
  ) = BeneficiaryPiiDto(
    id = "pii-1",
    fullName = fullName,
    villageId = "village-1",
    padaId = "pada-1",
    healthSubCentreId = "sc-1",
    phcId = "phc-1",
    healthBlockId = "block-1",
    dateOfBirth = dateOfBirth,
    sex = "FEMALE",
    stateId = "state-1",
    districtId = "district-1",
    talukaId = "taluka-1",
  )

  private fun row(
    id: String = "mother-1",
    caseType: String = "MOTHER",
    currentStatus: String = "ACTIVE",
    currentPhase: String = "ANC",
    pii: BeneficiaryPiiDto? = pii(),
  ) = BeneficiaryListItemDto(
    id = id,
    caseType = caseType,
    currentStatus = currentStatus,
    currentPhase = currentPhase,
    registrationDate = "2026-07-28T00:00:00.000Z",
    motherBeneficiaryId = null,
    pii = pii,
  )

  private fun ok(vararg rows: BeneficiaryListItemDto) =
    Response.success(BeneficiaryListResponseDto(success = true, message = "OK", data = rows.toList()))

  private fun repo(api: BeneficiaryApi, store: SecureKeyValueStore = InMemoryStore()) =
    RemoteMotherLinkRepository(api, store)

  // REPO-01 — a backend that ignores the caseType param must not put children into the picker.
  @Test
  fun `drops non-mother rows even though the query filters`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(id = "m1"), row(id = "c1", caseType = "CHILD")) })
    val result = repo(api).getRegisteredMothers()
    assertEquals(listOf("m1"), result?.map { it.id })
  }

  // REPO-02
  @Test
  fun `drops non-active rows`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(id = "m1"), row(id = "m2", currentStatus = "CLOSED")) })
    assertEquals(listOf("m1"), repo(api).getRegisteredMothers()?.map { it.id })
  }

  // REPO-03
  @Test
  fun `preserves backend ordering`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(id = "b"), row(id = "a"), row(id = "c")) })
    assertEquals(listOf("b", "a", "c"), repo(api).getRegisteredMothers()?.map { it.id })
  }

  // REPO-04 — taken as a calendar date, never converted through a local timezone.
  @Test
  fun `parses the date part without a timezone shift`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(pii = pii(dateOfBirth = "2001-07-29T00:00:00.000Z"))) })
    assertEquals(LocalDate.of(2001, 7, 29), repo(api).getRegisteredMothers()?.single()?.dateOfBirth)
  }

  // REPO-05 — a bad DOB must never drop a selectable mother.
  @Test
  fun `keeps a row whose date of birth is unparseable`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(pii = pii(dateOfBirth = "not-a-date"))) })
    val mother = repo(api).getRegisteredMothers()?.single()
    assertEquals("mother-1", mother?.id)
    assertNull(mother?.dateOfBirth)
  }

  @Test
  fun `keeps a row whose date of birth is missing`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(pii = pii(dateOfBirth = null))) })
    assertNull(repo(api).getRegisteredMothers()?.single()?.dateOfBirth)
  }

  @Test
  fun `drops a row with no pii block`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row(pii = null)) })
    assertEquals(emptyList<String>(), repo(api).getRegisteredMothers()?.map { it.id })
  }

  // REPO-06
  @Test
  fun `persists a successful fetch`() = runTest {
    val store = InMemoryStore()
    val api = FakeBeneficiaryApi(listResponse = { ok(row()) })
    repo(api, store).getRegisteredMothers()
    assertTrue(store.values.containsKey("mother_link_cache"))
  }

  // REPO-07 — the field reality: enrollment happens with no signal.
  @Test
  fun `falls back to the persisted list when the fetch fails`() = runTest {
    val store = InMemoryStore()
    repo(FakeBeneficiaryApi(listResponse = { ok(row(id = "cached-mother")) }), store).getRegisteredMothers()

    val offline = repo(FakeBeneficiaryApi(listResponse = null), store)
    assertEquals(listOf("cached-mother"), offline.getRegisteredMothers()?.map { it.id })
  }

  // REPO-08 — null and emptyList must stay distinguishable; the screen words them differently.
  @Test
  fun `returns null when the fetch fails with a cold cache`() = runTest {
    assertNull(repo(FakeBeneficiaryApi(listResponse = null)).getRegisteredMothers())
  }

  // REPO-09
  @Test
  fun `treats success false as a failure`() = runTest {
    val api = FakeBeneficiaryApi(
      listResponse = {
        Response.success(BeneficiaryListResponseDto(success = false, message = "nope", data = emptyList()))
      },
    )
    assertNull(repo(api).getRegisteredMothers())
  }

  @Test
  fun `treats a non-2xx response as a failure`() = runTest {
    val api = FakeBeneficiaryApi(
      listResponse = {
        Response.error(500, "boom".toResponseBody("application/json".toMediaType()))
      },
    )
    assertNull(repo(api).getRegisteredMothers())
  }

  // REPO-10 — a Sakhi whose last mother was closed must stop seeing a stale row.
  @Test
  fun `an empty success overwrites the cache`() = runTest {
    val store = InMemoryStore()
    repo(FakeBeneficiaryApi(listResponse = { ok(row(id = "gone")) }), store).getRegisteredMothers()

    val emptied = repo(FakeBeneficiaryApi(listResponse = { ok() }), store)
    assertEquals(emptyList<String>(), emptied.getRegisteredMothers()?.map { it.id })

    // And the stale row is gone from disk too, not just from this instance's memory.
    val fresh = repo(FakeBeneficiaryApi(listResponse = null), store)
    assertEquals(emptyList<String>(), fresh.getRegisteredMothers()?.map { it.id })
  }

  // REPO-11
  @Test
  fun `a corrupted cache reads as absent`() = runTest {
    val store = InMemoryStore()
    store.putString("mother_link_cache", "{not json")
    assertNull(repo(FakeBeneficiaryApi(listResponse = null), store).getRegisteredMothers())
  }

  // REPO-12
  @Test
  fun `caches in memory after the first successful fetch`() = runTest {
    val api = FakeBeneficiaryApi(listResponse = { ok(row()) })
    val repository = repo(api)
    repository.getRegisteredMothers()
    api.listResponse = null // any further network attempt now fails
    assertEquals(listOf("mother-1"), repository.getRegisteredMothers()?.map { it.id })
  }

  // REPO-13
  @Test
  fun `reads inherited consent as given`() = runTest {
    val api = FakeBeneficiaryApi(
      detailResponse = {
        Response.success(
          BeneficiaryDetailResponseDto(
            success = true,
            message = "OK",
            data = BeneficiaryDetailDto(
              id = "mother-1",
              consentRecords = listOf(ConsentRecordDto("ENROLLMENT", "GIVEN", "2026-07-28T00:00:00.000Z")),
            ),
          ),
        )
      },
    )
    assertEquals(LinkedMotherConsent(true), repo(api).getMotherConsent("mother-1"))
  }

  // REPO-14
  @Test
  fun `reads a refused or empty consent record as not given`() = runTest {
    val refused = FakeBeneficiaryApi(
      detailResponse = {
        Response.success(
          BeneficiaryDetailResponseDto(
            true, "OK",
            BeneficiaryDetailDto("mother-1", listOf(ConsentRecordDto("ENROLLMENT", "REFUSED", null))),
          ),
        )
      },
    )
    assertEquals(LinkedMotherConsent(false), repo(refused).getMotherConsent("mother-1"))

    val empty = FakeBeneficiaryApi(
      detailResponse = {
        Response.success(BeneficiaryDetailResponseDto(true, "OK", BeneficiaryDetailDto("mother-1", emptyList())))
      },
    )
    assertEquals(LinkedMotherConsent(false), repo(empty).getMotherConsent("mother-1"))
  }

  // REPO-15 — a failed consent lookup must not fail the selection.
  @Test
  fun `returns null consent when the detail call fails`() = runTest {
    assertNull(repo(FakeBeneficiaryApi(detailResponse = null)).getMotherConsent("mother-1"))
  }
}
