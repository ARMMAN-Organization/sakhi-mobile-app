package org.armman.sakhi.data.auth

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** The access token isn't a well-formed JWT (wrong segment count) — can't extract claims. */
class JwtDecodeException(message: String) : Exception(message)

/** Claims this app reads out of the access token payload. Shape confirmed from a live token:
 * `{sub, roles:[...], projectId, geographyUnitId, iat, exp}` — no username/display-name claim. */
data class JwtClaims(
  val subjectId: String,
  val roles: List<String>,
  val projectId: String?,
  val geographyUnitId: String?,
  val issuedAtEpochSeconds: Long,
  val expiresAtEpochSeconds: Long,
)

/**
 * Mirrors the raw wire shape with every field nullable. Gson instantiates Kotlin data classes
 * via reflection (bypassing the constructor), so Kotlin default values and non-null types are
 * NOT enforced by Gson itself — a missing `roles` key would silently become `null` at runtime
 * despite the type system, unless every field here is nullable and [JwtClaimsDecoder] coerces
 * explicitly. Never use a non-null-with-defaults data class as a direct Gson target.
 */
private data class RawJwtClaims(
  @SerializedName("sub") val subjectId: String?,
  @SerializedName("roles") val roles: List<String>?,
  @SerializedName("projectId") val projectId: String?,
  @SerializedName("geographyUnitId") val geographyUnitId: String?,
  @SerializedName("iat") val issuedAtEpochSeconds: Long?,
  @SerializedName("exp") val expiresAtEpochSeconds: Long?,
)

/**
 * Reads claims out of an access token's payload segment for local UI/session use.
 *
 * **This deliberately does not verify the RS256 signature** — that requires the server's
 * public key, which the app doesn't have and shouldn't need. Signature verification is the
 * server's job on every subsequent authenticated request; this decoder only exists so the app
 * can show a role-appropriate UI and know when to silently refresh, never as an auth boundary.
 */
@Singleton
class JwtClaimsDecoder @Inject constructor() {
  private val gson = Gson()

  fun decode(accessToken: String): JwtClaims {
    val segments = accessToken.split(".")
    if (segments.size != 3) {
      throw JwtDecodeException("Access token has ${segments.size} segments, expected 3.")
    }
    val payloadJson = try {
      String(Base64.getUrlDecoder().decode(padBase64Url(segments[1])))
    } catch (e: IllegalArgumentException) {
      throw JwtDecodeException("Access token payload is not valid base64url.")
    }
    val raw = try {
      gson.fromJson(payloadJson, RawJwtClaims::class.java)
        ?: throw JwtDecodeException("Access token payload decoded to null.")
    } catch (e: com.google.gson.JsonSyntaxException) {
      throw JwtDecodeException("Access token payload is not valid JSON.")
    }
    return JwtClaims(
      subjectId = raw.subjectId.orEmpty(),
      roles = raw.roles.orEmpty(),
      projectId = raw.projectId,
      geographyUnitId = raw.geographyUnitId,
      issuedAtEpochSeconds = raw.issuedAtEpochSeconds ?: 0L,
      expiresAtEpochSeconds = raw.expiresAtEpochSeconds ?: 0L,
    )
  }

  /** `Base64.getUrlDecoder()` requires padding; JWT segments omit it per RFC 7515. */
  private fun padBase64Url(segment: String): String {
    val remainder = segment.length % 4
    return if (remainder == 0) segment else segment + "=".repeat(4 - remainder)
  }
}
