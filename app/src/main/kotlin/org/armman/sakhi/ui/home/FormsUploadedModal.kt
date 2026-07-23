package org.armman.sakhi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.armman.sakhi.R
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.SerifTitle
import org.armman.sakhi.ui.theme.SerifTitleLarge
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow

/**
 * "Forms Uploaded" sync-status modal for the Home screen's Data Upload pill, matching the Figma
 * reference's actual structure: **one card per form category** (e.g. "Mother Registration"), each
 * showing an aggregate "synced/total" count, a single progress bar, and a single status icon —
 * not one card per individual submission. Only one category exists today since the static
 * enrollment flow is deprecated; see [groupUploadRecordsByCategory] for how a second category
 * would slot in later.
 *
 * Two deliberate departures from the Figma reference, both prior decisions in this build:
 * - No ETA pill ("2 mins left" in the reference) — a reliable estimate isn't available from real
 *   sync data, and a fabricated one would be misleading.
 * - A category shows the red "needs attention" treatment if ANY record inside it is
 *   FAILED/DUPLICATE_CONFLICT, regardless of how many others succeeded — the Figma reference
 *   doesn't demonstrate a mixed-outcome category, so this rule was confirmed explicitly rather
 *   than assumed (see [categoryIconKind]'s doc for the full priority order).
 *
 * The header and the single category card show the same fraction while only one category exists
 * — accepted as-is; the structure is ready to scale the moment a second category exists.
 *
 * [FormUploadRecord] deliberately excludes PII (see its doc), so no per-record decryption happens
 * just to render this list.
 */
@Composable
fun FormsUploadedModal(
  records: List<FormUploadRecord>,
  isLoading: Boolean,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Card(
      shape = RoundedCornerShape(Dimens.CardRadius),
      colors = CardDefaults.cardColors(containerColor = White),
      modifier = Modifier.fillMaxWidth(UploadModalWidthFraction).softShadow(cornerRadius = Dimens.CardRadius),
    ) {
      Column(modifier = Modifier.padding(Dimens.ScreenPadding)) {
        val categories = remember(records) { groupUploadRecordsByCategory(records) }
        val syncedCount = records.count { it.syncStatus == EnrollmentSyncStatus.SYNCED }

        // Small circular close button on its own row at the top-right, inside the modal (per the
        // Figma reference — not overlapping the corner). Sized down to match the app's compact
        // icon-button precedent (FilterPopup's close button), not IconButton's 48dp default.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(Dimens.UploadModalCloseButtonSize).background(Primary, CircleShape),
          ) {
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = stringResource(R.string.home_upload_modal_close_content_description),
              tint = White,
              modifier = Modifier.size(Dimens.UploadModalCloseIconSize),
            )
          }
        }

        Text(
          text = stringResource(R.string.home_upload_modal_title, syncedCount, records.size),
          style = SerifTitleLarge,
          color = NeutralG400,
          modifier = Modifier.padding(top = Dimens.SmallSpacing),
        )

        when {
          isLoading -> Row(
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
            horizontalArrangement = Arrangement.Center,
          ) {
            CircularProgressIndicator()
          }

          categories.isEmpty() -> Text(
            text = stringResource(R.string.home_upload_modal_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = NeutralG200,
            modifier = Modifier.padding(top = Dimens.ItemSpacing),
          )

          else -> LazyColumn(
            modifier = Modifier
              .padding(top = Dimens.ItemSpacing)
              .heightIn(max = Dimens.UploadModalListMaxHeight)
              .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
          ) {
            items(categories, key = { it.formCode }) { summary ->
              UploadCategoryCard(summary)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun UploadCategoryCard(summary: FormCategorySummary) {
  Card(
    shape = RoundedCornerShape(Dimens.TileRadius),
    colors = CardDefaults.cardColors(containerColor = White),
    modifier = Modifier.fillMaxWidth().softShadow(cornerRadius = Dimens.TileRadius),
  ) {
    Column(modifier = Modifier.padding(Dimens.ItemSpacing)) {
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(categoryLabelRes(summary.formCode)),
          style = SerifTitle,
          color = NeutralG400,
        )
        StatusIcon(summary.iconKind)
      }
      Text(
        text = stringResource(R.string.home_upload_modal_category_forms, summary.syncedCount, summary.totalCount),
        style = MaterialTheme.typography.labelSmall,
        color = NeutralG200,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.SmallSpacing),
      ) {
        LinearProgressIndicator(
          progress = { categoryProgressFraction(summary) },
          color = categoryBarColor(summary.iconKind),
          trackColor = categoryTrackColor(summary.iconKind),
          strokeCap = StrokeCap.Round,
          // Material3 1.3 (Compose BOM 2024.09) defaults to a track gap + a "stop indicator" dot
          // at the bar's end; both are suppressed here to match the Figma reference's single
          // continuous rounded bar.
          gapSize = 0.dp,
          drawStopIndicator = {},
          modifier = Modifier.weight(1f).height(Dimens.UploadModalProgressBarHeight),
        )
        Text(
          text = "${categoryPercent(summary)}%",
          style = MaterialTheme.typography.labelSmall,
          color = categoryTextColor(summary.iconKind),
        )
      }
    }
  }
}

/** Renders the icon for [kind] — see [categoryIconKind] for how a category's records aggregate
 * into this. */
@Composable
private fun StatusIcon(kind: UploadStatusIconKind) {
  when (kind) {
    UploadStatusIconKind.SYNCED -> Icon(
      painter = painterResource(R.drawable.ic_check_circle),
      contentDescription = stringResource(R.string.home_upload_status_synced),
      tint = StatusSuccess,
      modifier = Modifier.size(Dimens.UploadModalStatusIconSize),
    )

    // Distinct from the sync-loop icon on purpose — a Sakhi should be able to tell "still
    // working on it" apart from "needs your attention" at a glance.
    UploadStatusIconKind.NEEDS_ATTENTION -> Icon(
      painter = painterResource(R.drawable.ic_warning_circle),
      contentDescription = stringResource(R.string.home_upload_status_needs_attention),
      tint = RiskHigh,
      modifier = Modifier.size(Dimens.UploadModalStatusIconSize),
    )

    // Not yet attempted — muted, matches the reference's untouched-form loop icon.
    UploadStatusIconKind.PENDING -> Icon(
      imageVector = Icons.Filled.Refresh,
      contentDescription = stringResource(R.string.home_upload_status_pending),
      tint = categoryIconTint(kind),
      modifier = Modifier.size(Dimens.UploadModalStatusIconSize),
    )

    // Actively in progress — Primary tint, matches the reference's in-progress loop icon.
    UploadStatusIconKind.SYNCING -> Icon(
      imageVector = Icons.Filled.Refresh,
      contentDescription = stringResource(R.string.home_upload_status_syncing),
      tint = categoryIconTint(kind),
      modifier = Modifier.size(Dimens.UploadModalStatusIconSize),
    )
  }
}

private const val UploadModalWidthFraction = 0.92f
