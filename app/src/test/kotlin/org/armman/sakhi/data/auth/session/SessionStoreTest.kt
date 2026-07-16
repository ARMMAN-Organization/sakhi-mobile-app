package org.armman.sakhi.data.auth.session

import org.armman.sakhi.data.auth.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionStoreTest {
  private lateinit var store: FakeSecureKeyValueStore
  private lateinit var sessionStore: SessionStore

  private fun sampleSession() = UserSession(
    username = "test.sakhi",
    subjectId = "sub-1",
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "access-token",
    refreshToken = "refresh-token",
    accessTokenExpiresAtEpochSeconds = 1_784_189_546L,
  )

  @Before
  fun setUp() {
    store = FakeSecureKeyValueStore()
    sessionStore = SessionStore(store)
  }

  @Test
  fun `save then read returns identical session`() { // SS-1
    val session = sampleSession()
    sessionStore.saveSession(session)

    assertEquals(session, sessionStore.readSession())
  }

  @Test
  fun `no session saved returns null`() { // SS-2
    assertNull(sessionStore.readSession())
  }

  @Test
  fun `logout clears session but not offline credential cache`() { // SS-3
    sessionStore.saveSession(sampleSession())
    val offlineCache = OfflineCredentialCache(store)
    offlineCache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    sessionStore.clearSession()

    assertNull(sessionStore.readSession())
    assertEquals(true, offlineCache.verify("test.sakhi", "Test@1234".toCharArray()))
  }

  @Test
  fun `explicit account removal clears both session and offline cache`() { // SS-4
    // No single removeAccount() API exists yet — there's no UI action that triggers it. This
    // documents the composition a future "remove account" feature would call: both clears,
    // independently, since SessionStore and OfflineCredentialCache are deliberately decoupled.
    sessionStore.saveSession(sampleSession())
    val offlineCache = OfflineCredentialCache(store)
    offlineCache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    sessionStore.clearSession()
    offlineCache.clear()

    assertNull(sessionStore.readSession())
    assertEquals(false, offlineCache.verify("test.sakhi", "Test@1234".toCharArray()))
  }

  @Test
  fun `corrupted store read fails safe`() { // SS-5
    store.putRawCorrupted("session_json")

    assertNull(sessionStore.readSession())
  }
}
