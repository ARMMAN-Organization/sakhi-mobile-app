package org.armman.sakhi.data.forms

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Matches the backend's `createSubmissionSchema` (`.strict()`) exactly:
 * `formVersionId`/`beneficiaryId` are required UUIDs, `visitId` is nullable/optional (not used by
 * enrollment — only relevant for visit-linked forms, out of scope here), `localSubmissionUuid` is
 * the offline-retry idempotency key (same pattern as `case.localCaseUuid`, CR-017), and `formData`
 * is the free-form answer blob keyed by `question_code`.
 *
 * There used to be a client-supplied `submittedBy` field here (CR-035). It has been removed: the
 * backend rejects/ignores it — attribution is derived server-side from the caller's auth token —
 * and returns who it recorded as the submitter on the response instead, see
 * [SubmissionResponseData.submittedByUserId]. */
data class CreateSubmissionRequestDto(
  val formVersionId: String,
  val beneficiaryId: String,
  val visitId: String? = null,
  val localSubmissionUuid: String,
  val formData: Map<String, Any?>,
)

/**
 * [submittedByUserId] is server-derived (see [CreateSubmissionRequestDto]'s doc for why the
 * request-side `submittedBy` field was removed) — no current consumer reads it; modelled here so a
 * future one (e.g. an audit/debug view) doesn't need a DTO change to get at it.
 *
 * [childBeneficiaryIds] (CR-042, "Delivery Event Session") is present **only** on a
 * `DELIVERY_VISIT` submission's response, and only when at least one baby was live-born — every
 * other form code's response omits the key entirely, which Gson deserializes as `null` here since
 * the field is nullable. Do not read this as `?: emptyList()` — the distinction between "key
 * absent" (`null`, no live birth / non-delivery form) and "key present but empty" (backend
 * contract says this should not happen, but an empty list is still a distinct, valid state from
 * null if it ever does) matters to the delivery session: `null` means "skip the child-registration
 * step entirely," not "loop zero times." Order matches the submitted `child1_/child2_/child3_*`
 * field order; a stillborn or absent child is skipped, never padded with a null entry. Stable
 * across an idempotent retry (same `localSubmissionUuid` replays the same ids, no duplicate child
 * cases) per the backend's `resolveDeliveryChildren()` contract (confirmed 2026-08-18).
 */
data class SubmissionResponseData(
  val id: String,
  val submittedByUserId: String? = null,
  val childBeneficiaryIds: List<String>? = null,
  /**
   * CR-Closure-01 items #5/#6, backend contract confirmed 2026-08-31: present ONLY on the
   * response to the LAST `CCV_VISIT` submission for a child (visit date >= DOB + 730 days) —
   * absent (Gson-deserialized `null`) on every earlier CCV visit and on every non-CCV form code's
   * response, same "key absent on every form but one" shape [childBeneficiaryIds] already has
   * above. `true` = HR was detected at this visit; the app should defer closure into the CCV-HR
   * extension described by [extensionVisit] instead of prompting for child closure. `false` = no
   * HR; the app should route straight into the child closure prompt, same session — do not read
   * `null` as `false` here, since `null` means "not the boundary visit at all", not "no HR".
   */
  val closureDeferredForExtension: Boolean? = null,
  /** Non-null only alongside [closureDeferredForExtension] == `true`. Not yet backed by a real
   * persisted schedule row server-side (backend-confirmed follow-up, not built as of 2026-08-31)
   * — see [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator]'s own doc for how this
   * is surfaced without fabricating a local `visit_schedules` row against an unfinished contract. */
  val extensionVisit: ExtensionVisitWindowDto? = null,
)

/**
 * The CCV-HR extension visit's window, exactly as `POST /forms/submit` returns it for the last
 * CCV visit when [SubmissionResponseData.closureDeferredForExtension] is `true`. Dates are the raw
 * ISO-8601 strings the backend sends — not parsed to [java.time.LocalDate] here, since nothing in
 * this app currently persists this window as a real schedule row (see that field's own doc); it
 * is only ever displayed as-is.
 */
data class ExtensionVisitWindowDto(
  val scheduledDate: String? = null,
  val windowStartDate: String? = null,
  val windowEndDate: String? = null,
)

data class CreateSubmissionResponseDto(
  val success: Boolean,
  val message: String?,
  val data: SubmissionResponseData?,
)

/** Retrofit contract for `visit-form-service`'s form-submission endpoint (CR-018). Requires a
 * Bearer token, attached by [org.armman.sakhi.data.auth.AuthInterceptor]. */
interface FormSubmissionApi {
  @POST("forms/{formCode}/submissions")
  suspend fun createSubmission(
    @Path("formCode") formCode: String,
    @Body request: CreateSubmissionRequestDto,
  ): Response<CreateSubmissionResponseDto>

  /**
   * CR-Registration-Edit: post-submission field correction (task 10/11 of the LMP/Reopen/
   * Referral/Audit gap analysis). Backend contract confirmed 2026-09-02: `edits` is 1-20
   * `{fieldCode, value}` pairs, all-or-nothing (a single bad fieldCode 400s/422s the whole call,
   * nothing is partially applied), and clearing a field to empty is not supported — `value` is
   * never sent as a bare null. Success returns the full updated form submission (same shape as a
   * GET), which this app doesn't need to re-parse: it already knows what it just sent, so
   * [FieldEditsApi] only checks [Response.isSuccessful] and reads the error body on failure. See
   * [org.armman.sakhi.data.forms.FieldEditsRepository] for how the two documented error shapes
   * (`400 VALIDATION_ERROR` unknown fieldCode vs. `422 UNPROCESSABLE` not-editable fieldCode) are
   * told apart.
   */
  @PATCH("form-submissions/{submissionId}/answers")
  suspend fun updateAnswers(
    @Path("submissionId") submissionId: String,
    @Body request: UpdateFormSubmissionAnswersRequestDto,
  ): Response<UpdateFormSubmissionAnswersResponseDto>
}

/** One `{fieldCode, value}` edit. [value] is `Any` (never null — see [FormSubmissionApi
 * .updateAnswers]'s own doc) since the allowlisted fields span string/number answers; Gson
 * serializes a raw [String] the same as every other question answer already flowing through
 * [CreateSubmissionRequestDto.formData]. */
data class FieldEditDto(
  val fieldCode: String,
  val value: Any,
)

data class UpdateFormSubmissionAnswersRequestDto(
  val edits: List<FieldEditDto>,
)

/** Success envelope for `PATCH /form-submissions/:id/answers`. [data] is the full updated
 * submission (same shape a GET would return) — deliberately untyped (`Any?`) here since no caller
 * needs to read it back; see [FormSubmissionApi.updateAnswers]'s own doc for why. */
data class UpdateFormSubmissionAnswersResponseDto(
  val success: Boolean,
  val message: String?,
  val data: Any? = null,
)
