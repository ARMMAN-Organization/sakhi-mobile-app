package org.armman.sakhi.data.auth.session

import org.armman.sakhi.data.auth.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionStoreTest {
  private lateinit var store: FakeSecureKeyValueStore
  private lateinit var sessionStore: SessionStore

  // Fixed "now" so expiry assertions are deterministic.
  private val nowEpochSeconds = 1_784_189_546L
  private val fixedClock: Clock = Clock.fixed(Instant.ofEpochSecond(nowEpochSeconds), ZoneOffset.UTC)

  private fun sampleSession(expiresAtEpochSeconds: Long = nowEpochSeconds + 3600) = UserSession(
    username = "test.sakhi",
    subjectId = "sub-1",
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "access-token",
    refreshToken = "refresh-token",
    accessTokenExpiresAtEpochSeconds = expiresAtEpochSeconds,
  )

  @Before
  fun setUp() {
    store = FakeSecureKeyValueStore()
    sessionStore = SessionStore(store, fixedClock)
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

  @Test
  fun `hasValidSession is false when nothing saved`() { // SS-6
    assertFalse(sessionStore.hasValidSession())
  }

  @Test
  fun `hasValidSession is true for an unexpired session`() { // SS-7
    sessionStore.saveSession(sampleSession(expiresAtEpochSeconds = nowEpochSeconds + 60))

    assertTrue(sessionStore.hasValidSession())
  }

  @Test
  fun `hasValidSession is false for an expired session`() { // SS-8
    // The session is still readable (so "stay logged in" data survives) but must NOT bypass login.
    sessionStore.saveSession(sampleSession(expiresAtEpochSeconds = nowEpochSeconds - 1))

    assertFalse(sessionStore.hasValidSession())
    // readSession still returns the (expired) session — it's only hasValidSession that gates login.
    assertEquals("access-token", sessionStore.readSession()?.accessToken)
  }

  @Test
  fun `hasValidSession is false for a session expiring exactly now`() { // SS-9
    // Boundary: exp == now is treated as expired (strict greater-than).
    sessionStore.saveSession(sampleSession(expiresAtEpochSeconds = nowEpochSeconds))

    assertFalse(sessionStore.hasValidSession())
  }
}
