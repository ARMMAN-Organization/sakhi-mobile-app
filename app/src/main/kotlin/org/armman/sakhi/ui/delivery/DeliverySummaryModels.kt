package org.armman.sakhi.ui.delivery

/**
 * Shared Summary-tab review types for CR-042's Delivery Event Session screens
 * ([DeliveryChildRegistrationViewModel], [DeliverySessionViewModel]).
 *
 * Both ViewModels live in this same `ui.delivery` package — unlike the standalone mother/child
 * registration flows (`ui.motherregistration` / `ui.childregistration`), which live in separate
 * packages and can each safely declare their own identically-named `SummaryRow`/`SummarySection`
 * (Kotlin's file-private visibility for top-level declarations is file-scoped, so two files in the
 * same package can each have their own `private` composables/functions without conflict — but a
 * non-private top-level `data class`/`const val` IS package-scoped and would collide if each
 * delivery ViewModel tried to declare its own copy). One shared declaration here avoids that
 * redeclaration conflict while keeping both screens' Summary tabs structurally identical to the
 * child/mother registration flows' own review UI.
 */

/** One label/value line in a Summary tab's review. */
data class SummaryRow(val label: String, val value: String)

/** One section card in a Summary tab — [title] is the schema section, [rows] its answered fields
 * (empty sections are dropped by each ViewModel's own `buildSummary`). */
data class SummarySection(val title: String, val rows: List<SummaryRow>)

/** Tab/section label for any visible field whose schema `section` is missing — a catch-all so a
 * field never silently disappears. Shared so both delivery screens degrade identically. */
const val FALLBACK_SECTION = "Additional Information"
