package org.armman.sakhi.data.auth.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal encrypted key-value abstraction. [SessionStore] and [OfflineCredentialCache] depend on
 * this interface rather than `SharedPreferences` directly so their own logic (serialization,
 * corrupted-data handling, same-user policy) can be unit-tested with an in-memory fake — this
 * repo has no Robolectric/instrumented test setup, so the real Android Keystore-backed
 * implementation below is intentionally left uncovered by JVM unit tests; verify it manually
 * on-device.
 */
interface SecureKeyValueStore {
  fun getString(key: String): String?
  fun putString(key: String, value: String)
  fun remove(key: String)
}

private const val PREFS_FILE_NAME = "auth_secure_prefs"

/** Android Keystore-backed implementation. Not unit-testable in this repo's JVM-only test setup. */
@Singleton
class EncryptedSharedPreferencesStore @Inject constructor(
  @ApplicationContext context: Context,
) : SecureKeyValueStore {

  private val prefs by lazy {
    val masterKey = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    EncryptedSharedPreferences.create(
      context,
      PREFS_FILE_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  override fun getString(key: String): String? = prefs.getString(key, null)

  override fun putString(key: String, value: String) {
    prefs.edit().putString(key, value).apply()
  }

  override fun remove(key: String) {
    prefs.edit().remove(key).apply()
  }
}
