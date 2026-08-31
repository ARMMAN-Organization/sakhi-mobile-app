package org.armman.sakhi.ui.healtheducation

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.healtheducation.HealthEducationMediaType
import org.armman.sakhi.data.healtheducation.HealthEducationRepository
import org.armman.sakhi.data.healtheducation.HealthEducationTopic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CR-M3-06 requirement #10 — no test file existed for this ViewModel before this pass. */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthEducationViewModelTest {

  private class FakeHealthEducationRepository : HealthEducationRepository {
    var topicsByCode: Map<String, HealthEducationTopic> = emptyMap()
    var lastBeneficiaryId: String? = null
    var lastRequestedCodes: Set<String>? = null

    override suspend fun getEducationContentForBeneficiary(
      beneficiaryId: String,
      conditionCodes: Set<String>,
    ): Map<String, HealthEducationTopic> {
      lastBeneficiaryId = beneficiaryId
      lastRequestedCodes = conditionCodes
      return topicsByCode.filterKeys { it in conditionCodes }
    }

    override suspend fun getPlaceholderTopic(): HealthEducationTopic =
      throw UnsupportedOperationException("not used by this screen's post-submission flow")
  }

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var repository: FakeHealthEducationRepository

  private val comingSoon = HealthEducationTopic(
    topicCode = "COMING_SOON",
    topicName = "Content coming soon",
    mediaType = HealthEducationMediaType.QNA_TEXT,
    contentUrl = null,
  )

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    repository = FakeHealthEducationRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun buildViewModel(beneficiaryId: String = "beneficiary-1", conditionCodes: String = "JAUNDICE") =
    HealthEducationViewModel(
      savedStateHandle = SavedStateHandle(
        mapOf(
          HealthEducationViewModel.NAV_ARG_BENEFICIARY_ID to beneficiaryId,
          HealthEducationViewModel.NAV_ARG_CONDITION_CODES to conditionCodes,
        ),
      ),
      repository = repository,
    )

  @Test
  fun `resolves a card for the requested condition and forwards the beneficiary id`() {
    repository.topicsByCode = mapOf("JAUNDICE" to comingSoon)

    val viewModel = buildViewModel(beneficiaryId = "beneficiary-42", conditionCodes = "JAUNDICE")
    testDispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(!state.isLoading)
    assertEquals(1, state.cards.size)
    assertEquals("JAUNDICE", state.cards[0].conditionCode)
    assertEquals(comingSoon, state.cards[0].topic)
    assertEquals("beneficiary-42", repository.lastBeneficiaryId)
  }

  @Test
  fun `preserves trigger order, not the repository's map order`() {
    val other = comingSoon.copy(topicName = "Other")
    repository.topicsByCode = mapOf("ANEMIA" to other, "JAUNDICE" to comingSoon)

    // Nav arg order is JAUNDICE first, ANEMIA second — the order the visit form found them in.
    val viewModel = buildViewModel(conditionCodes = "JAUNDICE,ANEMIA")
    testDispatcher.scheduler.advanceUntilIdle()

    val codes = viewModel.uiState.value.cards.map { it.conditionCode }
    assertEquals(listOf("JAUNDICE", "ANEMIA"), codes)
  }

  @Test
  fun `a condition the repository has nothing for is simply omitted, not a placeholder card`() {
    // The repository contract already guarantees a placeholder for every requested code (see
    // RemoteHealthEducationRepositoryTest) — this covers the ViewModel's own defensive handling
    // if a caller/fake ever violates that contract, rather than crashing on a missing key.
    repository.topicsByCode = emptyMap()

    val viewModel = buildViewModel(conditionCodes = "JAUNDICE")
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.cards.isEmpty())
  }

  @Test
  fun `blank conditionCodes nav arg loads with no cards and never calls the repository`() {
    val viewModel = buildViewModel(conditionCodes = "")
    testDispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertTrue(!state.isLoading)
    assertTrue(state.cards.isEmpty())
    assertEquals(null, repository.lastRequestedCodes)
  }

  @Test
  fun `duplicate condition codes in the nav arg are de-duplicated`() {
    repository.topicsByCode = mapOf("JAUNDICE" to comingSoon)

    val viewModel = buildViewModel(conditionCodes = "JAUNDICE,JAUNDICE")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, viewModel.uiState.value.cards.size)
    assertEquals(setOf("JAUNDICE"), repository.lastRequestedCodes)
  }
}
