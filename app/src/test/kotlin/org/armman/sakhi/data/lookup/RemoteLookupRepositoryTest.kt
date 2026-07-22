package org.armman.sakhi.data.lookup

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class RemoteLookupRepositoryTest {

  /** Configurable fake — success/error/throw per category, counts calls per category. */
  private class FakeLookupApi : LookupApi {
    val responses = mutableMapOf<String, Response<LookupCategoryResponseDto>>()
    val exceptions = mutableMapOf<String, Throwable>()
    val callCounts = mutableMapOf<String, Int>()

    override suspend fun getCategory(categoryCode: String): Response<LookupCategoryResponseDto> {
      callCounts[categoryCode] = (callCounts[categoryCode] ?: 0) + 1
      exceptions[categoryCode]?.let { throw it }
      return responses.getValue(categoryCode)
    }
  }

  private fun valueDto(
    id: String,
    valueCode: String,
    valueLabel: String,
    sortOrder: Int = 0,
    isActive: Boolean = true,
  ) = LookupValueDto(
    id = id,
    valueCode = valueCode,
    valueLabel = valueLabel,
    sortOrder = sortOrder,
    parentLookupValueId = null,
    isActive = isActive,
  )

  private fun successResponse(categoryCode: String, values: List<LookupValueDto>) = Response.success(
    LookupCategoryResponseDto(
      success = true,
      message = "OK",
      data = LookupCategoryData(
        id = "cat-1",
        categoryCode = categoryCode,
        categoryName = categoryCode,
        description = null,
        isActive = true,
        values = values,
      ),
    ),
  )

  @Test
  fun `success maps active values sorted by sortOrder`() = runTest {
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse(
        "CASE_TYPE",
        listOf(
          valueDto(id = "id-child", valueCode = "CHILD", valueLabel = "Child", sortOrder = 2),
          valueDto(id = "id-mother", valueCode = "MOTHER", valueLabel = "Mother", sortOrder = 1),
        ),
      )
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    val values = repository.getValues("CASE_TYPE")

    assertEquals(listOf("MOTHER", "CHILD"), values.map { it.valueCode })
    assertEquals("id-mother", values.first().id)
  }

  @Test
  fun `inactive values are filtered out`() = runTest {
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse(
        "CASE_TYPE",
        listOf(
          valueDto(id = "id-mother", valueCode = "MOTHER", valueLabel = "Mother", isActive = true),
          valueDto(id = "id-old", valueCode = "OLD_CODE", valueLabel = "Deprecated", isActive = false),
        ),
      )
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    val values = repository.getValues("CASE_TYPE")

    assertEquals(1, values.size)
    assertEquals("MOTHER", values.first().valueCode)
  }

  @Test
  fun `findValue resolves by valueCode`() = runTest {
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse(
        "CASE_TYPE",
        listOf(valueDto(id = "id-mother", valueCode = "MOTHER", valueLabel = "Mother")),
      )
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    assertEquals("id-mother", repository.findValue("CASE_TYPE", "MOTHER")?.id)
    assertNull(repository.findValue("CASE_TYPE", "UNKNOWN"))
  }

  @Test
  fun `category not seeded yet (empty values) returns empty list, not an error`() = runTest {
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse("CASE_TYPE", emptyList())
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    assertTrue(repository.getValues("CASE_TYPE").isEmpty())
  }

  @Test
  fun `category not found (404) returns empty when nothing persisted`() = runTest {
    val json = """{"success":false,"message":"not found","errorCode":"NOT_FOUND"}"""
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = Response.error(404, json.toResponseBody("application/json".toMediaType()))
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    assertTrue(repository.getValues("CASE_TYPE").isEmpty())
  }

  @Test
  fun `network failure returns empty when nothing persisted`() = runTest {
    val api = FakeLookupApi().apply { exceptions["CASE_TYPE"] = IOException("timeout") }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    assertTrue(repository.getValues("CASE_TYPE").isEmpty())
  }

  @Test
  fun `caches per category so a second call for the same category does not refetch`() = runTest {
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse(
        "CASE_TYPE",
        listOf(valueDto(id = "id-mother", valueCode = "MOTHER", valueLabel = "Mother")),
      )
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    repository.getValues("CASE_TYPE")
    repository.getValues("CASE_TYPE")

    assertEquals(1, api.callCounts["CASE_TYPE"])
  }

  @Test
  fun `different categories are cached independently`() = runTest {
    val api = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse(
        "CASE_TYPE",
        listOf(valueDto(id = "id-mother", valueCode = "MOTHER", valueLabel = "Mother")),
      )
      responses["BENEFICIARY_TYPE"] = successResponse(
        "BENEFICIARY_TYPE",
        listOf(valueDto(id = "id-pw", valueCode = "PREGNANT_WOMAN", valueLabel = "Pregnant Woman")),
      )
    }
    val repository = RemoteLookupRepository(api, FakeSecureKeyValueStore())

    val caseType = repository.getValues("CASE_TYPE")
    val beneficiaryType = repository.getValues("BENEFICIARY_TYPE")

    assertEquals("MOTHER", caseType.single().valueCode)
    assertEquals("PREGNANT_WOMAN", beneficiaryType.single().valueCode)
  }

  @Test
  fun `successful fetch persists so an app restart with the same store sees it offline`() = runTest {
    val store = FakeSecureKeyValueStore()
    val onlineApi = FakeLookupApi().apply {
      responses["CASE_TYPE"] = successResponse(
        "CASE_TYPE",
        listOf(valueDto(id = "id-mother", valueCode = "MOTHER", valueLabel = "Mother")),
      )
    }
    RemoteLookupRepository(onlineApi, store).getValues("CASE_TYPE")

    val offlineApi = FakeLookupApi().apply { exceptions["CASE_TYPE"] = IOException("offline") }
    val restarted = RemoteLookupRepository(offlineApi, store)

    assertEquals("MOTHER", restarted.getValues("CASE_TYPE").single().valueCode)
  }
}
