package org.armman.sakhi.data.schedule

import androidx.room.TypeConverter
import java.time.LocalDate

/**
 * Room converters for [LocalDate]. The app's first converters — the three draft tables only ever
 * stored epoch millis, but a schedule's dates are calendar dates, not instants.
 *
 * Stored as ISO-8601 TEXT (`2026-08-04`) rather than an epoch number, for three reasons:
 *  - it matches the server contract exactly (`@db.Date`, date-only strings — CR-023 §7), so sync is
 *    a straight pass-through with no re-serialisation step to get wrong;
 *  - a schedule date is a *calendar day*, not a moment. Converting through epoch millis invites a
 *    timezone or DST shift, and a one-day shift means a real Sakhi visits a real woman on the wrong
 *    day. `LocalDate.toString()`/`parse` cannot drift;
 *  - it is readable in the DB inspector during QA.
 *
 * Registered on [org.armman.sakhi.data.db.SakhiDatabase]. Enums need no converter — Room stores
 * them as TEXT by name, which is also how they travel in the sync payload.
 */
object ScheduleTypeConverters {

  @TypeConverter
  @JvmStatic
  fun fromLocalDate(value: LocalDate?): String? = value?.toString()

  @TypeConverter
  @JvmStatic
  fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
