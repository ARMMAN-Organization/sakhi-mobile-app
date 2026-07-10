package org.armman.sakhi.data.profile

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static stand-in for the profile API, mirroring the Figma reference data
 * (p42/p43). Delete once the real endpoint exists — only DI references it.
 */
@Singleton
class StaticProfileRepository @Inject constructor() : ProfileRepository {

  override suspend fun getProfile(): SakhiProfile {
    delay(NETWORK_LATENCY_MS) // Simulate a round trip so the loading state is visible.
    return SakhiProfile(
      name = "Tarini Swaraj",
      sakhiId = "12345678",
      projectName = "Project_Name",
      cardNumber = "AFCPC7070A",
      mobileNumber = "0987654321",
      maskedBankAccount = "*******431 HDFC Bank Ltd.",
    )
  }

  private companion object {
    const val NETWORK_LATENCY_MS = 300L
  }
}
