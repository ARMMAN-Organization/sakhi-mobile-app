package org.armman.sakhi.data.schedule

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * One generated visit, in the shape `POST /visit-schedules/bulk` expects (CR-023 §5.1).
 *
 * Dates are ISO-8601 date-only strings, never timestamps: the server columns are `@db.Date`, and
 * sending an instant invites a timezone shift that would move a real visit to the wrong day.
 * [VisitScheduleEntity] already stores them as [java.time.LocalDate], so `toString()` is the whole
 * conversion.
 */
data class VisitScheduleUploadDto(
  /** The device-generated key the server dedupes on. */
  val localScheduleUuid: String,
  val visitCode: String,
  val visitType: String,
  val sequenceNo: Int,
  val scheduledDate: String,
  val windowStartDate: String,
  val windowEndDate: String,
  val anchorType: String,
  /** Local UUID of the triggering visit for `*_HR` rows; the server resolves it to its own ID. */
  val anchorVisitLocalUuid: String? = null,
)

data class BulkVisitScheduleRequestDto(
  val beneficiaryId: String,
  val generatedByRuleVersionId: String,
  val generatedAt: String,
  val schedules: List<VisitScheduleUploadDto>,
)

/** The server's ID for one uploaded row — what lets the device reference the schedule later. */
data class VisitScheduleUploadResultDto(
  val localScheduleUuid: String,
  val scheduleId: String,
  val status: String? = null,
)

data class BulkVisitScheduleResponseDataDto(
  val beneficiaryId: String? = null,
  val created: Int? = null,
  val alreadyExisted: Int? = null,
  val schedules: List<VisitScheduleUploadResultDto> = emptyList(),
)

data class BulkVisitScheduleResponseDto(
  val success: Boolean,
  val message: String? = null,
  val data: BulkVisitScheduleResponseDataDto? = null,
)

/**
 * Retrofit contract for the schedule-sync endpoint (CR-023). Requires a Bearer token, attached by
 * [org.armman.sakhi.data.auth.AuthInterceptor].
 *
 * One call per beneficiary, not per visit — a full ANC series is ten rows, and ten requests over a
 * rural connection is ten chances to fail.
 */
interface VisitScheduleApi {
  @POST("visit-schedules/bulk")
  suspend fun uploadSchedules(
    @Body request: BulkVisitScheduleRequestDto,
  ): Response<BulkVisitScheduleResponseDto>
}

/** Maps a locally generated row to its upload shape. Enum names travel as strings and must match
 * the server's `VisitCodeType` / `AnchorType` — pinned by `VisitScheduleDaoContractTest`. */
fun VisitScheduleEntity.toUploadDto() = VisitScheduleUploadDto(
  localScheduleUuid = localScheduleUuid,
  visitCode = visitCode,
  visitType = visitType.name,
  sequenceNo = sequenceNo,
  scheduledDate = scheduledDate.toString(),
  windowStartDate = windowStartDate.toString(),
  windowEndDate = windowEndDate.toString(),
  anchorType = anchorType.name,
  anchorVisitLocalUuid = anchorVisitLocalUuid,
)
