package org.armman.sakhi.ui.forms

/**
 * Scroll-position policy for the tabbed dynamic forms (mother enrollment, child registration).
 *
 * Both screens render every section through a single field-list call site — only the `fields`
 * argument changes when the tab switches — so Compose keeps the same `LazyListState`, and the
 * previous section's scroll offset carries over. Without a reset the Sakhi lands mid-form instead
 * of on the section's first question.
 *
 * Kept as a pure function (rather than inlined in the composable) so the guard is unit-testable:
 * the module has no Compose UI test infrastructure, and the one behaviour that can silently
 * regress is the post-submit error jump being clobbered by this reset.
 */
internal object FormSectionScroll {

  /**
   * Whether entering a section should jump to its first item.
   *
   * @param hasPendingErrorScroll true when a field-attributable submit failure is waiting to
   *   scroll this section to the flagged field. That jump is triggered by the same tab switch, so
   *   resetting to the top here would cancel it and hide the error the Sakhi needs to fix.
   */
  fun shouldResetToTop(hasPendingErrorScroll: Boolean): Boolean = !hasPendingErrorScroll
}
