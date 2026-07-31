package org.armman.sakhi.data.motherlink

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.LocalDate

/**
 * Gson for the mother-link offline cache. [LinkedMother] carries two [LocalDate] fields, and plain
 * [Gson] has no `java.time` support (no no-arg constructor, so its default reflective adapter
 * fails) — same reason
 * [org.armman.sakhi.data.enrollment.enrollmentRecordGson] exists for the enrollment draft store.
 *
 * A bare `Gson()` does not fail loudly on Android, which is the trap: with no JPMS there, the
 * reflective fallback succeeds and writes `LocalDate`'s private fields
 * (`{"year":2001,"month":7,"day":29}`) instead of `"2001-07-29"`. That round-trips today but is
 * opaque, inconsistent with every other cache in the app, and breaks on a JDK or Gson upgrade —
 * and it throws outright on the desktop JVM the unit tests run on.
 *
 * A null `LocalDate` never reaches these adapters: Gson omits null fields, and [LinkedMother]
 * tolerates the absence on read (a mother with no parseable DOB stays selectable).
 */
internal val motherLinkGson: Gson = GsonBuilder()
  .registerTypeAdapter(
    LocalDate::class.java,
    JsonSerializer<LocalDate> { src, _, _ -> JsonPrimitive(src.toString()) },
  )
  .registerTypeAdapter(
    LocalDate::class.java,
    JsonDeserializer { json, _, _ -> LocalDate.parse(json.asString) },
  )
  .create()
