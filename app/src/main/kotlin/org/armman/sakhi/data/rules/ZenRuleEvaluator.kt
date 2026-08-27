package org.armman.sakhi.data.rules

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.gorules.zen_engine.kotlin.JsonBuffer
import io.gorules.zen_engine.kotlin_android.ZenCustomNodeCallback
import io.gorules.zen_engine.kotlin_android.ZenDecisionLoaderCallback
import io.gorules.zen_engine.kotlin_android.ZenEngine
import io.gorules.zen_engine.kotlin_android.ZenEngineHandlerRequest
import io.gorules.zen_engine.kotlin_android.ZenEngineHandlerResponse
import io.gorules.zen_engine.kotlin_android.ZenEvaluateOptions
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"

/**
 * [RuleEvaluator] backed by GoRules' official on-device engine (`io.gorules:zen-engine-kotlin-android`,
 * added in `gradle/libs.versions.toml` as `zenEngineAndroid`).
 *
 * Re-enabled 2026-08-13 after moving Room/Hilt annotation processing from KAPT to KSP (see the
 * `ksp` version comment in `gradle/libs.versions.toml`) — KAPT's bundled `kotlinx-metadata-jvm`
 * reader only supported Kotlin metadata up to 2.0.0, and this artifact ships metadata 2.1.0.
 *
 * The Kotlin API below is now confirmed against the compiled `zen-engine-kotlin-android-0.7.2.aar`
 * itself (via `javap` on its `classes.jar` — no sources/API docs are published for this artifact),
 * not just the Rust `zen` core source the first prototype of this class was written against.
 * Differences from that prototype:
 *  - The real package is `io.gorules.zen_engine.kotlin_android` — plus a separate
 *    `io.gorules.zen_engine.kotlin` package holding only the shared [JsonBuffer] value type.
 *  - [ZenEngine]'s public constructor requires a [ZenDecisionLoaderCallback] and a
 *    [ZenCustomNodeCallback]. Neither is exercised by this app's usage (one self-contained
 *    decision graph per evaluation, no cross-graph references, no custom/function nodes), so both
 *    are wired to throw loudly if the engine ever does call them — better than silently returning
 *    a wrong result.
 *  - `ZenEngine.createDecision` / `ZenDecision.evaluate` take and return the value-class
 *    [JsonBuffer] wrapper, not a raw `ByteArray` — construct one from a `String`, read one back
 *    with `.toString()`.
 *  - `evaluate()` is a suspend fun on `ZenDecision` (returned by `createDecision`), not on
 *    [ZenEngine] itself; `createDecision` itself is a plain (non-suspend) call.
 *
 * Still worth a second pair of eyes before this goes out to real Sakhis: this confirms the API
 * *compiles* against the real artifact, not that its runtime behavior (decision-graph semantics,
 * perf on a low-end device) matches what CR-032 needs — that's the differential test suite
 * ([org.armman.sakhi.data.schedule.GoRulesScheduleFeatureFlag]'s own checklist) and the offline/perf
 * test, both still open.
 */
@Singleton
class ZenRuleEvaluator @Inject constructor() : RuleEvaluator {

  // One engine instance reused across evaluations — matches the pattern of every other singleton
  // client in this app (Retrofit, Room) rather than constructing one per call. Native resources
  // (the underlying Rust engine) are released via [ZenEngine.close]/[ZenEngine.destroy] only if
  // this class is ever torn down early; in practice it lives for the process lifetime like the
  // other singletons above, so that's not wired up here.
  private val engine: ZenEngine by lazy {
    ZenEngine(
      object : ZenDecisionLoaderCallback {
        override suspend fun load(key: String): JsonBuffer {
          // This app never evaluates a graph that references another graph by key — each
          // family's rulesJson is a single self-contained decision graph. If this fires, either
          // the seeded rule pack changed shape or we mis-scoped what "self-contained" means; fail
          // loudly rather than silently return an empty/wrong sub-graph.
          throw UnsupportedOperationException(
            "ZenRuleEvaluator: no decision loader configured — graph referenced key \"$key\""
          )
        }
      },
      object : ZenCustomNodeCallback {
        override suspend fun handle(request: ZenEngineHandlerRequest): ZenEngineHandlerResponse {
          // Same reasoning: none of the seeded ANC/PP/NN/INC/CCV/HR/Escalation graphs use custom
          // ("function") nodes today. If one starts using one, this needs real handling, not a
          // silent no-op.
          throw UnsupportedOperationException(
            "ZenRuleEvaluator: no custom node handler configured for node ${request.node}"
          )
        }
      },
    )
  }

  override suspend fun evaluate(rulesJson: JsonObject, context: JsonObject): JsonObject? {
    return try {
      engine.createDecision(JsonBuffer(rulesJson.toString())).use { decision ->
        val response = decision.evaluate(
          JsonBuffer(context.toString()),
          // Both params are required by this artifact (no defaults) — maxDepth caps recursive
          // sub-graph traversal, irrelevant here since [ZenDecisionLoaderCallback] above always
          // throws (no cross-graph references in any seeded family), so any value that isn't
          // absurdly low is fine. trace=false: no caller reads [ZenEngineResponse.trace] today.
          ZenEvaluateOptions(maxDepth = 5u, trace = false),
        )
        JsonParser.parseString(response.result.toString()).asJsonObject
      }
    } catch (e: Exception) {
      // Malformed graph, engine error, an unhandled loader/custom-node call above, or a shape the
      // caller doesn't understand — all treated as "could not evaluate"; callers fall back the
      // same way they would for no cached rule at all. Never let a rule-engine failure crash a
      // form or block an enrolment.
      Log.w(TAG, "ZenRuleEvaluator.evaluate: threw ${e::class.simpleName} — ${e.message}")
      null
    }
  }
}
