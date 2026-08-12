package org.armman.sakhi.data.schedule

/**
 * Gates [VisitScheduleCoordinator]'s use of [GoRulesScheduleAdapter] (CR-032).
 *
 * Mirrors the `RemoteBeneficiaryListFeatureFlag`/`ScheduleRuleSource` M2→M3 swap-point pattern
 * already used elsewhere in this codebase: one flag, one place, a trivial revert.
 *
 * **Keep `false` until:**
 * 1. The Step 1 ngrok trial has confirmed the on-device GoRules engine ([io.gorules.zen_engine]
 *    binding, see `data/rules/ZenRuleEvaluator.kt`) actually runs on a real low-end device.
 * 2. Each pack's real request-field names have been confirmed against the running rules-service
 *    (see [GoRulesScheduleAdapter]'s per-method doc for which fields are still best-effort).
 * 3. The differential test suite (GoRules vs [HardcodedRuleSource] over a seeded corpus) passes.
 *
 * Until all three hold, [VisitScheduleCoordinator] always falls back to the existing Kotlin
 * generators — flipping this on prematurely risks generating wrong schedules for real
 * beneficiaries, not just a failed test.
 */
internal object GoRulesScheduleFeatureFlag {
  const val ENABLED = false
}
