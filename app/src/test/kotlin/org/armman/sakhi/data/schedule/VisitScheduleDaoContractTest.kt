package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the small number of places where [VisitScheduleDao]'s SQL and the Kotlin model have to agree
 * by hand. Room validates column names and types at compile time, but not these.
 */
class VisitScheduleDaoContractTest {

  /**
   * `lapseOpenAncVisits` writes the reason as a SQL string literal, so a rename of the constant
   * would not break the build — it would just silently stop matching. This test fails instead.
   */
  @Test
  fun `lapse reason constant matches the literal written by the DAO query`() {
    assertEquals("LAPSED_ON_DELIVERY", REASON_LAPSED_ON_DELIVERY)
  }

  /**
   * Statuses are stored as TEXT by enum name, and several queries filter on those names inline
   * (`'GENERATED'`, `'OPEN'`, `'SUPERSEDED'`, `'CANCELLED'`). Renaming a member would break the
   * filters silently.
   */
  @Test
  fun `status names used inline in DAO queries still exist`() {
    val names = VisitScheduleStatus.entries.map { it.name }
    listOf("GENERATED", "OPEN", "SUPERSEDED", "CANCELLED", "COMPLETED", "MISSED")
      .forEach { assertTrue("$it is referenced in DAO SQL", it in names) }
  }

  /** Same for the ANC family names filtered inline by `lapseOpenAncVisits`. */
  @Test
  fun `ANC family names used inline in DAO queries still exist`() {
    val names = VisitCodeType.entries.map { it.name }
    listOf("ANC", "ANC_HR", "ANC_POST_EDD")
      .forEach { assertTrue("$it is referenced in DAO SQL", it in names) }
  }

  /** Mirrors the server's `VisitCodeType` — a divergence breaks the CR-023 bulk upload. */
  @Test
  fun `visit code type mirrors the server enum`() {
    assertEquals(
      listOf(
        "ANC", "ANC_HR", "ANC_POST_EDD", "DELIVERY", "PP",
        "NN", "INC", "INC_HR", "CCV", "CCV_HR",
      ),
      VisitCodeType.entries.map { it.name },
    )
  }

  /** Mirrors the server's `AnchorType`. */
  @Test
  fun `anchor type mirrors the server enum`() {
    assertEquals(
      listOf("REGISTRATION", "LMP", "EDD", "DELIVERY_DATE", "DOB", "ACTUAL_VISIT", "CCV_TRANSITION"),
      AnchorType.entries.map { it.name },
    )
  }

  /**
   * The server has no LAPSED member — see the note on [VisitScheduleStatus] and open question Q3.
   * If ARMMAN rules that lapsed must be distinct, this test is the reminder that both enums and a
   * server migration are involved.
   */
  @Test
  fun `status mirrors the server enum and has no LAPSED member yet`() {
    assertEquals(
      listOf("GENERATED", "OPEN", "MISSED", "COMPLETED", "SUPERSEDED", "CANCELLED"),
      VisitScheduleStatus.entries.map { it.name },
    )
  }
}
