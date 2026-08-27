package org.armman.sakhi.ui.visittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.visittracker.PadaSummary
import org.armman.sakhi.data.visittracker.PadaVisitBucket
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.PrimarySurface
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/** Fixed so the Women/Child stats line up between the Open and Referral Follow-up rows
 * regardless of whether the highlighted "(N)" suffix is present on a given row. Sized generously
 * enough that "Women N(N)" never wraps to a second line — [BucketStat] also caps at one line as
 * a safety net for double-digit counts. */
private val WomenStatWidth = 108.dp
private val ChildStatWidth = 72.dp

/**
 * Pada aggregate card, backed by [PadaSummary] (M3: `GET /sakhi/{sakhiId}/padas`).
 *
 * Per Figma p63/p65: an Open row and a Referral Follow-up row, each with a Women/Child count.
 * The `(N)` highlighted count is women-only by design (confirmed 2026-08-17) — [PadaVisitBucket]
 * also carries `childOverdueCount` from the API, but it is intentionally not rendered here.
 */
@Composable
fun PadaCard(
  summary: PadaSummary,
  isTablet: Boolean,
  onSeeVisits: (padaId: String, padaName: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .softShadow(cornerRadius = Dimens.CardRadius)
      .clip(RoundedCornerShape(Dimens.CardRadius))
      .background(White)
      .padding(Dimens.ItemSpacing),
  ) {
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Design (Figma p63/p65) shows only the pada name as the card title — no separate village
      // subtitle line. [PadaSummary.villageName] is kept on the model for search/accessibility
      // use elsewhere, just not rendered here.
      Text(
        text = summary.padaName,
        style = MaterialTheme.typography.titleLarge,
        color = NeutralG400,
      )
      Text(
        text = pluralStringResource(
          R.plurals.visit_tracker_visits_remaining,
          summary.visitsRemainingCount,
          summary.visitsRemainingCount,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = Primary,
        modifier = Modifier
          .background(PrimarySurface, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    BucketRow(
      label = stringResource(R.string.visit_tracker_open),
      bucket = summary.open,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    BucketRow(
      label = stringResource(R.string.visit_tracker_referral_follow_up),
      bucket = summary.referralFollowUp,
      modifier = Modifier.padding(top = Dimens.ItemSpacing),
    )
    PrimaryButton(
      text = stringResource(R.string.visit_tracker_see_visits),
      onClick = { onSeeVisits(summary.padaId, summary.padaName) },
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
      fullWidth = false,
      height = if (isTablet) Dimens.ChipHeight else Dimens.SmallButtonHeight,
      modifier = Modifier
        .align(Alignment.End)
        .padding(top = Dimens.ItemSpacing),
    )
  }
}

/** One visit-type row: bucket label (Open / Referral Follow-up) on the left, Women/Child counts
 * on the right in fixed-width columns so they align between the Open and Referral Follow-up
 * rows. Only the Women count gets a highlighted `(N)` suffix — see [PadaCard] doc. */
@Composable
private fun BucketRow(label: String, bucket: PadaVisitBucket, modifier: Modifier = Modifier) {
  Row(
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG400,
      modifier = Modifier.weight(1f),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
      BucketStat(
        icon = painterResource(R.drawable.ic_woman),
        label = stringResource(R.string.visit_tracker_women),
        count = bucket.womenCount,
        // Always shown (even "(0)") so the column width — and the Child column after it — never
        // shifts depending on whether this pada happens to have a highlighted count today.
        highlightedCount = bucket.womenOverdueCount,
        modifier = Modifier.width(WomenStatWidth),
      )
      BucketStat(
        icon = painterResource(R.drawable.ic_baby),
        label = stringResource(R.string.visit_tracker_child),
        count = bucket.childCount,
        // Women-only highlight by design (confirmed 2026-08-17) — null means "never render a
        // bracket for this stat", not "render (0)". Never pass childOverdueCount here.
        highlightedCount = null,
        modifier = Modifier.width(ChildStatWidth),
      )
    }
  }
}

/** [highlightedCount] of `null` means this stat never gets a bracketed suffix (Child); a non-null
 * value — including 0 — always renders "(N)" (Women), so the layout is stable whether or not
 * today's pada happens to have a highlighted count. */
@Composable
private fun BucketStat(
  icon: Painter,
  label: String,
  count: Int,
  highlightedCount: Int?,
  modifier: Modifier = Modifier,
) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Icon(
      painter = icon,
      contentDescription = null, // decorative — the adjacent label text already conveys meaning
      tint = NeutralG400,
      modifier = Modifier.size(16.dp),
    )
    Spacer(modifier = Modifier.width(4.dp))
    val text = buildAnnotatedString {
      append("$label $count")
      if (highlightedCount != null) {
        withStyle(SpanStyle(color = Primary)) { append("($highlightedCount)") }
      }
    }
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = NeutralG400,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
