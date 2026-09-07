package org.armman.sakhi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.notification.AppNotification
import org.armman.sakhi.data.notification.NOTIFICATION_CTA_FILL_REFERRAL_FORM
import org.armman.sakhi.data.notification.NOTIFICATION_TYPE_REFERRAL_INCOMPLETE_UPDATE
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.Information
import org.armman.sakhi.ui.theme.InformationSurface
import org.armman.sakhi.ui.theme.NeutralG400

/**
 * Stacks every active [AppNotification] on the Home dashboard, most-urgent first, each dismissible
 * independently.
 *
 * Expects [notifications] already sorted (by [AppNotification.srsStackRank], per
 * [org.armman.sakhi.ui.home.HomeViewModel.notifications]) — this composable does not re-sort, so
 * the ordering rules live in exactly one place.
 *
 * Visual style: every notification currently renders identically (the [Information] palette) —
 * there is no per-type severity/color styling in the SRS yet, only the stacking rank. Do not
 * invent a color-per-type mapping here without that spec landing first.
 *
 * Bug fix (2026-09-02): this used to be a plain [Column] with no height limit — sitting above a
 * `Modifier.weight(1f)` dashboard `Box` on [org.armman.sakhi.ui.home.HomeScreen], an unbounded,
 * non-scrolling stack of notifications (a Sakhi can easily accumulate 10+ over time — reopen,
 * closure, referral-follow-up updates) claimed the whole screen before the weighted dashboard
 * content below it got any space at all, and there was no way to scroll past it. Now a
 * [LazyColumn] capped at [Dimens.NotificationStackMaxHeight] — same cap-then-scroll pattern as
 * [org.armman.sakhi.ui.home.FormsUploadedModal]'s record list — so the stack scrolls internally
 * once it has more notifications than fit, and the dashboard underneath stays visible and
 * reachable exactly as before this bug.
 */
@Composable
fun NotificationBannerStack(
  notifications: List<AppNotification>,
  onDismiss: (AppNotification) -> Unit,
  onCtaClick: (AppNotification) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (notifications.isEmpty()) return
  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(Dimens.SmallSpacing),
    modifier = modifier
      .fillMaxWidth()
      .heightIn(max = Dimens.NotificationStackMaxHeight)
      .padding(horizontal = Dimens.ItemSpacing, vertical = 8.dp),
  ) {
    items(notifications, key = { it.id }) { notification ->
      NotificationBanner(
        notification = notification,
        onDismiss = { onDismiss(notification) },
        onCtaClick = { onCtaClick(notification) },
      )
    }
  }
}

/** One dismissible banner row. Dismiss is local-only for now (see [org.armman.sakhi.ui.home.HomeViewModel.onDismissNotification]'s
 * doc) — there is no confirmed backend mark-read/dismiss mutation yet, so nothing is persisted
 * server-side; reopening the dashboard after a fresh fetch can surface it again until that lands. */
@Composable
private fun NotificationBanner(
  notification: AppNotification,
  onDismiss: () -> Unit,
  onCtaClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .background(InformationSurface, RoundedCornerShape(8.dp))
      .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
  ) {
    Icon(
      imageVector = Icons.Filled.Notifications,
      contentDescription = null,
      tint = Information,
      modifier = Modifier.size(20.dp),
    )
    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
      Text(
        text = notification.title,
        style = MaterialTheme.typography.labelLarge,
        color = NeutralG400,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (notification.body.isNotBlank()) {
        Text(
          text = notification.body,
          style = MaterialTheme.typography.bodySmall,
          color = NeutralG400,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // Only "Fill Referral Form" (SRS FR-S-7.2 row 2, rejected-follow-up case) has a working CTA
      // today — see NOTIFICATION_CTA_FILL_REFERRAL_FORM's doc for why every other type is text-only.
      if (
        notification.type == NOTIFICATION_TYPE_REFERRAL_INCOMPLETE_UPDATE &&
        notification.ctaType == NOTIFICATION_CTA_FILL_REFERRAL_FORM
      ) {
        TextButton(onClick = onCtaClick) {
          Text(
            text = stringResource(R.string.notification_cta_fill_referral_form),
            style = MaterialTheme.typography.labelLarge,
            color = Information,
          )
        }
      }
    }
    IconButton(onClick = onDismiss) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = stringResource(R.string.notification_dismiss_content_description),
        tint = NeutralG400,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}
