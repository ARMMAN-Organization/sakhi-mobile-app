package org.armman.sakhi.data.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.armman.sakhi.data.auth.session.SessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

  private lateinit var server: MockWebServer
  private lateinit var sessionStore: SessionStore
  private lateinit var client: OkHttpClient

  private val session = UserSession(
    username = "test.sakhi",
    subjectId = "sub-1",
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "token-123",
    refreshToken = "refresh-1",
    accessTokenExpiresAtEpochSeconds = 2000L,
  )

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
    sessionStore = SessionStore(FakeSecureKeyValueStore())
    client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionStore)).build()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun call() {
    server.enqueue(MockResponse().setResponseCode(200))
    val request = Request.Builder().url(server.url("/me")).build()
    client.newCall(request).execute().close()
  }

  @Test
  fun `attaches bearer token when a session is stored`() {
    sessionStore.saveSession(session)

    call()

    val recorded = server.takeRequest()
    assertEquals("Bearer token-123", recorded.getHeader("Authorization"))
  }

  @Test
  fun `sends request unmodified when no session is stored`() {
    call()

    val recorded = server.takeRequest()
    assertNull(recorded.getHeader("Authorization"))
  }
}
