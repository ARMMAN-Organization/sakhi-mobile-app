package org.armman.sakhi.data.auth.session

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.armman.sakhi.data.auth.UserSession
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw wire shape, every field nullable. Gson instantiates Kotlin data classes via reflection
 * (bypassing the constructor), so a JSON value that's missing a key — including one written by
 * an older build of this app, before a field existed — silently becomes `null` at runtime no
 * matter what the Kotlin type says. [OfflineCredentialCache.readEntry] validates every field is
 * actually present before treating the entry as usable; never deserialize straight into a
 * non-null-typed class here (see the identical note on `RawJwtClaims`).
 */
private data class RawCachedSession(
  @SerializedName("username") val username: String?,
  @SerializedName("subjectId") val subjectId: String?,
  @SerializedName("roles") val roles: List<String>?,
  @SerializedName("projectId") val projectId: String?,
  @SerializedName("geographyUnitId") val geographyUnitId: String?,
  @SerializedName("accessToken") val accessToken: String?,
  @SerializedName("refreshToken") val refreshToken: String?,
  @SerializedName("accessTokenExpiresAtEpochSeconds") val accessTokenExpiresAtEpochSeconds: Long?,
)

private data class RawCachedCredentialEntry(
  @SerializedName("username") val username: String?,
  @SerializedName("saltBase64") val saltBase64: String?,
  @SerializedName("hashBase64") val hashBase64: String?,
  @SerializedName("session") val session: RawCachedSession?,
)

/** Validated, safe-to-use shape — only ever constructed after every required field is confirmed
 * non-null (see [OfflineCredentialCache.readEntry]). */
private data class CachedCredentialEntry(
  val username: String,
  val saltBase64: String,
  val hashBase64: String,
  val session: UserSession,
)

private const val KEY_ENTRY = "offline_cred_entry"

// PBKDF2 parameters for the offline password hash. Kept together here so the security posture is
// reviewed and updated in one place. Rationale:
//  - PBKDF2WithHmacSHA256: the strongest PBKDF2 variant available across all supported Android
//    API levels without a third-party crypto dependency.
//  - 120_000 iterations: aligned with OWASP's PBKDF2-SHA256 guidance (>= 120k); a deliberate
//    balance between brute-force cost and the latency a field worker will tolerate at login on
//    low-end devices. Raising this is safe (old entries just re-hash on next successful login),
//    but must stay >= the OWASP floor.
//  - 256-bit derived key + 16-byte random salt per entry.
// If any of these change, existing cached entries remain verifiable only while the same values
// are used to re-derive — a change invalidates the current offline cache until the next online
// login re-seeds it, which is acceptable (offline re-login simply falls back to "no cache").
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val ITERATIONS = 120_000
private const val KEY_LENGTH_BITS = 256
private const val SALT_LENGTH_BYTES = 16

/**
 * Caches a salted hash of the last successful online login, plus a snapshot of that login's
 * session, so the same Sakhi can log back in with no connectivity (per SRS "offline credential
 * cache" — field workers often have none) — including right after a logout performed while
 * offline, when there is no other copy of that session left anywhere.
 *
 * Deliberately same-user-only: a single stored entry, overwritten on each new successful online
 * login. Never persists the plaintext password anywhere. Survives a normal [SessionStore.clearSession]
 * (logout) — it's only cleared by an explicit account-removal action, which this app doesn't
 * expose in the UI yet. This is deliberately independent of [SessionStore]: logout must still
 * clear the "am I currently logged in" state (so the login form reappears), without touching
 * what offline re-login needs to restore.
 *
 * Any unreadable, partial, or schema-mismatched cache entry (e.g. left over from an older build)
 * is treated exactly like "nothing cached" — it must never crash the app.
 */
