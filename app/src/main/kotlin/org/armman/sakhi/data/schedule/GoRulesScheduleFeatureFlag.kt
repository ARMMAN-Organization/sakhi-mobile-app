package org.armman.sakhi.data.schedule

/**
 * Gates [VisitScheduleCoordinator]'s use of [GoRulesScheduleAdapter] (CR-032).
 *
 * Mirrors the `RemoteBeneficiaryListFeatureFlag`/`ScheduleRuleSource` M2->M3 swap-point pattern
 * already used elsewhere in this codebase: one flag, one place, a trivial revert.
 *
 * ### History
 * - **2026-08-13:** enabled for dev/internal-testing only, ahead of a real low-end-device check.
 * - **2026-08-27:** reverted — that real-device check failed on a release build. R8 had no keep
 *   rules for `io.gorules.**`, renamed the JNI-bound ZenEngine classes, and every release-build
 *   enrolment silently generated zero visits (`UnsatisfiedLinkError`/`NoSuchMethodError`, a
 *   `java.lang.Error` that skipped the `catch (e: Exception)` blocks in `ZenRuleEvaluator` and
 *   `GoRulesScheduleAdapter`, only caught silently one layer up).
 * - **2026-08-31:** `com.sun.jna.**` keep rules added (the native engine's transitive JNA
 *   dependency, missed by the first fix) — confirmed via a real release-build device log for the
 *   RISK-grading call path (`GoRulesRiskAdapter`).
 * - **2026-09-09:** re-enabled and verified end-to-end on a real release-build device
 *   (Bharath) — enrollment, child registration, delivery, and visit-form submission all
 *   exercised on-device with this flag on; forms and submissions confirmed working correctly, no
 *   `GoRulesScheduleAdapter: evaluate(...) threw` warnings in `SakhiSync` Logcat. **Permanently
 *   enabled** — this is no longer a swap-in-progress flag pending verification.
 *
 * Kept as a named constant (rather than deleted outright) purely as a fast, single-place revert
 * switch, matching this codebase's existing feature-flag convention — not because GoRules
 * scheduling is still considered provisional.
 */
internal object GoRulesScheduleFeatureFlag {
  const val ENABLED = true
}
