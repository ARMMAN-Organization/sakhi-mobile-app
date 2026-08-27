package org.armman.sakhi.data.forms

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/** Retrofit contract for `visit-form-service`'s dynamic form schema endpoint (CR-018), behind the
 * same API gateway/base URL as the other services. Requires a Bearer token, attached by
 * [org.armman.sakhi.data.auth.AuthInterceptor]. */
interface FormsApi {
  @GET("forms/{formCode}/active-version")
  suspend fun getActiveVersion(@Path("formCode") formCode: String): Response<FormActiveVersionResponseDto>

  /** CR-033/CR-034: backend lookup translating a schedule's [org.armman.sakhi.data.schedule.VisitCodeType]
   * (e.g. "PP", "INC_HR") to the `formCode` this API's other endpoints expect (e.g. "POSTPARTUM_VISIT").
   * See [org.armman.sakhi.data.forms.VisitCodeFormResolver] for the caching/fallback wrapper around
   * this call — nothing else should call this directly. */
  @GET("forms/visit-code-form-map")
  suspend fun getVisitCodeFormMap(): Response<VisitCodeFormMapResponseDto>
}
