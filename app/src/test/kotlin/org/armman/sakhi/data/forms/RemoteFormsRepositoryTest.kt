package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class RemoteFormsRepositoryTest {

  private class FakeFormsApi : FormsApi {
    var response: Response<FormActiveVersionResponseDto>? = null
    var exceptionToThrow: Throwable? = null
    var callCount = 0

    override suspend fun getActiveVersion(formCode: String): Response<FormActiveVersionResponseDto> {
      callCount++
      exceptionToThrow?.let { throw it }
      return response!!
    }
  }

  private fun version(versionNo: String) = FormVersion(
    id = "version-$versionNo",
    formDefinitionId = "def-1",
    versionNo = versionNo,
    schemaJson = listOf(
      FormFieldSchema(label = "LMP date", required = true, inputTypeRaw = "date", questionCode = "lmp_date"),
    ),
    validationJson = emptyList(),
    effectiveFrom = "2026-07-20T00:00:00Z",
    effectiveTo = null,
    status = "PUBLISHED",
  )

  private fun successResponse(version: FormVersion) =
    Response.success(FormActiveVersionResponseDto(success = true, message = "OK", data = version))

  @Test
  fun `successful fetch returns the version`() = runTest {
    val api = FakeFormsApi().apply { response = successResponse(version("v1")) }
    val repository = RemoteFormsRepository(api, FakeSecureKeyValueStore())

    val result = repository.getActiveVersion("MOTHER_REGISTRATION")

    assertEquals("v1", result?.versionNo)
  }

  @Test
  fun `network failure with nothing cached returns null`() = runTest {
    val api = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val repository = RemoteFormsRepository(api, FakeSecureKeyValueStore())

    assertNull(repository.getActiveVersion("MOTHER_REGISTRATION"))
  }

  @Test
  fun `form not published yet (404) returns null when nothing persisted`() = runTest {
    val json = """{"success":false,"message":"not found"}"""
    val api = FakeFormsApi().apply {
      response = Response.error(404, json.toResponseBody("application/json".toMediaType()))
    }
    val repository = RemoteFormsRepository(api, FakeSecureKeyValueStore())

    assertNull(repository.getActiveVersion("MOTHER_REGISTRATION"))
  }

  @Test
  fun `successful fetch persists so an app restart offline still sees it`() = runTest {
    val store = FakeSecureKeyValueStore()
    val onlineApi = FakeFormsApi().apply { response = successResponse(version("v1")) }
    RemoteFormsRepository(onlineApi, store).getActiveVersion("MOTHER_REGISTRATION")

    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val restarted = RemoteFormsRepository(offlineApi, store)

    assertEquals("v1", restarted.getActiveVersion("MOTHER_REGISTRATION")?.versionNo)
  }

  @Test
  fun `a newer published version always replaces the cached one on next fetch`() = runTest {
    val store = FakeSecureKeyValueStore()
    val api = FakeFormsApi().apply { response = successResponse(version("v1")) }
    val repository = RemoteFormsRepository(api, store)
    repository.getActiveVersion("MOTHER_REGISTRATION")

    api.response = successResponse(version("v5"))
    val result = repository.getActiveVersion("MOTHER_REGISTRATION")

    assertEquals("v5", result?.versionNo)
    assertEquals(2, api.callCount) // always prefers a fresh fetch — no manual "check for updates" step
  }
}
