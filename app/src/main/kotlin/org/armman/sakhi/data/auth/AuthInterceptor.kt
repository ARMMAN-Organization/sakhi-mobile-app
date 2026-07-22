package org.armman.sakhi.data.auth

import okhttp3.Interceptor
import okhttp3.Response
import org.armman.sakhi.data.auth.session.SessionStore
import javax.inject.Inject

private const val HEADER_AUTHORIZATION = "Authorization"

/**
 * Attaches the signed-in Sakhi's access token to every outgoing request.
 *
 * `login` itself needs no token (no session exists yet); every endpoint added after login
 * does (starting with `/me`), so this is applied globally at the OkHttp level rather than
 * threaded through each Retrofit call individually. If no session is stored (not logged in,
 * or logged out), the request is sent unmodified and the server rejects it with 401 as normal.
 */
class AuthInterceptor @Inject constructor(
  private val sessionStore: SessionStore,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val accessToken = sessionStore.readSession()?.accessToken
    val authorizedRequest = if (accessToken != null) {
      request.newBuilder().addHeader(HEADER_AUTHORIZATION, "Bearer $accessToken").build()
    } else {
      request
    }
    return chain.proceed(authorizedRequest)
  }
}
