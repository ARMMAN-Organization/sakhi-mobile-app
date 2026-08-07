package org.armman.sakhi.data.lookup

/**
 * Resolves a human-readable label coming from an external source (CR-032's `socioDemographics`
 * block on `GET /beneficiaries/:id`, which reports `{categoryCode, valueCode, label}` in
 * `beneficiary-service`'s own upper-snake-case convention) to the `value_code` the CHILD_REGISTRATION
 * form schema actually expects for that question — a separate, lower-snake-case, label-derived slug.
 *
 * The two sides are never a byte-for-byte match on the value code itself (`SELF` vs `self`,
 * `TENTH_PASS` vs `10th_pass`, `UPTO_10000` vs `10000` — confirmed field-by-field against the form
 * schema and the lookup seed data for all 8 socio-demographic fields). `beneficiary-service`'s own
 * `resolveLookupIdsByValueCode` (`lookup.client.ts:100-115`) solves the identical problem the other
 * direction by matching on the option's *label*, case/non-alphanumeric-insensitive, with a
 * longest-label-prefix fallback for options whose label is a truncated form of the other side's
 * (e.g. education: `"No formal education"` vs the schema's fuller
 * `"No formal education (Never attended school / cannot read or write)"`). This mirrors that
 * approach rather than inventing a second, divergent one.
 *
 * Deliberately generic over `(value code, label)` pairs rather than a form- or lookup-specific type:
 * the only two things a caller has are "the label I got" and "the value codes I could write."
 */
object LookupLabelMatcher {

  /**
   * The `valueCode` of the `(valueCode, label)` pair in [options] whose label matches
   * [externalLabel], or null if none matches confidently enough to write. Never guesses — a caller
   * should skip the field rather than write a wrong option code.
   */
  fun match(externalLabel: String?, options: List<Pair<String, String>>): String? {
    val target = externalLabel?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return null

    // 1) Exact match on the normalized label — covers the common case (Self, Labour, Hindu, ...).
    options.firstOrNull { (_, label) -> normalize(label) == target }?.let { (valueCode, _) -> return valueCode }

    // 2) Longest-prefix fallback, checked both directions — covers a truncated external label
    //    (education) and, symmetrically, a truncated schema label, whichever side is shorter.
    //    Ties broken by longest matching prefix so the closest label wins.
    return options
      .mapNotNull { (valueCode, label) ->
        val normalized = normalize(label)
        if (normalized.isEmpty()) return@mapNotNull null
        val prefixLength = when {
          target.startsWith(normalized) -> normalized.length
          normalized.startsWith(target) -> target.length
          else -> return@mapNotNull null
        }
        Triple(valueCode, label, prefixLength)
      }
      .maxByOrNull { (_, _, prefixLength) -> prefixLength }
      ?.first
  }

  /** Lowercase, letters/digits only — drops spaces, punctuation, and symbols like `≤`/`>` so
   * `"≤10000"` and `"<=10000"` normalize to the same `"10000"`. */
  private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
