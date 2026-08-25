package org.armman.sakhi.data.rules

import com.google.gson.JsonObject

/**
 * One rule set's currently-published content, cached for offline use.
 *
 * [versionNo] is opaque to callers here — it exists so a future stamping/telemetry need doesn't
 * require touching this contract again, not because [org.armman.sakhi.data.schedule.GoRulesRuleSource]
 * currently reads it. The generator's own version stamp continues to come from
 * [org.armman.sakhi.data.schedule.ScheduleRuleSource.ruleVersion] (per visit type).
 */
data class CachedRuleSet(
  val ruleSetId: String,
  /** `rule_versions.rule_version_id` — the value stamped as `generatedByRuleVersion`/
   * `generatedByRuleVersionId`. Distinct from [versionNo], which is a display string ("v1") not
   * accepted by the sync contract (CR-023 requires the real UUID). */
  val ruleVersionId: String,
  val versionNo: String,
  val rulesJson: JsonObject,
)

/**
 * Master-data boundary for GoRules rule content — the rule-package counterpart to
 * [org.armman.sakhi.data.forms.FormsRepository]. Real rule content lives server-side; if a fetch
 * fails with nothing cached from a prior successful fetch, callers get null and must fall back to
 * whatever they'd otherwise use (for scheduling, that is
 * [org.armman.sakhi.data.schedule.HardcodedRuleSource] — an enrolment can't be blocked on a rule
 * download; for risk grading, that means real-time field highlighting is simply unavailable until
 * a rule pack has been fetched at least once).
 *
 * One method now covers both SCHEDULE and RISK rule sets — as of 2026-08-24 both categories fetch
 * by rule *set* id and resolve "whatever's currently published" the same way, via
 * [RuleSetApi.getPublishedVersionId] + [RuleSetApi.getRuleVersionContent]. There is no longer a
 * fetch-by-fixed-version-id path (see [RuleSetIds.RISK_ANC]'s doc for why that used to exist and
 * why it doesn't anymore).
 */
interface RuleSetRepository {

  /**
   * The published [CachedRuleSet] for [ruleSetId] (see [RuleSetIds]), preferring a live
   * resolve-then-fetch and falling back to the last successfully cached content if
   * offline/failed/nothing published. Null only if no version of this rule set has ever been
   * fetched successfully on this device.
   *
   * A live call only re-downloads content when the resolved published version differs from what's
   * already cached — an unchanged published version is a cache hit, no second network call.
   */
  suspend fun getPublishedRuleSet(ruleSetId: String): CachedRuleSet?

  /**
   * Best-effort batch warm of the cache for every id in [ruleSetIds] in one round trip — call this
   * once per data sync rather than relying on [getPublishedRuleSet]'s per-id lazy fetch for a full
   * sync of all rule categories. An id with no published version, or the whole call failing
   * (offline, error), leaves that id's existing cache untouched rather than clearing it — this
   * never makes [getPublishedRuleSet] return something worse than before the call.
   */
  suspend fun prefetchRuleSets(ruleSetIds: List<String>)
}
