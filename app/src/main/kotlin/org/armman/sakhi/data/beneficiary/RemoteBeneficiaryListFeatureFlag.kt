package org.armman.sakhi.data.beneficiary

/**
 * Gates [OfflineFirstBeneficiaryRepository]'s remote fetch.
 *
 * `GET /beneficiaries` applies no `sakhiId` scoping today (backend risk R2 — confirmed against the
 * live service, see [org.armman.sakhi.data.motherlink.BeneficiaryApi]'s class doc): any
 * authenticated SAKHI/SUPERVISOR/MANAGER gets the same unscoped result set, so enabling this shows
 * every Sakhi every beneficiary case in the system, not just her own — a PII leak, not a cosmetic
 * bug.
 *
 * TEMPORARILY ENABLED 2026-08-07 (bharath, product decision) with that risk explicitly accepted
 * for now, ahead of the backend scoping fix — set back to `false` if that decision changes. This
 * stays a stopgap, not a permanent state: beneficiary-service's `list()` still doesn't scope by
 * the caller's `sakhiId` (`apps/beneficiary-service/src/beneficiary/beneficiary.service.ts` — it
 * currently only supports an optional `projectId` filter, nothing derived from `req.user`); once
 * that ships, this flag/comment can just be deleted since the remote fetch is then always safe.
 * Mirrors the `ScheduleRuleSource`/`HardcodedRuleSource` M2→M3 swap-point pattern already used
 * elsewhere in this codebase: one flag, one place, a trivial revert.
 */
internal object RemoteBeneficiaryListFeatureFlag {
  const val ENABLED = true
}
