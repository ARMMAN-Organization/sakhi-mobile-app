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
}
