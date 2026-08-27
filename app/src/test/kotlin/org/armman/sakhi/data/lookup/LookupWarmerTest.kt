package org.armman.sakhi.data.lookup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupWarmerTest {

  @Test
  fun `warms exactly the submit-critical categories`() = runTest {
    val lookups = FakeLookupRepository()
    LookupWarmer(lookups).warmSubmitCriticalCategories()

    assertEquals(listOf("CASE_TYPE", "BENEFICIARY_TYPE"), lookups.requestedCategories)
  }

  @Test
  fun `a failing category does not stop the others from being warmed`() = runTest {
    // Repository throwing on the first category must not prevent the second from being fetched.
    val lookups = object : LookupRepository {
      val requested = mutableListOf<String>()
      override suspend fun getValues(categoryCode: String): List<LookupValue> {
        requested += categoryCode
        if (categoryCode == "CASE_TYPE") error("boom")
        return emptyList()
      }
    }

    LookupWarmer(lookups).warmSubmitCriticalCategories()

    assertTrue(lookups.requested.contains("CASE_TYPE"))
    assertTrue(lookups.requested.contains("BENEFICIARY_TYPE"))
  }
}
