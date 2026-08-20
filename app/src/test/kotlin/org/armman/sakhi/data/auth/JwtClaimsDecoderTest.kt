package org.armman.sakhi.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
  private val tokenWithoutSubKey =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJyb2xlcyI6IFsiU0FLSEkiXSwgInByb2plY3RJZCI6IG51bGwsICJnZW9ncmFwaHlVbml0SWQiOiBudWxsLCAiaWF0IjogMTAwMCwgImV4cCI6IDIwMDB9." +
      "sig"
  private val tokenWithBlankSub =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAiIiwgInJvbGVzIjogWyJTQUtISSJdLCAicHJvamVjdElkIjogbnVsbCwgImdlb2dyYXBoeVVuaXRJZCI6IG51bGwsICJpYXQiOiAxMDAwLCAiZXhwIjogMjAwMH0." +
      "sig"
  private val tokenWithoutExpKey =
    "eyJhbGciOiAiUlMyNTYifQ." +
      "eyJzdWIiOiAidGVzdC1zdWIiLCAicm9sZXMiOiBbIlNBS0hJIl0sICJwcm9qZWN0SWQiOiBudWxsLCAiZ2VvZ3JhcGh5VW5pdElkIjogbnVsbCwgImlhdCI6IDEwMDB9." +
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
  fun `roles claim missing entirely fails fast`() { // JW-3
    // A token with no `roles` claim must not decode to an empty-role session (which would then
    // fail the SAKHI role check with a misleading WRONG_ROLE) — it's a malformed token.
    assertThrows(JwtDecodeException::class.java) {
      decoder.decode(tokenWithoutRolesKey)
    }
  }

  @Test
  fun `sub claim missing fails fast`() { // JW-7
    assertThrows(JwtDecodeException::class.java) {
      decoder.decode(tokenWithoutSubKey)
    }
  }

  @Test
  fun `blank sub claim fails fast`() { // JW-8
    assertThrows(JwtDecodeException::class.java) {
      decoder.decode(tokenWithBlankSub)
    }
  }

  @Test
  fun `exp claim missing defaults to no expiry`() { // JW-9
    // The auth-service intentionally no longer issues an `exp` claim (tokens don't expire) —
    // a missing claim must decode successfully with a sentinel that
    // SessionStore.hasValidSession() always treats as not-expired, rather than failing login.
    val claims = decoder.decode(tokenWithoutExpKey)

    assertEquals(NO_EXPIRY_EPOCH_SECONDS, claims.expiresAtEpochSeconds)
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
