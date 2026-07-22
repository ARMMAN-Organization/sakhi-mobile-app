package org.armman.sakhi.data.auth.session

import org.armman.sakhi.data.auth.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineCredentialCacheTest {
  private lateinit var store: FakeSecureKeyValueStore
  private lateinit var cache: OfflineCredentialCache

  private fun sampleSession(username: String = "test.sakhi") = UserSession(
    username = username,
    subjectId = "sub-1",
    roles = listOf("SAKHI"),
    projectId = null,
    geographyUnitId = null,
    accessToken = "access-token",
    refreshToken = "refresh-token",
    accessTokenExpiresAtEpochSeconds = 2000L,
  )

  @Before
  fun setUp() {
    store = FakeSecureKeyValueStore()
    cache = OfflineCredentialCache(store)
  }

  @Test
  fun `store never persists plaintext password`() { // OC-1
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    val raw = store.getString("offline_cred_entry").orEmpty()
    assertFalse(raw.contains("Test@1234"))
  }

  @Test
  fun `verify matches correct username and password`() { // OC-2
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    assertTrue(cache.verify("test.sakhi", "Test@1234".toCharArray()))
  }

  @Test
  fun `verify rejects wrong password`() { // OC-3
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    assertFalse(cache.verify("test.sakhi", "wrongpass".toCharArray()))
  }

  @Test
  fun `verify rejects different username`() { // OC-4
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    assertFalse(cache.verify("someone.else", "Test@1234".toCharArray()))
  }

  @Test
  fun `verify with no cache present returns false, not a crash`() { // OC-5
    assertFalse(cache.verify("test.sakhi", "Test@1234".toCharArray()))
  }

  @Test
  fun `hasAnyEntry reflects cache presence`() {
    assertFalse(cache.hasAnyEntry())
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())
    assertTrue(cache.hasAnyEntry())
  }

  @Test
  fun `a new successful online login overwrites the previous cached hash`() { // OC-6
    cache.store("test.sakhi", "OldPass1".toCharArray(), sampleSession())
    cache.store("test.sakhi", "NewPass2".toCharArray(), sampleSession())

    assertTrue(cache.verify("test.sakhi", "NewPass2".toCharArray()))
    assertFalse(cache.verify("test.sakhi", "OldPass1".toCharArray()))
  }

  @Test
  fun `clear removes the cached entry`() {
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())
    cache.clear()

    assertFalse(cache.hasAnyEntry())
    assertFalse(cache.verify("test.sakhi", "Test@1234".toCharArray()))
  }

  @Test
  fun `verifyAndRestoreSession returns the session captured at store time`() {
    val session = sampleSession()
    cache.store("test.sakhi", "Test@1234".toCharArray(), session)

    val restored = cache.verifyAndRestoreSession("test.sakhi", "Test@1234".toCharArray())

    assertEquals(session, restored)
  }

  @Test
  fun `verifyAndRestoreSession returns null on wrong password`() {
    cache.store("test.sakhi", "Test@1234".toCharArray(), sampleSession())

    assertNull(cache.verifyAndRestoreSession("test.sakhi", "wrongpass".toCharArray()))
  }

  @Test
  fun `verifyAndRestoreSession survives a SessionStore logout`() {
    val session = sampleSession()
    cache.store("test.sakhi", "Test@1234".toCharArray(), session)
    val sessionStore = SessionStore(store)
    sessionStore.saveSession(session)

    sessionStore.clearSession() // simulates logging out while offline

    val restored = cache.verifyAndRestoreSession("test.sakhi", "Test@1234".toCharArray())
    assertEquals(session, restored)
    assertNull(sessionStore.readSession())
  }
}
