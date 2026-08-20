package org.armman.sakhi.data.delivery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** CR-042 (Delivery Event Session). Same shape as `RoomVisitScheduleRepositoryTest` — no
 * Robolectric in this repo, so this exercises [RoomDeliverySessionRepository] against
 * [FakeDeliverySessionDao], not a real Room instance. The `MIGRATION_7_8` SQL itself is manual QA,
 * same as every other additive migration in this database (v4-v7). */
class RoomDeliverySessionRepositoryTest {

  private lateinit var dao: FakeDeliverySessionDao
  private lateinit var repository: RoomDeliverySessionRepository

  @Before
  fun setUp() {
    dao = FakeDeliverySessionDao()
    repository = RoomDeliverySessionRepository(dao)
  }

  private fun session(
    localSessionUuid: String = "session-1",
    localBeneficiaryId: String = "mother-1",
    step: DeliverySessionStep = DeliverySessionStep.DELIVERY_FORM,
    deliverySubmissionLocalUuid: String? = null,
    deliveryFormFilledOn: LocalDate? = null,
    child1BeneficiaryId: String? = null,
    child2BeneficiaryId: String? = null,
    child3BeneficiaryId: String? = null,
    nextChildIndexToRegister: Int = 0,
  ) = DeliverySessionEntity(
    localSessionUuid = localSessionUuid,
    localBeneficiaryId = localBeneficiaryId,
    step = step,
    deliverySubmissionLocalUuid = deliverySubmissionLocalUuid,
    deliveryFormFilledOn = deliveryFormFilledOn,
    child1BeneficiaryId = child1BeneficiaryId,
    child2BeneficiaryId = child2BeneficiaryId,
    child3BeneficiaryId = child3BeneficiaryId,
    nextChildIndexToRegister = nextChildIndexToRegister,
    createdAtEpochMillis = 1_755_000_000_000L,
    updatedAtEpochMillis = 1_755_000_000_000L,
  )

  @Test
  fun `save and read back a session by its own uuid`() = runTest {
    repository.save(session())

    val stored = repository.getBySessionUuid("session-1")

    assertEquals("mother-1", stored?.localBeneficiaryId)
    assertEquals(DeliverySessionStep.DELIVERY_FORM, stored?.step)
  }

  @Test
  fun `a beneficiary with no session yet has nothing active`() = runTest {
    assertNull(repository.getActiveForBeneficiary("mother-1"))
  }

  @Test
  fun `an in-progress session is returned as active`() = runTest {
    repository.save(session(step = DeliverySessionStep.CHILD_REGISTRATION))

    val active = repository.getActiveForBeneficiary("mother-1")

    assertEquals("session-1", active?.localSessionUuid)
  }

  @Test
  fun `a DONE session is not returned as active`() = runTest {
    repository.save(session(step = DeliverySessionStep.DONE))

    assertNull(repository.getActiveForBeneficiary("mother-1"))
  }

  @Test
  fun `re-saving the same session uuid updates it in place, matching REPLACE semantics`() = runTest {
    repository.save(session(step = DeliverySessionStep.DELIVERY_FORM))
    repository.save(
      session(
        step = DeliverySessionStep.CHILD_REGISTRATION,
        deliverySubmissionLocalUuid = "delivery-sub-1",
        child1BeneficiaryId = "child-1",
      ),
    )

    val stored = repository.getBySessionUuid("session-1")

    assertEquals(DeliverySessionStep.CHILD_REGISTRATION, stored?.step)
    assertEquals("delivery-sub-1", stored?.deliverySubmissionLocalUuid)
    assertEquals("child-1", stored?.child1BeneficiaryId)
  }

  @Test
  fun `twin registration resume index survives a save-reload cycle`() = runTest {
    repository.save(
      session(
        step = DeliverySessionStep.CHILD_REGISTRATION,
        child1BeneficiaryId = "child-1",
        child2BeneficiaryId = "child-2",
        nextChildIndexToRegister = 1,
      ),
    )

    val stored = repository.getBySessionUuid("session-1")

    assertEquals(1, stored?.nextChildIndexToRegister)
    assertEquals("child-2", stored?.child2BeneficiaryId)
  }

  @Test
  fun `deliveryFormFilledOn survives a save-reload cycle`() = runTest {
    val filledOn = LocalDate.of(2026, 8, 10)
    repository.save(
      session(step = DeliverySessionStep.PP1, deliveryFormFilledOn = filledOn),
    )

    val stored = repository.getBySessionUuid("session-1")

    assertEquals(filledOn, stored?.deliveryFormFilledOn)
  }

  @Test
  fun `deliveryFormFilledOn defaults to null, matching the DELIVERY_FORM entry state`() = runTest {
    repository.save(session())

    val stored = repository.getBySessionUuid("session-1")

    assertNull(stored?.deliveryFormFilledOn)
  }

  @Test
  fun `two different mothers each get their own independent active session`() = runTest {
    repository.save(session(localSessionUuid = "session-1", localBeneficiaryId = "mother-1"))
    repository.save(session(localSessionUuid = "session-2", localBeneficiaryId = "mother-2"))

    assertEquals("session-1", repository.getActiveForBeneficiary("mother-1")?.localSessionUuid)
    assertEquals("session-2", repository.getActiveForBeneficiary("mother-2")?.localSessionUuid)
  }

  @Test
  fun `a beneficiary with no session yet has nothing for getMostRecentForBeneficiary either`() = runTest {
    assertNull(repository.getMostRecentForBeneficiary("mother-1"))
  }

  @Test
  fun `getMostRecentForBeneficiary returns a DONE session, unlike getActiveForBeneficiary`() = runTest {
    repository.save(session(step = DeliverySessionStep.DONE, deliverySubmissionLocalUuid = "delivery-sub-1"))

    assertNull(repository.getActiveForBeneficiary("mother-1"))
    assertEquals(
      "delivery-sub-1",
      repository.getMostRecentForBeneficiary("mother-1")?.deliverySubmissionLocalUuid,
    )
  }

  @Test
  fun `getMostRecentForBeneficiary only ever returns that beneficiary's own session`() = runTest {
    repository.save(session(localSessionUuid = "session-1", localBeneficiaryId = "mother-1"))
    repository.save(session(localSessionUuid = "session-2", localBeneficiaryId = "mother-2"))

    assertEquals("session-1", repository.getMostRecentForBeneficiary("mother-1")?.localSessionUuid)
    assertEquals("session-2", repository.getMostRecentForBeneficiary("mother-2")?.localSessionUuid)
  }
}
