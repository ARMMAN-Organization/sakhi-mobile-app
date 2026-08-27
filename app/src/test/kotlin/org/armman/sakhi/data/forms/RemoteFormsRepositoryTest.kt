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

    // Not exercised by this test class (it only covers getActiveVersion) — added purely so this
    // fake still satisfies the FormsApi interface after CR-033/CR-034 added getVisitCodeFormMap.
    override suspend fun getVisitCodeFormMap(): Response<VisitCodeFormMapResponseDto> =
      error("FakeFormsApi.getVisitCodeFormMap() not stubbed — not used by RemoteFormsRepositoryTest")
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

  // --- FormVisibleWhenDeserializer: malformed backend content must not fail the whole parse ----

  @Test
  fun `a visibleWhen sent as a JSON array does not fail the whole schema parse`() = runTest {
    // The real bug report: schemaJson[7]'s visibleWhen arrived as a JSON array instead of the
    // documented single object (form-field.dto.ts). A bare reflective Gson parse throws
    // "Expected BEGIN_OBJECT but was BEGIN_ARRAY" here, and because schemaJson deserializes as
    // one array in a single pass, that took down the ENTIRE form — not just this one field.
    val store = FakeSecureKeyValueStore()
    val json = """
      {"id":"version-vw","formDefinitionId":"def-1","versionNo":"v3",
       "schemaJson":[
         {"label":"Weight","required":true,"input_type":"number","question_code":"weight_kg"},
         {"label":"Malformed field","required":false,"input_type":"text",
          "question_code":"some_field","visibleWhen":[{"field":"x","operator":"eq","value":"1"}]}
       ],
       "validationJson":[],"effectiveFrom":"2026-07-20T00:00:00Z","status":"PUBLISHED"}
    """.trimIndent()
    store.putString("form_active_version_ANC_VISIT", json)

    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val version = RemoteFormsRepository(offlineApi, store).getActiveVersion("ANC_VISIT")

    // The whole form must still load — this is the actual bug: before the fix, this call threw
    // and the Sakhi got "We couldn't load this visit's data" for the ENTIRE form.
    assertEquals(2, version?.schemaJson?.size)
    // The malformed field degrades to "never hidden" rather than crashing — the safe direction to
    // fail (an extra visible field is recoverable; a form that won't open at all is not).
    val malformedField = version?.schemaJson?.single { it.questionCode == "some_field" }
    assertNull(malformedField?.visibleWhen)
    // Every other field on the same form is completely unaffected.
    val weightField = version?.schemaJson?.single { it.questionCode == "weight_kg" }
    assertNull(weightField?.visibleWhen)
  }

  @Test
  fun `a valid visibleWhen object still parses correctly alongside the tolerant adapter`() = runTest {
    // Guards against the fix over-correcting: a well-formed visibleWhen must keep working exactly
    // as before — this is the same case `a visibleWhen block parses from the wire shape` above
    // covers on MOTHER_REGISTRATION, repeated here to confirm the new adapter didn't regress it.
    val store = FakeSecureKeyValueStore()
    val json = """
      {"id":"version-vw2","formDefinitionId":"def-1","versionNo":"v4",
       "schemaJson":[{"label":"Follow-up","required":false,"input_type":"radio",
        "question_code":"follow_up",
        "visibleWhen":{"field":"weight_kg","operator":"gte","value":50}}],
       "validationJson":[],"effectiveFrom":"2026-07-20T00:00:00Z","status":"PUBLISHED"}
    """.trimIndent()
    store.putString("form_active_version_ANC_VISIT", json)

    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val field = requireNotNull(
      RemoteFormsRepository(offlineApi, store).getActiveVersion("ANC_VISIT"),
    ).schemaJson.single()

    val condition = requireNotNull(field.visibleWhen)
    assertEquals("weight_kg", condition.field)
    assertEquals("gte", condition.operator)
    assertEquals("50", condition.value)
  }

  @Test
  fun `a visibleWhen missing its required field or operator degrades to null rather than throwing`() = runTest {
    val store = FakeSecureKeyValueStore()
    val json = """
      {"id":"version-vw3","formDefinitionId":"def-1","versionNo":"v5",
       "schemaJson":[{"label":"Odd field","required":false,"input_type":"text",
        "question_code":"odd_field","visibleWhen":{"value":"1"}}],
       "validationJson":[],"effectiveFrom":"2026-07-20T00:00:00Z","status":"PUBLISHED"}
    """.trimIndent()
    store.putString("form_active_version_ANC_VISIT", json)

    val offlineApi = FakeFormsApi().apply { exceptionToThrow = IOException("offline") }
    val field = requireNotNull(
      RemoteFormsRepository(offlineApi, store).getActiveVersion("ANC_VISIT"),
    ).schemaJson.single()

    assertNull(field.visibleWhen)
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
