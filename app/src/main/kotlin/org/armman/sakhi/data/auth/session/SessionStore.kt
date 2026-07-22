package org.armman.sakhi.data.auth.session

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.armman.sakhi.data.auth.UserSession
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_SESSION_JSON = "session_json"

/** Wire shape persisted to disk — mirrors [UserSession]'s stored fields (the two derived
 * properties, `displayName`/`role`, are recomputed on read, not persisted). */
private data class PersistedSession(
  val username: String,
  val subjectId: String,
  val roles: List<String>,
  val projectId: String?,
  val geographyUnitId: String?,
  val accessToken: String,
  val refreshToken: String,
  val accessTokenExpiresAtEpochSeconds: Long,
)

/**
 * Persists the "stay logged in" session to encrypted storage. Deliberately separate from
 * [OfflineCredentialCache] — [clearSession] (used by a normal logout) must never touch the
 * offline cache, so a Sakhi who logs out can still log back in offline later.
 */
@Singleton
class SessionStore @Inject constructor(
  private val store: SecureKeyValueStore,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val gson = Gson()

  fun saveSession(session: UserSession) {
    val persisted = PersistedSession(
      username = session.username,
      subjectId = session.subjectId,
      roles = session.roles,
      projectId = session.projectId,
      geographyUnitId = session.geographyUnitId,
      accessToken = session.accessToken,
      refreshToken = session.refreshToken,
      accessTokenExpiresAtEpochSeconds = session.accessTokenExpiresAtEpochSeconds,
    )
    store.putString(KEY_SESSION_JSON, gson.toJson(persisted))
  }

  /** Returns null if nothing was ever saved, or if the stored value is unreadable/corrupted —
   * a corrupted store must never crash the app; it's treated the same as "not logged in". */
  fun readSession(): UserSession? {
    val json = store.getString(KEY_SESSION_JSON) ?: return null
    val persisted = try {
      gson.fromJson(json, PersistedSession::class.java)
    } catch (e: JsonSyntaxException) {
      null
    } ?: return null
    return UserSession(
      username = persisted.username,
      subjectId = persisted.subjectId,
      roles = persisted.roles,
      projectId = persisted.projectId,
      geographyUnitId = persisted.geographyUnitId,
      accessToken = persisted.accessToken,
      refreshToken = persisted.refreshToken,
      accessTokenExpiresAtEpochSeconds = persisted.accessTokenExpiresAtEpochSeconds,
    )
  }

  /**
   * Whether there is a stored session whose access token has NOT yet expired — the correct
   * signal for "skip the login form". A stored-but-expired session must NOT bypass login: the
   * app would otherwise show the dashboard on an expired token until the first authenticated
   * call is rejected by the server. Returns false for no session, an unreadable session, or an
   * expired one.
   */
  fun hasValidSession(): Boolean {
    val session = readSession() ?: return false
    return session.accessTokenExpiresAtEpochSeconds > clock.instant().epochSecond
  }

  /** Normal logout. Intentionally does not clear [OfflineCredentialCache]. */
  fun clearSession() {
    store.remove(KEY_SESSION_JSON)
  }
}
