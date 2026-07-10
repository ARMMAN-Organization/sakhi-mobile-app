package org.armman.sakhi.data.profile

/**
 * Profile data boundary. UI depends only on this interface; the backing
 * implementation (static today, profile API later) is bound in DI.
 */
interface ProfileRepository {
  suspend fun getProfile(): SakhiProfile
}
