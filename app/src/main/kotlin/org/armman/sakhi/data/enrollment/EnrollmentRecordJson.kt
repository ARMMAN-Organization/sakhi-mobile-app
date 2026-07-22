package org.armman.sakhi.data.enrollment

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import java.time.Instant
import java.time.LocalDate

/**
 * Plain [Gson] has no built-in support for `java.time` types (no no-arg constructor, so its
 * default reflective adapter fails) — [EnrollmentRecord] uses [LocalDate] and [Instant]
 * throughout, so serializing/deserializing it (for the local encrypted draft store) needs this
 * dedicated instance rather than a bare `Gson()`.
 */
val enrollmentRecordGson: Gson = GsonBuilder()
  .registerTypeAdapter(
    LocalDate::class.java,
    JsonSerializer<LocalDate> { src, _, _ -> com.google.gson.JsonPrimitive(src.toString()) },
  )
  .registerTypeAdapter(
    LocalDate::class.java,
    JsonDeserializer { json, _, _ -> LocalDate.parse(json.asString) },
  )
  .registerTypeAdapter(
    Instant::class.java,
    JsonSerializer<Instant> { src, _, _ -> com.google.gson.JsonPrimitive(src.toString()) },
  )
  .registerTypeAdapter(
    Instant::class.java,
    JsonDeserializer { json, _, _ -> Instant.parse(json.asString) },
  )
  .create()
