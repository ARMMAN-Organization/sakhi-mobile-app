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
 * download).
 */
interface RuleSetRepository {
  /**
   * The published [CachedRuleSet] for [ruleSetId] (see [RuleSetIds]), preferring a live fetch and
   * falling back to the last successfully cached content if offline/failed. Null only if no
   * version of this rule set has ever been fetched successfully on this device.
   */
  suspend fun getPublishedRuleSet(ruleSetId: String): CachedRuleSet?
}
