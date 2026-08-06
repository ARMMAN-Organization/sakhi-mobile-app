package org.armman.sakhi.data.sync

import org.armman.sakhi.data.childregistration.FakeChildFormSyncScheduler
import org.armman.sakhi.data.enrollment.FakeEnrollmentSyncScheduler
import org.armman.sakhi.data.forms.FakeDynamicFormSyncScheduler
import org.armman.sakhi.data.schedule.FakeVisitScheduleSyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Under manual-only sync (SRS §3A.1) this class is the single path from "Sakhi taps Data Upload" to
 * "data leaves the device". A queue it forgets is a queue that never uploads at all — there is no
 * periodic sweep behind it any more — so the fan-out is worth pinning down explicitly.
 */
class ManualSyncTriggerTest {

  private val dynamic = FakeDynamicFormSyncScheduler()
  private val child = FakeChildFormSyncScheduler()
  private val enrollment = FakeEnrollmentSyncScheduler()
  private val visitSchedules = FakeVisitScheduleSyncScheduler()
  private val trigger = ManualSyncTrigger(dynamic, child, enrollment, visitSchedules)

  @Test
  fun `syncAllQueues nudges every offline queue exactly once`() {
    trigger.syncAllQueues()

    assertEquals(1, dynamic.syncNowCallCount)
    assertEquals(1, child.syncNowCallCount)
    assertEquals(1, enrollment.syncNowCallCount)
    assertEquals(1, visitSchedules.syncNowCallCount)
  }

  /**
   * CR-022's queue carries device-generated visit schedules rather than form drafts. It has no card
   * in the upload modal, so nothing in the UI would reveal it being forgotten here — and a schedule
   * that never uploads leaves the server unable to accept the visits performed against it.
   */
  @Test
  fun `the visit schedule queue is drained too, despite having no card in the modal`() {
    trigger.syncAllQueues()

    assertEquals(1, visitSchedules.syncNowCallCount)
  }

  @Test
  fun `the legacy enrollment queue is drained too, even though it has no card in the modal`() {
    // Deliberate asymmetry: enrollment_drafts rows carry no formCode so UploadRecordsSource can't
    // give them a category card, but rows left behind by an older build must still be able to
    // reach the server. Uploading and displaying are separate concerns here.
    trigger.syncAllQueues()

    assertEquals(1, enrollment.syncNowCallCount)
  }

  @Test
  fun `repeat taps each enqueue another attempt`() {
    trigger.syncAllQueues()
    trigger.syncAllQueues()
    trigger.syncAllQueues()

    // De-duplication is WorkManager's job (ExistingWorkPolicy.KEEP), not this class's — it must not
    // silently swallow a tap, or a Sakhi retrying a FAILED draft would get no response.
    assertEquals(3, dynamic.syncNowCallCount)
    assertEquals(3, child.syncNowCallCount)
    assertEquals(3, enrollment.syncNowCallCount)
    assertEquals(3, visitSchedules.syncNowCallCount)
  }
}
