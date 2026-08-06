package org.armman.sakhi.data.beneficiary

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves My Beneficiaries with the Sakhi's **own** enrolments and nothing else (CR-022g).
 *
 * The fourteen seeded fixtures that used to fill this screen are gone. They existed so the list had
 * something to render before any real data flow existed; now that enrolments produce real
 * beneficiaries with real visit schedules, keeping them would mean a Sakhi could not tell her own
 * work from sample data — and every fixture was a dead end, since none had a schedule behind it.
 *
 * An empty list is therefore a correct and expected state on a fresh install: the screen already
 * renders an empty state for it. The list fills as the Sakhi enrols.
 *
 * Reads from the device only. When the beneficiary-list API lands (CR-024) this gains a remote
 * source and keeps local rows first, so an enrolment made offline never disappears while it waits
 * to sync.
 */
@Singleton
class LocalBeneficiaryRepository @Inject constructor(
  private val localEnrolments: LocalEnrolmentBeneficiarySource,
) : BeneficiaryRepository {

  override suspend fun getBeneficiaries(): List<Beneficiary> =
    localEnrolments.getLocalBeneficiaries()
}
