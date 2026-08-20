package org.armman.sakhi.data.forms

import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Hand-rolled [FormsApi] fake shared by every test that needs to control the active-version or
 * visit-code-form-map responses without hitting a real network call. Kept in its own file for the
 * same reason as [FakeFormSubmissionApi] (avoids a package-level redeclaration clash between test
 * classes that each need their own local `FakeFormsApi` name).
 *
 * Both response fields default to a bare 404 ("not stubbed") rather than throwing, so a test that
 * only cares about one endpoint (e.g. [VisitCodeFormResolver] tests never touch
 * [getActiveVersion]) doesn't have to stub the other one just to satisfy the interface.
 */
class FakeFormsApi : FormsApi {
  var activeVersionResponse: Response<FormActiveVersionResponseDto> = notStubbed()
  var visitCodeFormMapResponse: Response<VisitCodeFormMapResponseDto> = notStubbed()
  var getActiveVersionCallCount = 0
  var getVisitCodeFormMapCallCount = 0

  override suspend fun getActiveVersion(formCode: String): Response<FormActiveVersionResponseDto> {
    getActiveVersionCallCount++
    return activeVersionResponse
  }

  override suspend fun getVisitCodeFormMap(): Response<VisitCodeFormMapResponseDto> {
    getVisitCodeFormMapCallCount++
    return visitCodeFormMapResponse
  }

  companion object {
    private fun <T> notStubbed(): Response<T> =
      Response.error(404, "not stubbed".toResponseBody(null))
  }
}
