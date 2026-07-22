package org.armman.sakhi.data.profile

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.auth.CurrentUserProfile
import org.armman.sakhi.data.auth.FakeCurrentUserRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticProfileRepositoryTest {
  private val currentUserRepository = FakeCurrentUserRepository(displayName = null)
  private val repository = StaticProfileRepository(currentUserRepository)

  @Test
  fun `falls back to the static name and returns a complete profile otherwise`() = runTest {
    val profile = repository.getProfile()

    assertFalse(profile.name.isBlank())
    assertEquals("Tarini Swaraj", profile.name)
    assertFalse(profile.sakhiId.isBlank())
    assertFalse(profile.projectName.isBlank())
    assertFalse(profile.cardNumber.isBlank())
    assertFalse(profile.mobileNumber.isBlank())
    assertTrue(profile.maskedBankAccount.startsWith("*"))
  }

  @Test
  fun `uses the me endpoint's fields when available`() = runTest {
    currentUserRepository.profile = CurrentUserProfile(
      username = "jane.sakhi",
      displayName = "Jane Sakhi",
      mobileNumber = "+919876543210",
      projectName = "GEP-2324",
      cardNumber = "EMP-00123",
      maskedBankAccount = "••••1234",
    )

    val profile = repository.getProfile()

    assertEquals("Jane Sakhi", profile.name)
    assertEquals("+919876543210", profile.mobileNumber)
    assertEquals("GEP-2324", profile.projectName)
    assertEquals("EMP-00123", profile.cardNumber)
    assertEquals("••••1234", profile.maskedBankAccount)
    // sakhiId has no /me equivalent yet — still the static placeholder.
    assertEquals("12345678", profile.sakhiId)
  }

  @Test
  fun `falls back to static values field-by-field when the live profile is partial`() = runTest {
    currentUserRepository.profile = CurrentUserProfile(
      username = "jane.sakhi",
      displayName = "Jane Sakhi",
      mobileNumber = null,
      projectName = null,
      cardNumber = null,
      maskedBankAccount = null,
    )

    val profile = repository.getProfile()

    assertEquals("Jane Sakhi", profile.name)
    assertEquals("0987654321", profile.mobileNumber)
    assertEquals("Project_Name", profile.projectName)
    assertEquals("AFCPC7070A", profile.cardNumber)
    assertEquals("*******431 HDFC Bank Ltd.", profile.maskedBankAccount)
  }
}
