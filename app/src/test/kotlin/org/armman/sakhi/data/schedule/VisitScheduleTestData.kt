package org.armman.sakhi.data.schedule

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Shared builder so schedule tests state only the field under test. */
internal fun schedule(
  localScheduleUuid: String,
  localBeneficiaryId: String = "ben-1",
  visitCode: String = "ANC1",
  visitType: VisitCodeType = VisitCodeType.ANC,
  sequenceNo: Int = 1,
  scheduledDate: LocalDate = LocalDate.of(2026, 8, 4),
  windowStartDate: LocalDate = scheduledDate,
  windowEndDate: LocalDate = scheduledDate.plusDays(5),
  anchorType: AnchorType = AnchorType.REGISTRATION,
  anchorDate: LocalDate = scheduledDate,
  anchorVisitLocalUuid: String? = null,
  status: VisitScheduleStatus = VisitScheduleStatus.GENERATED,
  reasonCode: String? = null,
  serverScheduleId: String? = null,
  serverBeneficiaryId: String? = null,
  generatedByRuleVersion: String = "test-v1",
  escalationPolicy: EscalationPolicy = EscalationPolicy.AFTER_TWO_CONSECUTIVE,
  createdAtEpochMillis: Long = 1_754_265_600_000L,
) = VisitScheduleEntity(
  localScheduleUuid = localScheduleUuid,
  serverScheduleId = serverScheduleId,
  localBeneficiaryId = localBeneficiaryId,
  serverBeneficiaryId = serverBeneficiaryId,
  visitCode = visitCode,
  visitType = visitType,
  sequenceNo = sequenceNo,
  scheduledDate = scheduledDate,
  windowStartDate = windowStartDate,
  windowEndDate = windowEndDate,
  anchorType = anchorType,
  anchorDate = anchorDate,
  anchorVisitLocalUuid = anchorVisitLocalUuid,
  status = status,
  reasonCode = reasonCode,
  generatedByRuleVersion = generatedByRuleVersion,
  escalationPolicy = escalationPolicy,
  createdAtEpochMillis = createdAtEpochMillis,
)

/**
 * A [ScheduleRuleSource] whose every value is supplied by the test.
 *
 * Its real purpose is not convenience — it is the proof that the CR-032 seam is genuine. If the
 * generator can be driven to produce a 10-day ANC cadence from a fake, then it reads its rules
 * rather than knowing them, and swapping in GoRules in M3 is a binding change. If these tests
 * cannot be written, the seam is decorative.
 */
internal class FakeRuleSource(
  private val ruleVersion: String = "test-v9",
  private val interval: Int = 10,
  private val count: Int = 3,
  private val windowDays: Int = 2,
  private val hrOffset: Int = 7,
  private val cutoff: Int? = null,
  private val escalation: EscalationPolicy = EscalationPolicy.IMMEDIATE,
  private val supportsHr: Boolean = true,
  private val fixedRangeDays: Pair<Int, Int> = 0 to 9,
  private val offsetTable: List<Int> = listOf(0, 20, 40),
  private val earlyIncMaxDay: Long = 30L,
  private val incFirstVisitOffset: Int = 20,
  private val incPhaseEnd: Int = 100,
  private val postEddGrace: Int = 3,
  private val postEddOffset: Int = 4,
  private val eddOffset: Int = 200,
  private val perDetection: Boolean = true,
) : ScheduleRuleSource {
  override fun ruleVersion(visitType: VisitCodeType) = ruleVersion

  override fun intervalDays(visitType: VisitCodeType) = interval

  override fun visitCount(visitType: VisitCodeType, context: ScheduleContext) = count

  override fun window(visitType: VisitCodeType, sequenceNo: Int, scheduledDate: LocalDate) =
    VisitWindow(
      scheduledDate.minusDays(windowDays.toLong()),
      scheduledDate.plusDays(windowDays.toLong()),
    )

  /** Only NN is fixed-range in the fake, mirroring the real source's shape without its numbers. */
  override fun usesFixedRangeWindow(visitType: VisitCodeType, sequenceNo: Int) =
    visitType == VisitCodeType.NN

  override fun fixedRangeWindow(
    visitType: VisitCodeType,
    sequenceNo: Int,
    anchorDate: LocalDate,
    notBefore: LocalDate?,
  ): VisitWindow? {
    val end = anchorDate.plusDays(fixedRangeDays.second.toLong())
    val nominalStart = anchorDate.plusDays(fixedRangeDays.first.toLong())
    val start = if (notBefore != null && notBefore.isAfter(nominalStart)) notBefore else nominalStart
    return if (start.isAfter(end)) null else VisitWindow(start, end)
  }

  override fun scheduledOffsetDays(visitType: VisitCodeType, sequenceNo: Int) =
    offsetTable[sequenceNo - 1]

  override fun isEarlyIncRegistration(dob: LocalDate, registrationDate: LocalDate) =
    ChronoUnit.DAYS.between(dob, registrationDate) in 0..earlyIncMaxDay

  override fun incFirstVisitOffsetDays() = incFirstVisitOffset

  override fun incPhaseEndDays() = incPhaseEnd

  override fun postEddGraceDays() = postEddGrace

  override fun postEddOffsetDays() = postEddOffset

  override fun eddOffsetDays() = eddOffset

  override fun hrPerDetection() = perDetection

  override fun hrOffsetDays(visitType: VisitCodeType) = hrOffset
  override fun cutoffDays(visitType: VisitCodeType) = cutoff
  override fun escalationPolicy(visitType: VisitCodeType) = escalation
  override fun supportsHrVisits(visitType: VisitCodeType) = supportsHr
}
