package org.armman.sakhi.data.healtheducation

/**
 * CR-M3-06 (Contextual Health Education & "Learn More" placeholder).
 *
 * REWRITTEN 2026-08-28 after backend's response to `docs/backend-requests/CR-M3-06-health-
 * education-content-api.md` — the versioned content-bundle model this file previously defined
 * (stage/entityType/messageOrder/contentStatus, a `messages` array) does not exist anywhere in
 * `cms-content-service` and nothing shaped like it is planned. What's real today, per backend's
 * confirmed sample response, is a single, tiny "Learn More" model: `LearnMoreTopic` (`topicCode`,
 * `topicName`, `mediaType`, `contentUrl`), seeded with exactly one row (`topicCode: "COMING_SOON"`)
 * that every `isEducationTrigger` condition currently resolves to — there is no per-condition
 * mapping table yet. Do not re-add stage/versioning/contentStatus fields without a fresh backend
 * confirmation; they were never real.
 */

/** `LearnMoreTopic.mediaType` — `QNA_TEXT` is the only value confirmed live (the seeded
 * `COMING_SOON` row). `IMAGE`/`AUDIO`/`VIDEO` are kept as forward-compatible decode targets per
 * CR-M3-06 requirement #8 (media with text-only fallback) but are NOT confirmed to exist in the
 * backend's enum — [UNKNOWN] is the safe decode-failure fallback for anything else. */
enum class HealthEducationMediaType { QNA_TEXT, IMAGE, AUDIO, VIDEO, UNKNOWN }

/** One `LearnMoreTopic` row, 1:1 with backend's `GET /learn-more/topics/{topicCode}` /
 * `GET /beneficiaries/{beneficiaryId}/risk`'s embedded `educationContent` shape. */
data class HealthEducationTopic(
  val topicCode: String,
  val topicName: String,
  val mediaType: HealthEducationMediaType,
  /** Null for the seeded `COMING_SOON` row today — text-only render (requirement #8) whenever
   * this is null, regardless of [mediaType]. */
  val contentUrl: String?,
)
