package org.armman.sakhi.ui.beneficiaryprofile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiary.BeneficiaryStatus
import org.armman.sakhi.data.beneficiary.BeneficiaryType
import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.beneficiaryprofile.VitalStat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryProfileViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  private class FakeRepository(
    var error: Exception? = null,
  ) : BeneficiaryProfileRepository {
    override suspend fun getBeneficiary(id: String): BeneficiaryProfile {
      error?.let { throw it }
      return when (id) {
        "mother" -> MOTHER
        "child" -> CHILD
        else -> throw NoSuchElementException("Unknown id: $id")
      }
    }
  }

  private lateinit var repository: FakeRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(id: String?): BeneficiaryProfileViewModel {
    val args = if (id == null) emptyMap() else mapOf(BeneficiaryProfileViewModel.NAV_ARG_ID to id)
    val viewModel = BeneficiaryProfileViewModel(repository, SavedStateHandle(args))
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  @Test
  fun `loads the profile for the given id`() {
    val viewModel = createViewModel("mother")
    val state = viewModel.uiState.value

    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals("Aishwarya Pawar", state.profile?.name)
  }

  @Test
  fun `mother variant exposes lmp and edd but not dob or weight`() {
    val state = createViewModel("mother").uiState.value.profile

    assertEquals(BeneficiaryType.MOTHER, state?.type)
    assertTrue(!state?.lmp.isNullOrBlank())
    assertTrue(!state?.edd.isNullOrBlank())
    assertNull(state?.dob)
    assertNull(state?.weight)
  }

  @Test
  fun `child variant exposes dob and weight but not lmp or edd`() {
    val state = createViewModel("child").uiState.value.profile

    assertEquals(BeneficiaryType.INFANT, state?.type)
    assertTrue(!state?.dob.isNullOrBlank())
    assertTrue(!state?.weight.isNullOrBlank())
    assertNull(state?.lmp)
    assertNull(state?.edd)
  }

  @Test
  fun `repository failure sets error state without leaking profile`() {
    repository.error = IOException("offline")
    val state = createViewModel("mother").uiState.value

    assertFalse(state.isLoading)
    assertTrue(state.hasError)
    assertNull(state.profile)
  }

  @Test
  fun `unknown id sets error state`() {
    val state = createViewModel("ghost").uiState.value
    assertTrue(state.hasError)
  }

  @Test
  fun `missing id argument sets error state`() {
    val state = createViewModel(null).uiState.value
    assertTrue(state.hasError)
  }

  @Test
  fun `retry after error loads successfully`() {
    repository.error = IOException("offline")
    val viewModel = createViewModel("mother")
    assertTrue(viewModel.uiState.value.hasError)

    repository.error = null
    viewModel.loadProfile()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertFalse(state.hasError)
    assertEquals("Aishwarya Pawar", state.profile?.name)
  }

  private companion object {
    val MOTHER = BeneficiaryProfile(
      id = "mother",
      name = "Aishwarya Pawar",
      type = BeneficiaryType.MOTHER,
      ageLabel = "25",
      village = "Rampur",
      pada = "Chausa",
      husbandName = "Akash Sharma",
      mobileNumber = "987563421",
      status = BeneficiaryStatus.ACTIVE,
      riskLevel = RiskLevel.HIGH,
      lmp = "1 Dec 2025",
      edd = "1 Sep 2026",
      diagnoses = listOf("Sickle Cell", "Chronic Diabetes"),
      lastVisitStats = listOf(VitalStat("9 (12)", "Low Hb.", abnormal = true)),
    )

    val CHILD = BeneficiaryProfile(
      id = "child",
      name = "Baby of Aishwarya",
      type = BeneficiaryType.INFANT,
      ageLabel = "2 mo",
      village = "Rampur",
      pada = "Chausa",
      husbandName = "Akash Sharma",
      mobileNumber = "987563421",
      status = BeneficiaryStatus.ACTIVE,
      riskLevel = RiskLevel.MODERATE,
      dob = "10 Nov 2025",
      weight = "2.1 Kg",
      diagnoses = listOf("Low Birth Weight"),
      lastVisitStats = listOf(VitalStat("2.1 (3.2)", "Low Weight", abnormal = true)),
    )
  }
}