@Singleton
class OfflineCredentialCache @Inject constructor(
  private val store: SecureKeyValueStore,
) {
  private val secureRandom = SecureRandom()
  private val gson = Gson()

  /** Overwrites any previously cached entry — same-user-only, one slot. Stores [session]
   * verbatim so a later offline login can restore it without depending on [SessionStore]. */
  fun store(username: String, password: CharArray, session: UserSession) {
    val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
    val hash = deriveHash(password, salt)
    val entry = RawCachedCredentialEntry(
      username = username,
      saltBase64 = encode(salt),
      hashBase64 = encode(hash),
      session = RawCachedSession(
        username = session.username,
        subjectId = session.subjectId,
        roles = session.roles,
        projectId = session.projectId,
        geographyUnitId = session.geographyUnitId,
        accessToken = session.accessToken,
        refreshToken = session.refreshToken,
        accessTokenExpiresAtEpochSeconds = session.accessTokenExpiresAtEpochSeconds,
      ),
    )
    store.putString(KEY_ENTRY, gson.toJson(entry))
  }

  /** True only if [username] matches the cached entry's username exactly and [password] hashes
   * to the same value. False for a wrong password, a different/missing username, or no
   * readable cache at all. */
  fun verify(username: String, password: CharArray): Boolean = matchingEntry(username, password) != null

  /** Verifies [username]/[password] against the cache and, on a match, returns the session
   * snapshot captured at the last successful online login — the offline login path's only
   * source of a session, since it must work even when [SessionStore] was already cleared by a
   * logout performed while offline. Null on any mismatch, missing field, or unreadable cache. */
  fun verifyAndRestoreSession(username: String, password: CharArray): UserSession? =
    matchingEntry(username, password)?.session

  private fun matchingEntry(username: String, password: CharArray): CachedCredentialEntry? {
    val entry = readEntry() ?: return null
    if (entry.username != username) return null
    val salt = try {
      decode(entry.saltBase64)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val expectedHash = try {
      decode(entry.hashBase64)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val actualHash = deriveHash(password, salt)
    return if (actualHash.contentEquals(expectedHash)) entry else null
  }

  /** Parses the stored JSON and validates every field the app needs is actually present.
   * Returns null — never throws — for missing keys, wrong types, corrupted JSON, or an entry
   * written by an older, incompatible build. */
  private fun readEntry(): CachedCredentialEntry? {
    val json = store.getString(KEY_ENTRY) ?: return null
    val raw = try {
      gson.fromJson(json, RawCachedCredentialEntry::class.java)
    } catch (e: Exception) {
      // Gson can throw several unchecked exception types for malformed JSON
      // (JsonSyntaxException, JsonParseException, IllegalStateException, ...) — a
      // read of locally-cached data must never crash the app regardless of which one.
      null
    } ?: return null

    val session = raw.session ?: return null
    val restoredSession = UserSession(
      username = session.username ?: return null,
      subjectId = session.subjectId ?: return null,
      roles = session.roles ?: return null,
      projectId = session.projectId,
      geographyUnitId = session.geographyUnitId,
      accessToken = session.accessToken ?: return null,
      refreshToken = session.refreshToken ?: return null,
      accessTokenExpiresAtEpochSeconds = session.accessTokenExpiresAtEpochSeconds ?: return null,
    )
    return CachedCredentialEntry(
      username = raw.username ?: return null,
      saltBase64 = raw.saltBase64 ?: return null,
      hashBase64 = raw.hashBase64 ?: return null,
      session = restoredSession,
    )
  }

  /** Whether any *readable* credential is cached — used to distinguish "wrong password" from
   * "never logged in here before" (or "cache from an old, incompatible build") when offline. */
  fun hasAnyEntry(): Boolean = readEntry() != null

  /** Not wired to any UI yet — reserved for a future explicit "remove account" action. */
  fun clear() {
    store.remove(KEY_ENTRY)
  }

  private fun deriveHash(password: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
    val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
    return factory.generateSecret(spec).encoded
  }

  private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
  private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
