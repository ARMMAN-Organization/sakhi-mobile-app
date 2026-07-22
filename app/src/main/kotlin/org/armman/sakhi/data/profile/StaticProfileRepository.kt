package org.armman.sakhi.data.profile

import kotlinx.coroutines.delay
import org.armman.sakhi.data.auth.CurrentUserRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the profile API, mirroring the Figma reference data (p42/p43) — except
 * [SakhiProfile.name], [SakhiProfile.projectName], [SakhiProfile.cardNumber],
 * [SakhiProfile.mobileNumber], and [SakhiProfile.maskedBankAccount], which now come from the
 * real `/me` endpoint via [CurrentUserRepository]. Each field falls back to its static
 * placeholder independently if `/me` hasn't returned a value for it yet. [SakhiProfile.sakhiId]
 * stays hardcoded — `/me` has no equivalent field for it. Delete the rest once the real endpoint
 * exists — only DI references this class.
 */
@Singleton
class StaticProfileRepository @Inject constructor(
  private val currentUserRepository: CurrentUserRepository,
) : ProfileRepository {

  override suspend fun getProfile(): SakhiProfile {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    val liveProfile = currentUserRepository.getProfile()
    return SakhiProfile(
      name = liveProfile?.displayName ?: "Tarini Swaraj",
      sakhiId = "12345678", // No sakhiId-equivalent field on /me yet.
      projectName = liveProfile?.projectName ?: "Project_Name",
      cardNumber = liveProfile?.cardNumber ?: "AFCPC7070A",
      mobileNumber = liveProfile?.mobileNumber ?: "0987654321",
      maskedBankAccount = liveProfile?.maskedBankAccount ?: "*******431 HDFC Bank Ltd.",
    )
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 300L
  }
}
