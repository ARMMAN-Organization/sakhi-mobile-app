package org.armman.sakhi.util

import java.util.Locale

/**
 * Display-only title case: capitalizes the first letter of each whitespace-separated word,
 * lower-cases the rest. Used to normalize beneficiary names for the UI without touching the
 * underlying stored/synced value (which may come from the server or a form as typed, e.g. all
 * lowercase) — never call this before persisting or submitting data.
 */
fun String.toTitleCase(): String =
  split(" ").joinToString(" ") { word ->
    if (word.isEmpty()) {
      word
    } else {
      // Lower-case the remainder before capitalizing, so an all-caps value from the server or a
      // form ("SUNITA SHARMA") renders as "Sunita Sharma" rather than staying shouty — which is
      // what this function's own doc promises. replaceFirstChar alone only touches index 0.
      word.lowercase(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
  }
