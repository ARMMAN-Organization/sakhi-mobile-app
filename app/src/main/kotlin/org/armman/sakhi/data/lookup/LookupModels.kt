package org.armman.sakhi.data.lookup

/**
 * One selectable value within a lookup category (e.g. `MOTHER`/`CHILD` under the `CASE_TYPE`
 * category), named as the `/lookups/:categoryCode` endpoint returns it. [id] is the UUID the
 * `/beneficiaries` API expects for fields like `beneficiaryTypeLookupId`/`caseTypeLookupId` —
 * never hardcode these ids; the whole point of the lookup table is that ARMMAN's content team
 * can rename/reorder [valueLabel]s without breaking any client that resolves by [valueCode].
 */
data class LookupValue(
  val id: String,
  val valueCode: String,
  val valueLabel: String,
)
