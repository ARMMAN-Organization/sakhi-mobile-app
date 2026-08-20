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
 *
 * **Enabled 2026-08-13 — dev/internal-testing only.** Bharath confirmed this build is not being
 * distributed to real Sakhis yet, so items 2 and 3 above being satisfied (field names confirmed
 * by backend, mapping/differential tests passing — see [org.armman.sakhi.data.schedule.GoRulesScheduleAdapterMappingTest])
 * is enough to test internally. Item 1 (a real low-end-device run) is STILL OPEN — no
 * emulator/device was connected at the moment this flag was flipped. Before this build (or any
 * build with this flag on) reaches a real Sakhi: connect a device, register a real test
 * beneficiary end-to-end, and check Logcat (tag `SakhiSync`) for any GoRules evaluation warnings.
 * If that check fails, revert this to `false` immediately.
 */
internal object GoRulesScheduleFeatureFlag {
  const val ENABLED = true
}
