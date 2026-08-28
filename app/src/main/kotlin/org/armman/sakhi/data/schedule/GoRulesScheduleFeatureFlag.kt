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
  // REVERTED 2026-08-27: the "real low-end/release-build check" this flag's own doc called out as
  // STILL OPEN just failed. Release builds have zero ProGuard/R8 keep rules for io.gorules.** — R8
  // renames the JNI-bound ZenEngine classes, breaking native linkage, throwing an
  // UnsatisfiedLinkError/NoSuchMethodError (java.lang.Error, not Exception — so it skips both
  // ZenRuleEvaluator's and GoRulesScheduleAdapter's `catch (e: Exception)` blocks) that only gets
  // caught (silently, pre-2026-08-27) by MotherEnrolmentScheduleTrigger's outer runCatching. Net
  // effect: every release-build enrolment silently generated zero visits, debug builds unaffected
  // (isMinifyEnabled = false there). Per this flag's own rollback rule ("if that check fails,
  // revert to false immediately") — do not re-enable until proguard-rules.pro has real io.gorules
  // keep rules AND this has been re-verified on an actual release build on a real device.
  const val ENABLED = false
}
