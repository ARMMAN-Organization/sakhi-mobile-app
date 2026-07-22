package org.armman.sakhi.data.lookup

/**
 * Master-data boundary for lookup categories (`BENEFICIARY_TYPE`, `CASE_TYPE`, ...) backing the
 * `/beneficiaries` API's `beneficiaryTypeLookupId`/`caseTypeLookupId` fields. Real category/value
 * rows live server-side and may be empty today if the category hasn't been seeded yet — callers
 * must handle an empty list (e.g. block enrollment submission with a clear message) rather than
 * assume a non-empty result.
 */
interface LookupRepository {

  /**
   * Values for [categoryCode] (e.g. `"BENEFICIARY_TYPE"`), or an empty list if the category has
   * no active values yet (not yet seeded server-side) or the fetch failed with nothing cached
   * from a prior successful fetch. Never throws.
   */
  suspend fun getValues(categoryCode: String): List<LookupValue>

  /** Convenience: the single value whose [LookupValue.valueCode] equals [valueCode] within
   * [categoryCode], or null if not found. */
  suspend fun findValue(categoryCode: String, valueCode: String): LookupValue? =
    getValues(categoryCode).find { it.valueCode == valueCode }
}
