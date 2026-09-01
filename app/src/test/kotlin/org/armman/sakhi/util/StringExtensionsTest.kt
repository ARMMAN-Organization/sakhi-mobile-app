package org.armman.sakhi.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StringExtensionsTest {

  @Test
  fun `capitalizes each word of an all-lowercase name`() {
    assertEquals("Sunita Sharma", "sunita sharma".toTitleCase())
  }

  @Test
  fun `lower-cases the rest of an all-caps name`() {
    assertEquals("Sunita Sharma", "SUNITA SHARMA".toTitleCase())
  }

  @Test
  fun `leaves an already title-cased name unchanged`() {
    assertEquals("Sunita Sharma", "Sunita Sharma".toTitleCase())
  }

  @Test
  fun `handles a single word name`() {
    assertEquals("Sunita", "sunita".toTitleCase())
  }

  @Test
  fun `collapses no extra spacing and handles blank input`() {
    assertEquals("", "".toTitleCase())
  }
}
