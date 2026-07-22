package org.armman.sakhi.data.auth

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.armman.sakhi.data.auth.session.FakeSecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class RemoteCurrentUserRepositoryTest {

  /** Configurable fake — success/error/throw, and counts how many times `/me` was actually
   * called, so caching can be asserted on. */
  private class FakeAuthApi : AuthApi {
    var meResponse: Response<CallerProfileResponseDto>? = null
    var meExceptionToThrow: Throwable? = null
    var meCallCount = 0

    override suspend fun login(request: LoginRequestDto) = throw NotImplementedError("unused")

    override suspend fun getMe(): Response<CallerProfileResponseDto> {
      meCallCount++
      meExceptionToThrow?.let { throw it }
      return meResponse!!
    }
  }

  private fun profileData(
    displayName: String = "Jane Sakhi",
    mobileNumber: String? = "+919876543210",
    projectName: String? = "GEP-2324",
    cardNumber: String? = "EMP-00123",
    maskedBankAccount: String? = "•••1234",
  ) = CallerProfileData(
    id = "id-1",
    username = "jane.sakhi",
    displayName = displayName,
    mobileNumber = mobileNumber,
    roles = listOf(CallerRoleDto(roleCode = "SAKHI", projectId = "proj-1", geographyUnitId = "geo-1")),
    projectName = projectName,
    cardNumber = cardNumber,
    maskedBankAccount = maskedBankAccount,
  )

  private fun successResponse(data: CallerProfileData = profileData()) =
    Response.success(CallerProfileResponseDto(success = true, message = "OK", data = data))

  @Test
  fun `success maps every field onto CurrentUserProfile`() = runTest {
    val api = FakeAuthApi().apply { meResponse = successResponse() }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    val profile = repository.getProfile()

    assertEquals("jane.sakhi", profile?.username)
    assertEquals("Jane Sakhi", profile?.displayName)
    assertEquals("+919876543210", profile?.mobileNumber)
    assertEquals("GEP-2324", profile?.projectName)
    assertEquals("EMP-00123", profile?.cardNumber)
    assertEquals("•••1234", profile?.maskedBankAccount)
  }

  @Test
  fun `caches the result so a second call does not hit the network again`() = runTest {
    val api = FakeAuthApi().apply { meResponse = successResponse() }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    repository.getProfile()
    repository.getProfile()

    assertEquals(1, api.meCallCount)
  }

  @Test
  fun `clear resets the in-memory cache so the next call hits the network again`() = runTest {
    val api = FakeAuthApi().apply { meResponse = successResponse() }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())
    repository.getProfile()

    repository.clear()
    repository.getProfile()

    assertEquals(2, api.meCallCount)
  }

  @Test
  fun `401 unauthorized returns null when nothing was ever persisted`() = runTest {
    val json = """{"success":false,"message":"unauthenticated","errorCode":"UNAUTHENTICATED"}"""
    val api = FakeAuthApi().apply {
      meResponse = Response.error(401, json.toResponseBody("application/json".toMediaType()))
    }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    assertNull(repository.getProfile())
  }

  @Test
  fun `success envelope with success false returns null`() = runTest {
    val api = FakeAuthApi().apply {
      meResponse = Response.success(CallerProfileResponseDto(success = false, message = "nope", data = null))
    }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    assertNull(repository.getProfile())
  }

  @Test
  fun `blank display name returns null instead of a profile with a blank name`() = runTest {
    val api = FakeAuthApi().apply { meResponse = successResponse(profileData(displayName = "  ")) }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    assertNull(repository.getProfile())
  }

  @Test
  fun `blank optional fields are normalized to null rather than kept as blank strings`() = runTest {
    val api = FakeAuthApi().apply {
      meResponse = successResponse(profileData(mobileNumber = "  ", projectName = "", cardNumber = null))
    }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    val profile = repository.getProfile()

    assertNull(profile?.mobileNumber)
    assertNull(profile?.projectName)
    assertNull(profile?.cardNumber)
  }

  @Test
  fun `network failure returns null when nothing was ever persisted`() = runTest {
    val api = FakeAuthApi().apply { meExceptionToThrow = IOException("timeout") }
    val repository = RemoteCurrentUserRepository(api, FakeSecureKeyValueStore())

    assertNull(repository.getProfile())
  }

  @Test
  fun `successful fetch persists the full profile to disk`() = runTest {
    val store = FakeSecureKeyValueStore()
    val api = FakeAuthApi().apply { meResponse = successResponse() }
    RemoteCurrentUserRepository(api, store).getProfile()

    // A fresh repository instance (simulating an app restart) with the same on-disk store must
    // see the profile even without a network call — this is the bug we're fixing (SG-1).
    val restarted = RemoteCurrentUserRepository(
      FakeAuthApi().apply { meExceptionToThrow = IOException("offline") },
      store,
    )
    val profile = restarted.getProfile()
    assertEquals("Jane Sakhi", profile?.displayName)
    assertEquals("GEP-2324", profile?.projectName)
    assertEquals("EMP-00123", profile?.cardNumber)
    assertEquals("•••1234", profile?.maskedBankAccount)
  }

  @Test
  fun `falls back to the persisted profile when a later live fetch fails`() = runTest {
    val store = FakeSecureKeyValueStore()
    val onlineApi = FakeAuthApi().apply { meResponse = successResponse() }
    RemoteCurrentUserRepository(onlineApi, store).getProfile() // first launch, online

    val offlineApi = FakeAuthApi().apply { meExceptionToThrow = IOException("offline") }
    val afterGoingOffline = RemoteCurrentUserRepository(offlineApi, store)

    assertEquals("Jane Sakhi", afterGoingOffline.getProfile()?.displayName)
  }

  @Test
  fun `clear removes the persisted profile too, not just the in-memory cache`() = runTest {
    val store = FakeSecureKeyValueStore()
    val onlineApi = FakeAuthApi().apply { meResponse = successResponse() }
    val repository = RemoteCurrentUserRepository(onlineApi, store)
    repository.getProfile()

    repository.clear()

    val offlineApi = FakeAuthApi().apply { meExceptionToThrow = IOException("offline") }
    val nextSakhi = RemoteCurrentUserRepository(offlineApi, store)
    assertNull(nextSakhi.getProfile())
  }

  @Test
  fun `clearIfDifferentUser is a no-op when the persisted profile belongs to the same user`() = runTest {
    val store = FakeSecureKeyValueStore()
    val onlineApi = FakeAuthApi().apply { meResponse = successResponse(profileData()) } // username jane.sakhi
    val repository = RemoteCurrentUserRepository(onlineApi, store)
    repository.getProfile()

    repository.clearIfDifferentUser("jane.sakhi") // same Sakhi logging back in

    val offlineApi = FakeAuthApi().apply { meExceptionToThrow = IOException("offline") }
    val sameSakhiOffline = RemoteCurrentUserRepository(offlineApi, store)
    assertEquals("Jane Sakhi", sameSakhiOffline.getProfile()?.displayName)
  }

  @Test
  fun `clearIfDifferentUser wipes the cache when a different user logs in`() = runTest {
    val store = FakeSecureKeyValueStore()
    val onlineApi = FakeAuthApi().apply { meResponse = successResponse(profileData()) } // username jane.sakhi
    val repository = RemoteCurrentUserRepository(onlineApi, store)
    repository.getProfile()

    repository.clearIfDifferentUser("other.sakhi")

    val offlineApi = FakeAuthApi().apply { meExceptionToThrow = IOException("offline") }
    val differentSakhiOffline = RemoteCurrentUserRepository(offlineApi, store)
    assertNull(differentSakhiOffline.getProfile())
  }
}
