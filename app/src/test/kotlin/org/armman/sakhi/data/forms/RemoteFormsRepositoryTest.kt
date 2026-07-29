package org.armman.sakhi.data.forms

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  private fun version(
    versionNo: String,
    geography: List<FormGeographyUnit>? = null,
  ) = FormVersion(
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
    geography = geography,
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
  fun `backend geography is parsed and survives the offline persistence round-trip`() = runTest {
    val store = FakeSecureKeyValueStore()
    val geography = listOf(
      FormGeographyUnit("phc-uuid", "PHC", "Dhadgaon PHC"),
      FormGeographyUnit("state-uuid", "STATE", "Maharashtra"),
    )
    val onlineApi = FakeFormsApi().apply { response = successResponse(version("v1", geography)) }
    RemoteFormsRepository(onlineApi, store).getActiveVersion("MOTHER_REGISTRATION")

    // Restart offline — geography must come back from the persisted cache, not be dropped.
    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val restored = RemoteFormsRepository(offlineApi, store).getActiveVersion("MOTHER_REGISTRATION")

    assertEquals(geography, restored?.geography)
  }

  @Test
  fun `a version cached by an older build without geography still loads with null geography`() = runTest {
    // Legacy persisted JSON predating the `geography` field — Gson must not choke on its absence.
    val store = FakeSecureKeyValueStore()
    val legacyJson = """
      {"id":"version-legacy","formDefinitionId":"def-1","versionNo":"v0",
       "schemaJson":[{"label":"LMP date","required":true,"input_type":"date","question_code":"lmp_date"}],
       "validationJson":[],"effectiveFrom":"2026-07-20T00:00:00Z","status":"PUBLISHED"}
    """.trimIndent()
    store.putString("form_active_version_MOTHER_REGISTRATION", legacyJson)

    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val loaded = RemoteFormsRepository(offlineApi, store).getActiveVersion("MOTHER_REGISTRATION")

    assertEquals("v0", loaded?.versionNo)
    assertNull(loaded?.geography)
  }

  @Test
  fun `a visibleWhen block parses from the wire shape, numeric value included`() = runTest {
    // Nothing else pins the JSON key or the value's type: the backend sends camelCase `visibleWhen`
    // with `value: z.any()`, so a numeric 2 arrives unquoted. A key or type mismatch is silent —
    // Gson leaves the property null and the field renders unconditionally, which is exactly the
    // "shown regardless of Gravida" bug.
    val store = FakeSecureKeyValueStore()
    val json = """
      {"id":"version-vw","formDefinitionId":"def-1","versionNo":"v2",
       "schemaJson":[{"label":"When was your last pregnancy?","required":false,"input_type":"radio",
        "question_code":"when_was_your_last_pregnancy",
        "visibleWhen":{"field":"gravida_total_number_of_pregnancies","operator":"gte","value":2}}],
       "validationJson":[],"effectiveFrom":"2026-07-20T00:00:00Z","status":"PUBLISHED"}
    """.trimIndent()
    store.putString("form_active_version_MOTHER_REGISTRATION", json)

    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val field = requireNotNull(
      RemoteFormsRepository(offlineApi, store).getActiveVersion("MOTHER_REGISTRATION"),
    ).schemaJson.single()

    val condition = requireNotNull(field.visibleWhen)
    assertEquals("gravida_total_number_of_pregnancies", condition.field)
    assertEquals("gte", condition.operator)
    assertEquals("2", condition.value)

    // …and it actually gates the field once parsed.
    assertFalse(
      FormVisibilityEvaluator.isVisible(
        field,
        FormAnswers(singleValues = mapOf("gravida_total_number_of_pregnancies" to "1")),
      ),
    )
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
