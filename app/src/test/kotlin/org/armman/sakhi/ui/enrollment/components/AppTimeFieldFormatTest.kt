package org.armman.sakhi.ui.enrollment.components

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatTimeOfDay] backs [AppTimeField]'s stored/displayed value. Backend does not validate the
 * `TIME` input_type's answer format server-side, but wants consistency with the platform's
 * "hh:mm am/pm" convention (lowercase am/pm, 12-hour clock) — these cases pin the classic 12-hour
 * edge cases (midnight/noon) and confirm the am/pm marker is actually lowercase, not just assumed
 * to be from the pattern letter used.
 */
class AppTimeFieldFormatTest {

  @Test
  fun `formats a morning time with leading zero minute correctly`() {
    assertEquals("09:05 am", formatTimeOfDay(LocalTime.of(9, 5)))
  }

  @Test
  fun `formats an afternoon-pm time correctly, lowercase`() {
    assertEquals("02:45 pm", formatTimeOfDay(LocalTime.of(14, 45)))
  }

  @Test
  fun `formats midnight and noon correctly`() {
    assertEquals("12:00 am", formatTimeOfDay(LocalTime.of(0, 0)))
    assertEquals("12:00 pm", formatTimeOfDay(LocalTime.of(12, 0)))
  }
}
