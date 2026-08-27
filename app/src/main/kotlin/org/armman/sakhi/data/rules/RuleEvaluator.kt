package org.armman.sakhi.data.rules

import com.google.gson.JsonObject

/**
 * Runs one GoRules decision graph against one input object, fully on-device.
 *
 * This is the seam between the app's rule-source logic and whichever local execution mechanism
 * actually runs a decision graph — deliberately the *only* file in the app that references the
 * third-party rule engine directly. [org.armman.sakhi.data.schedule.GoRulesRuleSource] depends on
 * this interface, never on the engine library itself, for the same reason
 * [org.armman.sakhi.data.schedule.ScheduleRuleSource] exists: if the execution mechanism changes
 * (e.g. the official Android binding turns out unworkable and a custom JDM-subset interpreter
 * replaces it — see the CR-032 planning notes), exactly one file changes.
 *
 * [rulesJson] is the decision graph fetched via [RuleSetRepository]; [context] is the answers
 * object for one evaluation (shape depends on the graph — see the specific `answers` payloads in
 * the rules-service API reference §7 for the scheduling shapes this app sends).
 */
interface RuleEvaluator {
  /**
   * @return the decision graph's output object, or null if evaluation failed (malformed graph,
   * engine error, or a shape the caller doesn't understand). Callers must treat null the same way
   * as "no cached rule" — fall back to [org.armman.sakhi.data.schedule.HardcodedRuleSource] rather
   * than crash or block the flow that needed a schedule.
   */
  suspend fun evaluate(rulesJson: JsonObject, context: JsonObject): JsonObject?
}
