package org.armman.sakhi.data.rules

import android.util.Log
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"

/**
 * [RuleEvaluator] backed by GoRules' official on-device engine (`io.gorules:zen-engine-kotlin-android`,
 * added in `gradle/libs.versions.toml` as `zenEngineAndroid`).
 *
 * ⚠ **TEMPORARILY STUBBED — the real engine call is commented out below, not deleted.**
 * `implementation(libs.gorules.zen.engine)` in `app/build.gradle.kts` is currently commented out:
 * that artifact ships classes with Kotlin metadata version 2.1.0, and this project's pinned Room
 * (2.6.1, KAPT-based) bundles a `kotlinx-metadata-jvm` reader that only reads up to 2.0.0 — so
 * having the dependency on the classpath at all breaks `:app:kaptDebugKotlin` while Room processes
 * DAOs (confirmed 2026-08-12: removing just that one line fixes the build; nothing else changed).
 * Restoring this class requires, in order: (1) resolving that Room/KAPT conflict — bump Room past
 * 2.6.1, or move Room/Hilt off KAPT onto KSP; (2) uncommenting the dependency line; (3) uncommenting
 * the real `evaluate()` body below and deleting this stub's `return null`.
 *
 * Safe to ship in this state: [org.armman.sakhi.data.schedule.GoRulesScheduleFeatureFlag.ENABLED]
 * is `false`, so nothing calls [evaluate] today — every caller already treats a null result the
 * same as "no cached rule" and falls back to [org.armman.sakhi.data.schedule.HardcodedRuleSource].
 *
 * ⚠ **The real implementation itself remains PROTOTYPE — verify against the library before relying
 * on it.** The exact Kotlin API this artifact generates (via UniFFI from the Rust `zen` core) was
 * not independently confirmed at the time the code below was written — only the Rust source
 * (`ZenEngine.createDecision(content) -> ZenDecision`, `ZenDecision.evaluate(context, options?) ->
 * ZenEngineResponse`, both operating on a `JsonBuffer` wrapper type) was located, not a
 * compiled/generated Kotlin signature. This is exactly the Step 1 trial from the CR-032 plan: once
 * the dependency is restored, open this class in Android Studio, "Go to declaration" on `ZenEngine`,
 * and fix any name that doesn't match — this file is the *only* place such a fix would be needed,
 * by design (see [RuleEvaluator]'s doc).
 *
 * If the official binding turns out unworkable (too slow/large on a low-end device, API too
 * unstable), this file is also the only one that needs replacing with a custom JDM-subset
 * interpreter — nothing in [org.armman.sakhi.data.schedule.GoRulesScheduleAdapter] would change.
 */
@Singleton
class ZenRuleEvaluator @Inject constructor() : RuleEvaluator {

  override suspend fun evaluate(rulesJson: JsonObject, context: JsonObject): JsonObject? {
    Log.w(TAG, "ZenRuleEvaluator.evaluate: engine dependency disabled (Room/KAPT metadata conflict) — returning null")
    return null

    // Real implementation — restore once the dependency is back and the API is confirmed:
    //
    // return try {
    //   val decision = engine.createDecision(rulesJson.toString().toByteArray())
    //   val response = decision.evaluate(context.toString().toByteArray())
    //   JsonParser.parseString(response.result.toString(Charsets.UTF_8)).asJsonObject
    // } catch (e: Exception) {
    //   // Malformed graph, engine error, or an API mismatch from this class's own doc caveat — all
    //   // treated as "could not evaluate"; callers fall back the same way they would for no cached
    //   // rule at all. Never let a rule-engine failure crash a form or block an enrolment.
    //   Log.w(TAG, "ZenRuleEvaluator.evaluate: threw ${e::class.simpleName} — ${e.message}")
    //   null
    // }
  }

  // One engine instance reused across evaluations — matches the pattern of every other singleton
  // client in this app (Retrofit, Room) rather than constructing one per call. Restore alongside
  // the real evaluate() body above.
  // private val engine by lazy { ZenEngine() }
}
