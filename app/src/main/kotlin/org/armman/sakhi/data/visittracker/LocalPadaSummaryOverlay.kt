package org.armman.sakhi.data.visittracker

import org.armman.sakhi.data.beneficiary.Beneficiary
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.referral.ReferralLinkDao
import org.armman.sakhi.data.schedule.VisitScheduleEntity
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Placeholder id prefix for a pada card built entirely from local data — never a real
 * `padaId` `GET /padas/{padaId}/visits` would recognise. Safe anyway:
 * [org.armman.sakhi.ui.visittracker.PadaVisitsViewModel]'s own local overlay matches by
 * [Beneficiary.pada] name, not this id, so tapping into a card built with this id still resolves
 * the beneficiary's real open visits/referrals once there. */
private const val LOCAL_PADA_ID_PREFIX = "local-pada:"

/**
 * Builds the Visit Tracker landing screen's pada list purely from this device's own data, for use
 * when `GET /sakhi/{sakhiId}/padas` has failed AND nothing was ever cached for it
 * ([RemotePadaRepository] throws in exactly that case) — bharath, 2026-09-10: "Visit Tracker
 * screen is blank while offline" (a fresh device/install, or simply a Sakhi who has never opened
 * this screen once online, has no [RemotePadaRepository] cache to fall back to at all).
 *
 * Unlike [org.armman.sakhi.data.dashboard.LocalVisitCounts]/[org.armman.sakhi.ui.visittracker
 * .PadaVisitsViewModel]'s own ADDITIVE local overlay (which counts only never-uploaded schedules,
 * to guarantee no double count against a real server response), this counts EVERY currently open
 * schedule regardless of sync state — synced or not, mother/child or referral. That is safe and
 * correct here specifically because this only ever runs as a full replacement, when there is no
 * server response of any kind to conflict with (see this class's own trigger condition below) —
 * a schedule that already synced is exactly as invisible to this device's stale/absent
 * [RemotePadaRepository] cache as one that never did.
 *
 * Deliberately NOT used to top up a successful/cached fetch the way the dashboard and per-pada
 * overlays are — there is no reliable way to match a locally-known pada name to the server's own
 * [PadaSummary.padaId] here (unlike the per-pada screen, which already has the real padaId's
 * *name* from nav args to match against), so merging would risk showing the same pada twice under
 * two different cards. Full-replacement fallback only, same as
 * [org.armman.sakhi.data.dashboard.RemoteDashboardRepository]'s own cache-miss case.
 */
/**
 * An interface (rather than a bare `@Inject constructor` class) purely so
 * [org.armman.sakhi.ui.visittracker.PadaSelectionViewModel] can be unit-tested against a trivial
 * fake instead of constructing a real [LocalEnrolmentBeneficiarySource] — same
 * interface-plus-`Room`-prefixed-impl convention [org.armman.sakhi.data.dashboard
 * .LocalReferralFollowUpOverlay] and every other repository in `di/` already follows.
 */
interface LocalPadaSummaryOverlay {
  suspend fun buildPadaSummaries(today: LocalDate = LocalDate.now()): List<PadaSummary>

  suspend fun mergeWithLocal(
    serverSummaries: List<PadaSummary>,
    today: LocalDate = LocalDate.now(),
  ): List<PadaSummary>
}

