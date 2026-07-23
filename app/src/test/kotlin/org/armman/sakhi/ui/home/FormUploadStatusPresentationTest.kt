package org.armman.sakhi.ui.home

import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.ui.theme.NeutralG100
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.RiskHighSurface
import org.armman.sakhi.ui.theme.StatusSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit coverage for the "Forms Uploaded" modal's category-aggregation rules, extracted
 * into [FormUploadStatusPresentation.kt] specifically so they're testable without a Compose UI
 * test harness (this repo has none — no `createComposeRule` usage exists anywhere).
 */
class FormUploadStatusPresentationTest {

  private fun record(
    id: String,
    status: EnrollmentSyncStatus,
    formCode: String = "MOTHER_REGISTRATION",
  ) = FormUploadRecord(localBeneficiaryId = id, formCode = formCode, syncStatus = status, createdAtEpochMillis = 0L)

  // --- groupUploadRecordsByCategory ------------------------------------------------------------

  @Test
  fun `empty record list groups into no categories`() {
    assertTrue(groupUploadRecordsByCategory(emptyList()).isEmpty())
  }

  @Test
  fun `records with the same form code group into a single category summary`() {
    val records = listOf(
      record("a", EnrollmentSyncStatus.SYNCED),
      record("b", EnrollmentSyncStatus.SYNCED),
      record("c", EnrollmentSyncStatus.PENDING),
    )

    val categories = groupUploadRecordsByCategory(records)

    assertEquals(1, categories.size)
    val summary = categories.single()
    assertEquals("MOTHER_REGISTRATION", summary.formCode)
    assertEquals(2, summary.syncedCount)
    assertEquals(3, summary.totalCount)
  }

  @Test
  fun `records with different form codes group into separate category summaries`() {
    val records = listOf(
      record("a", EnrollmentSyncStatus.SYNCED, formCode = "MOTHER_REGISTRATION"),
      record("b", EnrollmentSyncStatus.PENDING, formCode = "REFERRAL_FORM"),
    )

    val categories = groupUploadRecordsByCategory(records)

    assertEquals(2, categories.size)
    assertEquals(setOf("MOTHER_REGISTRATION", "REFERRAL_FORM"), categories.map { it.formCode }.toSet())
  }

  // --- categoryIconKind: the approved priority rule --------------------------------------------

  @Test
  fun `all synced yields SYNCED`() {
    val kind = categoryIconKind(listOf(EnrollmentSyncStatus.SYNCED, EnrollmentSyncStatus.SYNCED))
    assertEquals(UploadStatusIconKind.SYNCED, kind)
  }

  @Test
  fun `none attempted yet yields PENDING`() {
    val kind = categoryIconKind(listOf(EnrollmentSyncStatus.PENDING, EnrollmentSyncStatus.PENDING))
    assertEquals(UploadStatusIconKind.PENDING, kind)
  }

  @Test
  fun `partial progress with no failures yields SYNCING, matching the reference's 50 percent example`() {
    val kind = categoryIconKind(listOf(EnrollmentSyncStatus.SYNCED, EnrollmentSyncStatus.PENDING))
    assertEquals(UploadStatusIconKind.SYNCING, kind)
  }

  @Test
  fun `an in-flight record with no successes yet still yields SYNCING`() {
    val kind = categoryIconKind(listOf(EnrollmentSyncStatus.SYNCING, EnrollmentSyncStatus.PENDING))
    assertEquals(UploadStatusIconKind.SYNCING, kind)
  }

  @Test
  fun `a single failure wins over mostly-successful records (approved rule)`() {
    val kind = categoryIconKind(
      listOf(
        EnrollmentSyncStatus.SYNCED,
        EnrollmentSyncStatus.SYNCED,
        EnrollmentSyncStatus.SYNCED,
        EnrollmentSyncStatus.FAILED,
      ),
    )
    assertEquals(UploadStatusIconKind.NEEDS_ATTENTION, kind)
  }

  @Test
  fun `a duplicate conflict also wins over an otherwise-complete category`() {
    val kind = categoryIconKind(listOf(EnrollmentSyncStatus.SYNCED, EnrollmentSyncStatus.DUPLICATE_CONFLICT))
    assertEquals(UploadStatusIconKind.NEEDS_ATTENTION, kind)
  }

