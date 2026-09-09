package org.armman.sakhi.data.schedule

import kotlinx.coroutines.test.runTest
import org.armman.sakhi.data.delivery.DeliverySessionEntity
import org.armman.sakhi.data.delivery.DeliverySessionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** CR-Delivery-01: [SameSessionNnVisitResolver] is the single place "which same-session NN visit
 * is due" is decided -- see that class's own doc for the bug this replaced (a coordinator-side
 * lookup that queried the wrong, mother's, beneficiary id and so never found anything). */
class SameSessionNnVisitResolverTest {

  private val deliveryFormFilledOn = LocalDate.of(2026, 8, 7)

  private fun session(
    child1BeneficiaryId: String? = null,
    child2BeneficiaryId: String? = null,
    child3BeneficiaryId: String? = null,
    deliveryFormFilledOnDate: LocalDate? = deliveryFormFilledOn,
  ) = DeliverySessionEntity(
    localSessionUuid = "delivery-session-1",
    localBeneficiaryId = "mother-1",
    step = DeliverySessionStep.PP1,
    deliverySubmissionLocalUuid = "delivery-sub-1",
    deliveryFormFilledOn = deliveryFormFilledOnDate,
    child1BeneficiaryId = child1BeneficiaryId,
    child2BeneficiaryId = child2BeneficiaryId,
    child3BeneficiaryId = child3BeneficiaryId,
    createdAtEpochMillis = 1_755_000_000_000L,
    updatedAtEpochMillis = 1_755_000_000_000L,
  )

  private suspend fun repositoryWith(vararg visits: VisitScheduleEntity): VisitScheduleRepository {
    val dao = FakeVisitScheduleDao()
    val repository = RoomVisitScheduleRepository(dao)
    repository.saveGenerated(visits.toList())
    return repository
  }

  @Test
  fun `resolves the single registered child's matching NN row`() = runTest {
    val repository = repositoryWith(
      schedule(
        "nn1-child-1",
        localBeneficiaryId = "child-1",
        visitCode = "NN1",
        visitType = VisitCodeType.NN,
        sequenceNo = 1,
        scheduledDate = deliveryFormFilledOn,
      ),
    )
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(session(child1BeneficiaryId = "child-1"))

    assertEquals("child-1", match?.childBeneficiaryId)
    assertEquals("nn1-child-1", match?.visit?.localScheduleUuid)
    assertEquals(true, resolver.hasMatch(session(child1BeneficiaryId = "child-1")))
  }

  @Test
  fun `returns null when deliveryFormFilledOn is null`() = runTest {
    // Pre-v11-migration session row — see DeliverySessionEntity.deliveryFormFilledOn's own doc.
    val repository = repositoryWith(
      schedule(
        "nn1-child-1",
        localBeneficiaryId = "child-1",
        visitCode = "NN1",
        visitType = VisitCodeType.NN,
        sequenceNo = 1,
        scheduledDate = deliveryFormFilledOn,
      ),
    )
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(
      session(child1BeneficiaryId = "child-1", deliveryFormFilledOnDate = null),
    )

    assertNull(match)
  }

  @Test
  fun `returns null when no registered child has a matching NN row`() = runTest {
    val repository = repositoryWith(
      schedule(
        "nn2-child-1",
        localBeneficiaryId = "child-1",
        visitCode = "NN2",
        visitType = VisitCodeType.NN,
        sequenceNo = 2,
        // Belongs to the regular tracker (Day 15), not this delivery session.
        scheduledDate = deliveryFormFilledOn.plusDays(15),
      ),
    )
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(session(child1BeneficiaryId = "child-1"))

    assertNull(match)
  }

  @Test
  fun `returns null when the session has no registered child at all`() = runTest {
    val repository = repositoryWith()
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(session())

    assertNull(match)
  }

  @Test
  fun `keeps looking past a child with no match to find the second child's NN row`() = runTest {
    val repository = repositoryWith(
      schedule(
        "nn1-child-2",
        localBeneficiaryId = "child-2",
        visitCode = "NN1",
        visitType = VisitCodeType.NN,
        sequenceNo = 1,
        scheduledDate = deliveryFormFilledOn,
      ),
    )
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(
      session(child1BeneficiaryId = "child-1", child2BeneficiaryId = "child-2"),
    )

    assertEquals("child-2", match?.childBeneficiaryId)
  }

  @Test
  fun `resolves the FIRST child in registration order when more than one has a matching NN row`() = runTest {
    // Twin scenario: both children's NN1 clamp to the same delivery-form-fill date (SR-NN-01
    // Scenario A). sameSessionNnVisit()'s own singleOrNull would treat two pooled rows as
    // ambiguous and return null -- resolving per child in birth order instead always hands off to
    // one of them (child1, deterministically) rather than silently dropping both twins' hand-off.
    val repository = repositoryWith(
      schedule(
        "nn1-child-1",
        localBeneficiaryId = "child-1",
        visitCode = "NN1",
        visitType = VisitCodeType.NN,
        sequenceNo = 1,
        scheduledDate = deliveryFormFilledOn,
      ),
      schedule(
        "nn1-child-2",
        localBeneficiaryId = "child-2",
        visitCode = "NN1",
        visitType = VisitCodeType.NN,
        sequenceNo = 1,
        scheduledDate = deliveryFormFilledOn,
      ),
    )
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(
      session(child1BeneficiaryId = "child-1", child2BeneficiaryId = "child-2"),
    )

    assertEquals("child-1", match?.childBeneficiaryId)
    assertEquals("nn1-child-1", match?.visit?.localScheduleUuid)
  }

  @Test
  fun `excludes an already-completed NN row via getOpenByType`() = runTest {
    val repository = repositoryWith(
      schedule(
        "nn1-child-1",
        localBeneficiaryId = "child-1",
        visitCode = "NN1",
        visitType = VisitCodeType.NN,
        sequenceNo = 1,
        scheduledDate = deliveryFormFilledOn,
        status = VisitScheduleStatus.COMPLETED,
      ),
    )
    val resolver = SameSessionNnVisitResolver(repository)

    val match = resolver.resolve(session(child1BeneficiaryId = "child-1"))

    assertNull(match)
  }
}
