package org.armman.sakhi.data.beneficiary

import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BeneficiaryRepository] binding for My Beneficiaries — merges the Sakhi's own on-device
 * enrolments ([LocalEnrolmentBeneficiarySource]) with the server's list
 * ([RemoteBeneficiaryRepository]) once [RemoteBeneficiaryListFeatureFlag.ENABLED] is true.
 *
 * ### Why this exists
 * The local-only source loses every enrolment the moment the app is uninstalled or the device is
 * replaced — the Room database and encrypted store are gone with it, even though the beneficiary
 * still exists server-side. A Sakhi should not lose sight of her own caseload just because she got a
 * new phone or had to reinstall.
 *
 * ### Merge rule
 * A beneficiary can be in one of three states from this device's perspective:
 *  - **Local, not yet synced** ([Beneficiary.remoteBeneficiaryId] `== null`): only this device knows
 *    about her. Shown from the local copy — she has no remote row to merge with yet.
 *  - **Local AND synced** (a local row's `remoteBeneficiaryId` matches a remote row's id): shown
 *    ONCE, from the LOCAL copy. The local copy carries the real risk/visit-schedule data — both are
 *    computed/generated entirely on-device (SRS FR-S-2.2) and never uploaded — while the remote copy
 *    has neither. The remote row is dropped so she doesn't appear twice.
 *  - **Remote only** (no local draft at all — enrolled on another device, or this device's local
 *    store was wiped, e.g. an uninstall/reinstall): shown from the remote copy,
 *    [Beneficiary.isAssessed] `== false`, so the card can grey out risk/visit instead of implying an
 *    assessment that never happened on this device.
 *
 * Ordering is a known simplification: the merged list is local rows (already newest-first) followed
 * by remote-only rows, not a true interleave by date. Local `createdAtEpochMillis` and the server's
 * `registrationDate` aren't directly comparable (different precision, different clocks), and a real
 * interleave isn't worth solving before the backend scoping fix even ships — revisit once remote
 * data is a normal, live part of this list rather than a reinstall/second-device fallback.
 *
 * ### Flag off (today's default)
 * Behaves exactly like [LocalBeneficiaryRepository] — local rows only, remote never fetched. See
 * [RemoteBeneficiaryListFeatureFlag] for why it defaults off.
 */
@Singleton
class OfflineFirstBeneficiaryRepository @Inject constructor(
  private val localEnrolments: LocalEnrolmentBeneficiarySource,
  private val remoteBeneficiaries: RemoteBeneficiaryRepository,
) : BeneficiaryRepository {

  override suspend fun getBeneficiaries(): List<Beneficiary> {
    val local = localEnrolments.getLocalBeneficiaries()
    if (!RemoteBeneficiaryListFeatureFlag.ENABLED) return local

    // No network, nothing ever cached — the local-only list is the best available answer, not an
    // empty screen.
    val remote = remoteBeneficiaries.fetchRemoteBeneficiaries() ?: return local

    val syncedRemoteIds = local.mapNotNull { it.remoteBeneficiaryId }.toSet()
    val remoteOnly = remote.filterNot { it.id in syncedRemoteIds }

    return local + remoteOnly
  }
}