  @Test
  fun `failure wins even when everything else is untouched`() {
    val kind = categoryIconKind(listOf(EnrollmentSyncStatus.PENDING, EnrollmentSyncStatus.FAILED))
    assertEquals(UploadStatusIconKind.NEEDS_ATTENTION, kind)
  }

  // --- categoryPercent / categoryProgressFraction: always real, never fabricated ---------------

  @Test
  fun `percent and fraction reflect the true synced-over-total ratio`() {
    val summary = FormCategorySummary(
      formCode = "MOTHER_REGISTRATION",
      syncedCount = 8,
      totalCount = 9,
      iconKind = UploadStatusIconKind.SYNCING,
    )
    assertEquals(89, categoryPercent(summary))
    assertEquals(8f / 9f, categoryProgressFraction(summary), 0.0001f)
  }

  @Test
  fun `fully synced category is 100 percent`() {
    val summary = FormCategorySummary("MOTHER_REGISTRATION", syncedCount = 4, totalCount = 4, UploadStatusIconKind.SYNCED)
    assertEquals(100, categoryPercent(summary))
    assertEquals(1f, categoryProgressFraction(summary), 0.0001f)
  }

  @Test
  fun `untouched category is 0 percent`() {
    val summary = FormCategorySummary("MOTHER_REGISTRATION", syncedCount = 0, totalCount = 3, UploadStatusIconKind.PENDING)
    assertEquals(0, categoryPercent(summary))
    assertEquals(0f, categoryProgressFraction(summary), 0.0001f)
  }

  @Test
  fun `zero total does not divide by zero`() {
    val summary = FormCategorySummary("MOTHER_REGISTRATION", syncedCount = 0, totalCount = 0, UploadStatusIconKind.PENDING)
    assertEquals(0, categoryPercent(summary))
    assertEquals(0f, categoryProgressFraction(summary), 0.0001f)
  }

  // --- color mappings ---------------------------------------------------------------------------

  @Test
  fun `icon tint follows the aggregate status`() {
    assertEquals(StatusSuccess, categoryIconTint(UploadStatusIconKind.SYNCED))
    assertEquals(RiskHigh, categoryIconTint(UploadStatusIconKind.NEEDS_ATTENTION))
    assertEquals(Primary, categoryIconTint(UploadStatusIconKind.SYNCING))
    assertEquals(NeutralG100, categoryIconTint(UploadStatusIconKind.PENDING))
  }

  @Test
  fun `bar and track colors turn red only for NEEDS_ATTENTION`() {
    assertEquals(RiskHigh, categoryBarColor(UploadStatusIconKind.NEEDS_ATTENTION))
    assertEquals(RiskHighSurface, categoryTrackColor(UploadStatusIconKind.NEEDS_ATTENTION))
    assertNotEquals(RiskHigh, categoryBarColor(UploadStatusIconKind.SYNCED))
    assertNotEquals(RiskHigh, categoryBarColor(UploadStatusIconKind.SYNCING))
    assertNotEquals(RiskHigh, categoryBarColor(UploadStatusIconKind.PENDING))
  }

  @Test
  fun `pending track color is distinct from the in-progress and synced track color`() {
    assertEquals(NeutralG50, categoryTrackColor(UploadStatusIconKind.PENDING))
    assertEquals(PrimarySurface, categoryTrackColor(UploadStatusIconKind.SYNCED))
    assertEquals(PrimarySurface, categoryTrackColor(UploadStatusIconKind.SYNCING))
  }

  @Test
  fun `text color is neutral for pending and syncing, not just the icon tint`() {
    assertEquals(NeutralG200, categoryTextColor(UploadStatusIconKind.PENDING))
    assertEquals(NeutralG200, categoryTextColor(UploadStatusIconKind.SYNCING))
  }

  // --- uploadStatusIconKind: single-record mapping feeding the aggregation ---------------------

  @Test
  fun `FAILED and DUPLICATE_CONFLICT share the same single-record icon kind`() {
    assertEquals(
      uploadStatusIconKind(EnrollmentSyncStatus.FAILED),
      uploadStatusIconKind(EnrollmentSyncStatus.DUPLICATE_CONFLICT),
    )
  }
}
