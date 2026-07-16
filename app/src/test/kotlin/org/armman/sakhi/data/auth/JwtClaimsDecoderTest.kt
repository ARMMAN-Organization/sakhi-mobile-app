package org.armman.sakhi.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JwtClaimsDecoderTest {
  private lateinit var decoder: JwtClaimsDecoder

  // Hand-built tokens matching the real payload shape {sub, roles, projectId, geographyUnitId,
  // iat, exp} — header/signature are placeholders since this decoder never checks them (JW-6).
  private val tokenWithNullIds =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAidGVzdC1zdWIiLCAicm9sZXMiOiBbIlNBS0hJIl0sICJwcm9qZWN0SWQiOiBudWxsLCAiZ2VvZ3JhcGh5VW5pdElkIjogbnVsbCwgImlhdCI6IDEwMDAsICJleHAiOiAyMDAwfQ." +
      "sig"
  private val tokenWithRealIds =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAidGVzdC1zdWIiLCAicm9sZXMiOiBbIlNBS0hJIl0sICJwcm9qZWN0SWQiOiAicHJvai0xIiwgImdlb2dyYXBoeVVuaXRJZCI6ICJnZW8tMSIsICJpYXQiOiAxMDAwLCAiZXhwIjogMjAwMH0." +
      "sig"
  private val tokenWithoutRolesKey =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAidGVzdC1zdWIiLCAicHJvamVjdElkIjogbnVsbCwgImdlb2dyYXBoeVVuaXRJZCI6IG51bGwsICJpYXQiOiAxMDAwLCAiZXhwIjogMjAwMH0." +
      "sig"

  @Before
  fun setUp() {
    decoder = JwtClaimsDecoder()
  }

  @Test
  fun `decodes sub roles project and geography from valid JWT`() { // JW-1
    val claims = decoder.decode(tokenWithRealIds)

    assertEquals("test-sub", claims.subjectId)
    assertEquals(listOf("SAKHI"), claims.roles)
    assertEquals("proj-1", claims.projectId)
    assertEquals("geo-1", claims.geographyUnitId)
    assertEquals(1000L, claims.issuedAtEpochSeconds)
    assertEquals(2000L, claims.expiresAtEpochSeconds)
  }

  @Test
  fun `null projectId and geographyUnitId decode as null, not crash`() { // JW-2
    val claims = decoder.decode(tokenWithNullIds)

    assertNull(claims.projectId)
    assertNull(claims.geographyUnitId)
    assertEquals("test-sub", claims.subjectId)
  }

  @Test
  fun `roles missing entirely yields empty list, no crash`() { // JW-3
    val claims = decoder.decode(tokenWithoutRolesKey)

    assertTrue(claims.roles.isEmpty())
  }

  @Test
  fun `malformed token throws a typed decode error`() { // JW-4
    assertThrows(JwtDecodeException::class.java) {
      decoder.decode("not-a-jwt")
    }
  }

  @Test
  fun `malformed base64 payload throws a typed decode error`() {
    assertThrows(JwtDecodeException::class.java) {
      decoder.decode("header.###not-base64###.sig")
    }
  }
}
