package org.armman.sakhi.ui.delivery

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfile
import org.armman.sakhi.data.beneficiaryprofile.BeneficiaryProfileRepository
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliveryFormSubmitResult
import org.armman.sakhi.data.forms.FakeFormsRepository
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormFieldOption
import org.armman.sakhi.data.forms.FormFieldSchema
import org.armman.sakhi.data.forms.FormVersion
import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.lookup.FakeLookupRepository
import org.armman.sakhi.data.schedule.FakeVisitScheduleDao
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.schedule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Covers [DeliverySessionViewModel] — schema load, submit's three outcome branches (Synced with/
 * without children, QueuedOffline, Failed), and the PP1 lookup that follows a successful sync. Not
 * covered here (out of this ViewModel's scope per its own class doc): CHILD_REGISTRATION and NN
 * steps have no UI of their own yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliverySessionViewModelTest {

  /** Records every [submitDraft] call and returns whatever [resultToReturn] is configured —
   * mirrors [org.armman.sakhi.ui.adhocform.AdHocFormViewModelTest]'s fake-draft-repository shape. */
  private class FakeDeliveryFormDraftRepository : DeliveryFormDraftRepository {
    var resultToReturn: DeliveryFormSubmitResult = DeliveryFormSubmitResult.Synced(childBeneficiaryIds = null)
    val submittedAnswers = mutableListOf<FormAnswers>()
    var lastDeliveryDate: LocalDate? = null
    var lastDeliveryFormFilledOn: LocalDate? = null

    override suspend fun submitDraft(
      localSubmissionUuid: String,
      localSessionUuid: String,
      localBeneficiaryId: String,
      formVersionId: String,
      answers: FormAnswers,
      deliveryDate: LocalDate,
      deliveryFormFilledOn: LocalDate,
    ): DeliveryFormSubmitResult {
      submittedAnswers += answers
      lastDeliveryDate = deliveryDate
      lastDeliveryFormFilledOn = deliveryFormFilledOn
      return resultToReturn
    }

    // Not exercised by this ViewModel's own tests — DeliverySessionViewModel only writes drafts,
    // it never reads a stored delivery answers payload back. See
    // DeliveryChildRegistrationViewModelTest for the fake that DOES configure this.
    override suspend fun getAnswers(localSubmissionUuid: String): FormAnswers? = null
  }

  /** Mirrors [org.armman.sakhi.ui.adhocform.AdHocFormViewModelTest]'s own copy — none of this
   * file's tests configure a beneficiary, so an always-throwing lookup is fine: only
   * [DeliverySessionViewModel.loadBeneficiaryDateContext]'s best-effort `runCatching` calls it. */
  private class FakeBeneficiaryProfileRepository(
    private val profilesById: MutableMap<String, BeneficiaryProfile> = mutableMapOf(),
  ) : BeneficiaryProfileRepository {
    override suspend fun getBeneficiary(id: String): BeneficiaryProfile =
      profilesById[id] ?: throw NoSuchElementException("no beneficiary $id")
  }

  private val dispatcher = StandardTestDispatcher()

  private lateinit var formsRepository: FakeFormsRepository
  private lateinit var lookupRepository: FakeLookupRepository
  private lateinit var deliveryFormDraftRepository: FakeDeliveryFormDraftRepository
  private lateinit var scheduleDao: FakeVisitScheduleDao
  private lateinit var visitScheduleRepository: RoomVisitScheduleRepository
  private lateinit var beneficiaryProfileRepository: FakeBeneficiaryProfileRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    formsRepository = FakeFormsRepository()
    lookupRepository = FakeLookupRepository(valuesByCategory = mutableMapOf())
    deliveryFormDraftRepository = FakeDeliveryFormDraftRepository()
    scheduleDao = FakeVisitScheduleDao()
    visitScheduleRepository = RoomVisitScheduleRepository(scheduleDao)
    beneficiaryProfileRepository = FakeBeneficiaryProfileRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun dateField(questionCode: String, required: Boolean = true) = FormFieldSchema(
    label = questionCode,
    required = required,
    inputTypeRaw = "date",
    questionCode = questionCode,
  )

  private fun textField(questionCode: String, section: String? = null) = FormFieldSchema(
    label = questionCode,
    required = false,
    inputTypeRaw = "text",
    questionCode = questionCode,
    section = section,
  )

  private fun selectField(questionCode: String, options: List<FormFieldOption>, section: String? = null) = FormFieldSchema(
    label = questionCode,
    required = false,
    inputTypeRaw = "select",
    questionCode = questionCode,
    options = options,
    section = section,
  )

  private fun versionWith(fields: List<FormFieldSchema>) = FormVersion(
    id = "version-1",
    formDefinitionId = "definition-1",
    versionNo = "v1",
    schemaJson = fields,
    validationJson = emptyList(),
    effectiveFrom = "2026-01-01",
    effectiveTo = null,
    status = "PUBLISHED",
    geography = null,
  )

  private fun buildViewModel(beneficiaryId: String = "mother-1", sessionUuid: String = "session-1") =
    DeliverySessionViewModel(
      formsRepository = formsRepository,
      lookupRepository = lookupRepository,
      deliveryFormDraftRepository = deliveryFormDraftRepository,
      visitScheduleRepository = visitScheduleRepository,
      beneficiaryProfileRepository = beneficiaryProfileRepository,
      savedStateHandle = SavedStateHandle(
        mapOf("beneficiaryId" to beneficiaryId, "sessionUuid" to sessionUuid),
      ),
    )

  @Test
  fun `load() success populates the version and clears isLoading`() {
    formsRepository.version = versionWith(fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)))

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    val state = viewModel.uiState.value
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals("version-1", state.version?.id)
  }

  @Test
  fun `load() with no active version sets hasError`() {
    formsRepository.version = null

    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.hasError)
  }

  @Test
  fun `missing beneficiaryId or sessionUuid sets hasError without calling FormsRepository`() {
    val viewModel = buildViewModel(beneficiaryId = "", sessionUuid = "session-1")
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.hasError)
  }

  @Test
  fun `onSubmit is a no-op while a required field is unanswered`() = runTest {
    formsRepository.version = versionWith(fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)))
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(deliveryFormDraftRepository.submittedAnswers.isEmpty())
  }

  @Test
  fun `onSubmit resolves deliveryDate and deliveryFormFilledOn from the form answers`() = runTest {
    formsRepository.version = versionWith(
      fields = listOf(
        dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY),
        dateField(DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON, required = false),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.setAnswer(DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON, "2026-08-15")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 1), deliveryFormDraftRepository.lastDeliveryDate)
    assertEquals(LocalDate.of(2026, 8, 15), deliveryFormDraftRepository.lastDeliveryFormFilledOn)
  }

  /**
   * [DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON]'s real fallback contract, per that constant's
   * own doc: the fallback to [DeliveryQuestionCodes.DATE_OF_DELIVERY] is for the field being
   * ABSENT from the schema ("if a schema republish ever drops this field"), not merely unanswered.
   * While the field IS present, `prefillTodayDateFields` seeds it with today on load — it is "the
   * date the Sakhi is filling this form", so today is the correct value and the fallback is
   * unreachable by design. This asserts the absent-field path, which is the one that can happen.
   */
  @Test
  fun `onSubmit falls back deliveryFormFilledOn to deliveryDate when the schema omits the field`() = runTest {
    formsRepository.version = versionWith(
      fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 1), deliveryFormDraftRepository.lastDeliveryFormFilledOn)
  }

  /** The companion to the above: while the field IS in the schema and the Sakhi leaves it alone,
   * it carries today's prefill rather than the delivery date. */
  @Test
  fun `onSubmit uses today's prefilled deliveryFormFilledOn when the schema carries the field`() = runTest {
    formsRepository.version = versionWith(
      fields = listOf(
        dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY),
        dateField(DeliveryQuestionCodes.DELIVERY_FORM_FILLED_ON, required = false),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.now(), deliveryFormDraftRepository.lastDeliveryFormFilledOn)
  }

  @Test
  fun `onSubmit Synced with no children emits Submitted with the freshly generated PP1 schedule id`() = runTest {
    formsRepository.version = versionWith(fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)))
    deliveryFormDraftRepository.resultToReturn = DeliveryFormSubmitResult.Synced(childBeneficiaryIds = null)
    visitScheduleRepository.saveGenerated(
      listOf(schedule("pp1-schedule", localBeneficiaryId = "mother-1", visitCode = "PP1", visitType = VisitCodeType.PP, sequenceNo = 1)),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    val submitted = viewModel.events.first() as DeliverySessionEvent.Submitted
    assertNull(submitted.childBeneficiaryIds)
    assertEquals("pp1-schedule", submitted.pp1LocalScheduleUuid)
  }

  @Test
  fun `onSubmit Synced falls back to null pp1LocalScheduleUuid when no PP1 row was found`() = runTest {
    formsRepository.version = versionWith(fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)))
    deliveryFormDraftRepository.resultToReturn = DeliveryFormSubmitResult.Synced(childBeneficiaryIds = listOf("child-1"))
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    val submitted = viewModel.events.first() as DeliverySessionEvent.Submitted
    assertEquals(listOf("child-1"), submitted.childBeneficiaryIds)
    assertNull(submitted.pp1LocalScheduleUuid)
  }

  @Test
  fun `onSubmit QueuedOffline emits QueuedOffline`() = runTest {
    formsRepository.version = versionWith(fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)))
    deliveryFormDraftRepository.resultToReturn = DeliveryFormSubmitResult.QueuedOffline
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(DeliverySessionEvent.QueuedOffline, viewModel.events.first())
  }

  @Test
  fun `onSubmit Failed emits SubmitFailed with the message`() = runTest {
    formsRepository.version = versionWith(fields = listOf(dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY)))
    deliveryFormDraftRepository.resultToReturn = DeliveryFormSubmitResult.Failed("boom")
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()

    val failed = viewModel.events.first() as DeliverySessionEvent.SubmitFailed
    assertEquals("boom", failed.message)
  }

  @Test
  fun `exitForm emits ExitForm`() = runTest {
    formsRepository.version = versionWith(fields = emptyList())
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.exitForm()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(DeliverySessionEvent.ExitForm, viewModel.events.first())
  }

  @Test
  fun `buildSummary groups answered fields into per-section cards, dropping sections with no answers`() = runTest {
    formsRepository.version = versionWith(
      fields = listOf(
        dateField(DeliveryQuestionCodes.DATE_OF_DELIVERY, required = false).copy(section = "Delivery Details"),
        textField("infant_name", section = "Infant Details"),
        textField("untouched_field", section = "Empty Section"),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer(DeliveryQuestionCodes.DATE_OF_DELIVERY, "2026-08-01")
    viewModel.setAnswer("infant_name", "Baby A")

    val summary = viewModel.buildSummary(imageCapturedLabel = "Photo captured")

    assertEquals(listOf("Delivery Details", "Infant Details"), summary.map { it.title })
    assertEquals(
      listOf(SummaryRow(label = DeliveryQuestionCodes.DATE_OF_DELIVERY, value = "2026-08-01")),
      summary.first { it.title == "Delivery Details" }.rows,
    )
  }

  @Test
  fun `buildSummary falls back untagged fields into FALLBACK_SECTION`() = runTest {
    formsRepository.version = versionWith(fields = listOf(textField("untagged_field")))
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer("untagged_field", "some value")

    val summary = viewModel.buildSummary(imageCapturedLabel = "Photo captured")

    assertEquals(listOf(FALLBACK_SECTION), summary.map { it.title })
  }

  @Test
  fun `buildSummary resolves a select field's coded answer to its option label`() = runTest {
    formsRepository.version = versionWith(
      fields = listOf(
        selectField(
          "mode_of_delivery",
          options = listOf(FormFieldOption(label = "Caesarean", sortOrder = 0, valueCode = "caesarean")),
          section = "Delivery Details",
        ),
      ),
    )
    val viewModel = buildViewModel()
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.setAnswer("mode_of_delivery", "caesarean")

    val summary = viewModel.buildSummary(imageCapturedLabel = "Photo captured")

    assertEquals("Caesarean", summary.first { it.title == "Delivery Details" }.rows.first().value)
  }
}
