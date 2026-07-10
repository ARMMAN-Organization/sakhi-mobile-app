package org.armman.sakhi.data.profile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticProfileRepositoryTest {
  private val repository = StaticProfileRepository()

  @Test
  fun `returns a complete profile with masked bank account`() = runTest {
    val profile = repository.getProfile()

    assertFalse(profile.name.isBlank())
    assertFalse(profile.sakhiId.isBlank())
    assertFalse(profile.projectName.isBlank())
    assertFalse(profile.cardNumber.isBlank())
    assertFalse(profile.mobileNumber.isBlank())
    assertTrue(profile.maskedBankAccount.startsWith("*"))
  }
}