@Singleton
class RoomLocalPadaSummaryOverlay @Inject constructor(
  private val localEnrolmentBeneficiarySource: LocalEnrolmentBeneficiarySource,
  private val visitScheduleRepository: VisitScheduleRepository,
  private val referralLinkDao: ReferralLinkDao,
) : LocalPadaSummaryOverlay {

  override suspend fun buildPadaSummaries(today: LocalDate): List<PadaSummary> {
    val beneficiariesByPada = localEnrolmentBeneficiarySource.getLocalBeneficiaries(today)
      .filter { it.pada.isNotBlank() && it.pada != UNKNOWN_PADA_DASH }
      .groupBy { it.pada }
    if (beneficiariesByPada.isEmpty()) return emptyList()

    val beneficiaryTypeById: Map<String, BeneficiaryType> =
      beneficiariesByPada.values.flatten().associate { it.id to it.type }

    val openSchedulesByBeneficiary: Map<String, List<VisitScheduleEntity>> =
      visitScheduleRepository.getAllActive()
        .filter { it.localBeneficiaryId in beneficiaryTypeById }
        .groupBy { it.localBeneficiaryId }

    val pendingReferralBeneficiaryIds: Set<String> =
      referralLinkDao.getPendingFollowUp().map { it.beneficiaryId }.toSet()

    return beneficiariesByPada.map { (padaName, beneficiaries) ->
      val ids = beneficiaries.map { it.id }.toSet()
      val openSchedules = ids.flatMap { openSchedulesByBeneficiary[it].orEmpty() }

      val openBucket = openSchedules.toPadaVisitBucket(beneficiaryTypeById, today)
      val referralIds = ids.filter { it in pendingReferralBeneficiaryIds }
      val referralBucket = PadaVisitBucket(
        womenCount = referralIds.count { beneficiaryTypeById[it] == BeneficiaryType.MOTHER },
        womenOverdueCount = 0,
        childCount = referralIds.count { beneficiaryTypeById[it] == BeneficiaryType.INFANT },
        childOverdueCount = 0,
      )

      PadaSummary(
        padaId = "$LOCAL_PADA_ID_PREFIX$padaName",
        padaName = padaName,
        villageName = UNKNOWN_PADA_DASH,
        open = openBucket,
        referralFollowUp = referralBucket,
        visitsRemainingCount = openBucket.womenCount + openBucket.childCount,
      )
    }
  }

  private fun List<VisitScheduleEntity>.toPadaVisitBucket(
    beneficiaryTypeById: Map<String, BeneficiaryType>,
    today: LocalDate,
  ): PadaVisitBucket {
    var womenCount = 0
    var womenOverdue = 0
    var childCount = 0
    var childOverdue = 0
    for (schedule in this) {
      val isOverdue = today.isAfter(schedule.windowEndDate)
      // Not-yet-open (window in the future) schedules are skipped entirely, matching
      // LocalVisitCounts' own "actionable now" scope.
      if (!isOverdue && today.isBefore(schedule.windowStartDate)) continue
      when (beneficiaryTypeById[schedule.localBeneficiaryId]) {
        BeneficiaryType.MOTHER -> {
          womenCount++
          if (isOverdue) womenOverdue++
        }
        BeneficiaryType.INFANT -> {
          childCount++
          if (isOverdue) childOverdue++
        }
        null -> Unit
      }
    }
    return PadaVisitBucket(womenCount, womenOverdue, childCount, childOverdue)
  }

  /**
   * Bharath, 2026-09-10 ("dashboard/per-pada screen shows the real count, but the pada CARD on
   * the Visit Tracker landing page still shows 0") — [PadaSelectionViewModel] previously only
   * ever consulted this class when `padaRepository.getPadaSummaries()` threw outright. A stale
   * (or simply zero, for a pada [RemotePadaRepository] genuinely has cached a `0` for) but
   * *successful* response never triggers that fallback at all, so a schedule the server hasn't
   * caught up on yet — synced or not — stayed invisible on this specific screen even after every
   * other fix. Elementwise MAX per bucket field, matched by [PadaSummary.padaName]
   * (case-insensitive; there is no reliable local padaId to match by — see this class's own
   * top-level doc), rather than adding the two together: this device's own count is a real
   * ground-truth read of Room, so it can never be an over-count for this Sakhi's own caseload, and
   * max avoids ever double-counting a visit the (possibly stale) server number already reflects.
   * A local pada absent from the server list entirely is appended as its own card, same as the
   * full-replacement path.
   */
  override suspend fun mergeWithLocal(
    serverSummaries: List<PadaSummary>,
    today: LocalDate,
  ): List<PadaSummary> {
    val localSummaries = buildPadaSummaries(today)
    if (localSummaries.isEmpty()) return serverSummaries

    val localByName = localSummaries.associateBy { it.padaName.trim().lowercase() }
    val mergedServerNames = mutableSetOf<String>()

    val merged = serverSummaries.map { server ->
      val key = server.padaName.trim().lowercase()
      val local = localByName[key] ?: return@map server
      mergedServerNames += key
      server.copy(
        open = server.open.maxWith(local.open),
        referralFollowUp = server.referralFollowUp.maxWith(local.referralFollowUp),
        visitsRemainingCount = maxOf(server.visitsRemainingCount, local.visitsRemainingCount),
      )
    }

    val localOnly = localSummaries.filter { it.padaName.trim().lowercase() !in mergedServerNames }
    return merged + localOnly
  }

  private fun PadaVisitBucket.maxWith(other: PadaVisitBucket) = PadaVisitBucket(
    womenCount = maxOf(womenCount, other.womenCount),
    womenOverdueCount = maxOf(womenOverdueCount, other.womenOverdueCount),
    childCount = maxOf(childCount, other.childCount),
    childOverdueCount = maxOf(childOverdueCount, other.childOverdueCount),
  )

  private companion object {
    const val UNKNOWN_PADA_DASH = "—"
  }
}
