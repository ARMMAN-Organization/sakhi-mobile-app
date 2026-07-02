package org.armman.sakhi.data.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticAuthRepositoryTest {
  private val repository = StaticAuthRepository()

  @Test
  fun `valid credentials return success with session`() = runTest {
    val result = repository.login(LoginRequest(userId = "sakhi01", password = "Sakhi@123"))

    assertTrue(result is LoginResult.Success)
    val session = (result as LoginResult.Success).session
    assertEquals("sakhi01", session.userId)
    assertEquals("SAKHI", session.role)
    assertTrue(session.accessToken.isNotBlank())
  }

  @Test
  fun `user id match is case-insensitive`() = runTest {
    val result = repository.login(LoginRequest(userId = "SAKHI01", password = "Sakhi@123"))
    assertTrue(result is LoginResult.Success)
  }

  @Test
  fun `wrong password fails with invalid credentials`() = runTest {
    val result = repository.login(LoginRequest(userId = "sakhi01", password = "wrong"))

    assertTrue(result is LoginResult.Failure)
    assertEquals(LoginFailureReason.INVALID_CREDENTIALS, (result as LoginResult.Failure).reason)
  }

  @Test
  fun `unknown user fails with invalid credentials`() = runTest {
    val result = repository.login(LoginRequest(userId = "nobody", password = "Sakhi@123"))

    assertTrue(result is LoginResult.Failure)
    assertEquals(LoginFailureReason.INVALID_CREDENTIALS, (result as LoginResult.Failure).reason)
  }

  @Test
  fun `password match is case-sensitive`() = runTest {
    val result = repository.login(LoginRequest(userId = "sakhi01", password = "sakhi@123"))
    assertTrue(result is LoginResult.Failure)
  }
}
