package org.armman.sakhi.data.visit

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.visittracker.PadaVisitDto
import org.armman.sakhi.data.visittracker.PadaVisitsDataDto
import org.armman.sakhi.data.visittracker.PadaVisitsResponseDto
import org.armman.sakhi.data.visittracker.VisitApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class RemoteVisitRepositoryTest {

  private class FakeVisitApi(
    var response: (() -> Response<PadaVisitsResponseDto>)? = null,
    var lastStatus: String? = null,
    var lastSearch: String? = null,
  ) : VisitApi {
    override suspend fun getVisits(
      padaId: String,
      status: String,
      date: String?,
      search: String?,
    ): Response<PadaVisitsResponseDto> {
      assertEquals("pada-1", padaId)
      lastStatus = status
      lastSearch = search
      return response?.invoke() ?: throw IOException("offline")
    }
  }

  private fun visitDto(
    visitId: String? = "visit-1",
    beneficiaryId: String? = "ben-1",
    beneficiaryName: String? = "Sunita Sharma",
    riskLevel: String? = "high",
    padaName: String? = "Jamsar",
    villageName: String? = "Pada - Jamsar",
    scheduledDate: String? = "2026-08-20",
    dueDate: String? = "2026-08-20",
    visitType: String? = "ANC 3",
    phoneNumber: String? = "9876543210",
  ) = PadaVisitDto(
    visitId, beneficiaryId, beneficiaryName, riskLevel, padaName, villageName,
    scheduledDate, dueDate, visitType, phoneNumber,
  )

  private fun ok(openCount: Int? = 1, referralCount: Int? = 1, vararg visits: PadaVisitDto) = Response.success(
    PadaVisitsResponseDto(
      success = true,
      message = "OK",
      data = PadaVisitsDataDto(openCount, referralCount, visits.toList()),
    ),
  )

  private fun repo(api: VisitApi, store: FakeSecureKeyValueStore = FakeSecureKeyValueStore()) =
    RemoteVisitRepository(api, store)

  @Test
  fun `fetch success maps every field and both counts`() = runTest {
    val result = repo(FakeVisitApi(response = { ok(3, 2, visitDto()) })).getVisits("pada-1", VisitStatus.OPEN)

    assertEquals(3, result.openCount)
    assertEquals(2, result.referralFollowUpCount)
    val visit = result.visits.single()
    assertEquals("visit-1", visit.id)
    assertEquals("ben-1", visit.beneficiaryId)
    assertEquals("Sunita Sharma", visit.beneficiaryName)
    assertEquals(RiskLevel.HIGH, visit.riskLevel)
    assertEquals("Jamsar", visit.pada)
    assertEquals("Pada - Jamsar", visit.village)
    assertEquals(LocalDate.of(2026, 8, 20), visit.dueDate)
    assertEquals("ANC 3", visit.visitLabel)
    assertEquals(BeneficiaryType.MOTHER, visit.beneficiaryType)
    assertEquals("9876543210", visit.phoneNumber)
  }

  @Test
  fun `status maps to the API's open and referral_follow_up query values`() = runTest {
    val api = FakeVisitApi(response = { ok(0, 0) })
    repo(api).getVisits("pada-1", VisitStatus.OPEN)
    assertEquals("open", api.lastStatus)
    repo(api).getVisits("pada-1", VisitStatus.REFERRAL_FOLLOW_UP)
    assertEquals("referral_follow_up", api.lastStatus)
  }

  @Test
  fun `blank search is not sent to the API`() = runTest {
    val api = FakeVisitApi(response = { ok(0, 0) })
    repo(api).getVisits("pada-1", VisitStatus.OPEN, search = "  ")
    assertNull(api.lastSearch)
    repo(api).getVisits("pada-1", VisitStatus.OPEN, search = "Sunita")
    assertEquals("Sunita", api.lastSearch)
  }

  @Test
  fun `null name phone and risk degrade the row instead of dropping it`() = runTest {
    val visit = repo(
      FakeVisitApi(
        response = {
          ok(
            1,
            0,
            visitDto(beneficiaryName = null, phoneNumber = null, riskLevel = null),
          )
        },
      ),
    ).getVisits("pada-1", VisitStatus.OPEN).visits.single()

    assertNull(visit.beneficiaryName)
    assertNull(visit.phoneNumber)
    assertNull(visit.riskLevel)
    assertEquals("ben-1", visit.beneficiaryId) // Base row survives.
  }

  @Test
  fun `a none risk grade maps to null, same as a failed lookup`() = runTest {
    val visit = repo(FakeVisitApi(response = { ok(1, 0, visitDto(riskLevel = "none")) }))
      .getVisits("pada-1", VisitStatus.OPEN).visits.single()
    assertNull(visit.riskLevel)
  }

  @Test
  fun `a referral row has a null visitId and infers case type from the label`() = runTest {
    val visit = repo(
      FakeVisitApi(
        response = {
          ok(
            0,
            1,
            visitDto(visitId = null, visitType = "Referral Follow-up"),
          )
        },
      ),
    ).getVisits("pada-1", VisitStatus.REFERRAL_FOLLOW_UP).visits.single()

    assertNull(visit.id)
    assertEquals(BeneficiaryType.MOTHER, visit.beneficiaryType) // No infant signal in the label.
  }

  @Test
  fun `visits sort by risk severity then by due date`() = runTest {
    val result = repo(
      FakeVisitApi(
        response = {
          ok(
            3,
            0,
            visitDto(visitId = "v-mild", riskLevel = "mild", dueDate = "2026-08-18"),
            visitDto(visitId = "v-high-late", riskLevel = "high", dueDate = "2026-08-22"),
            visitDto(visitId = "v-high-early", riskLevel = "high", dueDate = "2026-08-19"),
          )
        },
      ),
    ).getVisits("pada-1", VisitStatus.OPEN)

    assertEquals(listOf("v-high-early", "v-high-late", "v-mild"), result.visits.map { it.id })
  }

  @Test
  fun `a row missing its beneficiaryId is dropped`() = runTest {
    val result = repo(FakeVisitApi(response = { ok(1, 0, visitDto(beneficiaryId = null)) }))
      .getVisits("pada-1", VisitStatus.OPEN)
    assertEquals(emptyList<Visit>(), result.visits)
  }

  @Test
  fun `a row missing its dueDate is dropped`() = runTest {
    val result = repo(FakeVisitApi(response = { ok(1, 0, visitDto(dueDate = null)) }))
      .getVisits("pada-1", VisitStatus.OPEN)
    assertEquals(emptyList<Visit>(), result.visits)
  }

  @Test
  fun `empty visits array maps to an empty list, not an error`() = runTest {
    val result = repo(FakeVisitApi(response = { ok(0, 0) })).getVisits("pada-1", VisitStatus.OPEN)
    assertFalse(result.visits.isNotEmpty())
  }

  @Test
  fun `no cache and fetch fails throws so the caller's existing hasError path is used`() {
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(FakeVisitApi(response = null)).getVisits("pada-1", VisitStatus.OPEN) }
    }
  }

  @Test
  fun `a non-2xx response falls back to cache exactly like a network failure`() {
    val api = FakeVisitApi(response = { Response.error(502, "boom".toResponseBody("application/json".toMediaType())) })
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(api).getVisits("pada-1", VisitStatus.OPEN) }
    }
  }

  @Test
  fun `network failure falls back to the same day's cached visits`() = runTest {
    val store = FakeSecureKeyValueStore()
    val today = LocalDate.now()
    repo(FakeVisitApi(response = { ok(2, 1, visitDto(beneficiaryName = "Cached Beneficiary")) }), store)
      .getVisits("pada-1", VisitStatus.OPEN, date = today)

    val offline = repo(FakeVisitApi(response = null), store)
    val result = offline.getVisits("pada-1", VisitStatus.OPEN, date = today)

    assertEquals(2, result.openCount)
    assertEquals(1, result.referralFollowUpCount)
    assertEquals("Cached Beneficiary", result.visits.single().beneficiaryName)
  }

  @Test
  fun `a cache from a different day is not served as today's list`() = runTest {
    val store = FakeSecureKeyValueStore()
    repo(FakeVisitApi(response = { ok(1, 0, visitDto()) }), store)
      .getVisits("pada-1", VisitStatus.OPEN, date = LocalDate.of(2026, 8, 19))

    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking {
        repo(FakeVisitApi(response = null), store)
          .getVisits("pada-1", VisitStatus.OPEN, date = LocalDate.of(2026, 8, 20))
      }
    }
  }

  @Test
  fun `open and referral caches for the same pada and day don't clobber each other`() = runTest {
    val store = FakeSecureKeyValueStore()
    val today = LocalDate.now()
    repo(FakeVisitApi(response = { ok(1, 0, visitDto(visitId = "open-1")) }), store)
      .getVisits("pada-1", VisitStatus.OPEN, date = today)
    repo(FakeVisitApi(response = { ok(1, 0, visitDto(visitId = null, visitType = "Referral Follow-up")) }), store)
      .getVisits("pada-1", VisitStatus.REFERRAL_FOLLOW_UP, date = today)

    val offlineOpen = repo(FakeVisitApi(response = null), store).getVisits("pada-1", VisitStatus.OPEN, date = today)
    assertEquals("open-1", offlineOpen.visits.single().id)
  }

  @Test
  fun `offline search filters the cached full list by exact name match`() = runTest {
    val store = FakeSecureKeyValueStore()
    val today = LocalDate.now()
    repo(
      FakeVisitApi(
        response = {
          ok(2, 0, visitDto(visitId = "v1", beneficiaryName = "Sunita Sharma"), visitDto(visitId = "v2", beneficiaryName = "Riya Verma"))
        },
      ),
      store,
    ).getVisits("pada-1", VisitStatus.OPEN, date = today)

    val offline = repo(FakeVisitApi(response = null), store)
      .getVisits("pada-1", VisitStatus.OPEN, date = today, search = "sunita sharma")

    // Counts stay the pada's full counts — search never narrows them, only the visits list.
    assertEquals(2, offline.openCount)
    assertEquals(listOf("v1"), offline.visits.map { it.id })
  }

  @Test
  fun `a searched fetch is not itself cached as the full list`() = runTest {
    val store = FakeSecureKeyValueStore()
    val today = LocalDate.now()
    val api = FakeVisitApi(response = { ok(1, 0, visitDto(beneficiaryName = "Sunita Sharma")) })
    repo(api, store).getVisits("pada-1", VisitStatus.OPEN, date = today, search = "Sunita Sharma")

    // Nothing was ever cached (the only fetch was a search), so offline now throws rather than
    // serving a partial "search result" list as if it were the full one.
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repo(FakeVisitApi(response = null), store).getVisits("pada-1", VisitStatus.OPEN, date = today) }
    }
  }
}
