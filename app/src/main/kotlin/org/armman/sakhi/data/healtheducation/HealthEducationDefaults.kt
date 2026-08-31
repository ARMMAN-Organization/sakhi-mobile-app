package org.armman.sakhi.data.healtheducation

/**
 * The one seeded `LearnMoreTopic` row, copied verbatim from backend's confirmed sample response
 * for `GET /learn-more/topics/COMING_SOON` (2026-08-28) — NOT invented/guessed content, unlike
 * the deleted `HealthEducationStubContent` (see `_to_delete/HealthEducationStubContent.kt.bak` in
 * this package — moved there, not deleted outright, since this session's device shell can't
 * delete files in a connected folder; please delete that file for real), which fabricated
 * per-condition CSV-sourced messages against a content-bundle contract that turned out not to
 * exist.
 *
 * Used only as [RemoteHealthEducationRepository]'s last-resort offline/failure fallback — a live
 * or cached fetch of the real `COMING_SOON` topic always wins when either is available. Since
 * backend confirmed every `isEducationTrigger` condition resolves to this same topic today (no
 * per-condition mapping table exists yet), this fallback is not a guess about what *should* show —
 * it is what the real endpoint returns in the one case that exists.
 */
object HealthEducationDefaults {
  const val COMING_SOON_TOPIC_CODE = "COMING_SOON"

  val COMING_SOON_TOPIC = HealthEducationTopic(
    topicCode = COMING_SOON_TOPIC_CODE,
    topicName = "Content coming soon",
    mediaType = HealthEducationMediaType.QNA_TEXT,
    contentUrl = null,
  )
}
