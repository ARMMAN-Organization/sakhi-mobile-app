package org.armman.sakhi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.armman.sakhi.R
import org.armman.sakhi.data.dashboard.DashboardSummary
import org.armman.sakhi.ui.theme.ButtonTextTablet
import org.armman.sakhi.ui.components.PrimaryButton
import org.armman.sakhi.ui.components.SecondaryButton
import org.armman.sakhi.ui.components.StatTile
import org.armman.sakhi.ui.components.SummaryCard
import org.armman.sakhi.ui.theme.Dimens
import org.armman.sakhi.ui.theme.KpiNumberSecondary
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.NeutralG75
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Success-state body of the Home dashboard: Sakhi row, cards and bottom bar.
 *
 * M3: cards render [DashboardSummary]'s real API fields. [ActiveVisitsCard] and
 * [ActiveBeneficiariesCard] are 2-tile rows per the confirmed design (bharath, 2026-08-17) —
 * [DashboardSummary.overdueVisitsCount] + [DashboardSummary.dueVisitsCount] are combined into a
 * single "Open" figure, with [DashboardSummary.endingSoonVisitsCount] broken out alongside it, and
 * [DashboardSummary.accompaniedReferralsCount] is not shown on Home (no tile for it in the
 * design) — see docs/test-cases/dashboard-visit-tracker-api.md for the full field mapping.
 */
@Composable
internal fun HomeContent(
  summary: DashboardSummary,
  pendingUploadCount: Int,
  onAllBeneficiaries: () -> Unit = {},
  onSeeVisitTracker: () -> Unit = {},
  onRegisterNew: () -> Unit = {},
  onDataUploadClick: () -> Unit = {},
) {
  Column(modifier = Modifier.fillMaxSize()) {
    Column(
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ScreenPadding),
    ) {
      SakhiRow(summary, pendingUploadCount, onDataUploadClick)
      ActiveVisitsCard(summary, onSeeVisitTracker)
      ActiveBeneficiariesCard(summary)
    }
    BottomActionBar(onAllBeneficiaries = onAllBeneficiaries, onRegisterNew = onRegisterNew)
  }
}

/** Sakhi name + Data Upload pill on one row, "Updated" caption under the pill. */
@Composable
private fun SakhiRow(summary: DashboardSummary, pendingUploadCount: Int, onDataUploadClick: () -> Unit) {
  val updatedOn = summary.lastSyncedAt?.let {
    DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
      .withZone(ZoneId.systemDefault())
      .format(it)
  }
  Row(
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
    modifier = Modifier.fillMaxWidth(),
  ) {
    // Name centers against the pill's height; caption centers under the pill.
    val isTabletRow = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
    val pillHeight = if (isTabletRow) Dimens.DataUploadPillHeightTablet else Dimens.DataUploadPillHeight
    Text(
      text = summary.sakhiName,
      style = MaterialTheme.typography.headlineSmall,
      color = NeutralG400,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      // Cards below (e.g. "Active Visits") sit inside a SummaryCard with its own
      // Dimens.ItemSpacing inner padding on top of this row's outer padding, so their title
      // text starts one extra ItemSpacing in from the screen edge. Match that here so the
      // Sakhi's name lines up with "Active Visits"/"Active Beneficiaries" below it.
      modifier = Modifier
        .weight(1f)
        .padding(start = Dimens.ItemSpacing, end = Dimens.SmallSpacing)
        .height(pillHeight)
        .wrapContentHeight(Alignment.CenterVertically),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
      DataUploadPill(pendingCount = pendingUploadCount, onClick = onDataUploadClick)
      Text(
        text = updatedOn?.let { stringResource(R.string.home_updated_on, it) }
          ?: stringResource(R.string.home_never_synced),
        style = if (isTablet) {
          MaterialTheme.typography.bodyLarge
        } else {
          MaterialTheme.typography.bodyMedium
        },
        color = NeutralG200,
        modifier = Modifier.padding(top = Dimens.SmallSpacing),
      )
    }
  }
}

@Composable
private fun DataUploadPill(pendingCount: Int, onClick: () -> Unit) {
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val badgeSize = if (isTablet) 28.dp else 24.dp
  // Nothing left to upload: the pill goes inert (grey, unclickable) rather than staying an
  // actionable-looking purple CTA with no action left to take.
  val allUploaded = pendingCount <= 0
  Button(
    onClick = onClick,
    enabled = !allUploaded,
    colors = ButtonDefaults.buttonColors(
      disabledContainerColor = NeutralG75,
      disabledContentColor = White,
    ),
    contentPadding = PaddingValues(horizontal = Dimens.PillButtonPaddingH),
    modifier = Modifier.height(if (isTablet) Dimens.DataUploadPillHeightTablet else Dimens.DataUploadPillHeight),
  ) {
    if (pendingCount > 0) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(badgeSize).background(White, CircleShape),
      ) {
        Text(
          text = pendingCount.toString(),
          style = if (isTablet) {
            MaterialTheme.typography.titleMedium
          } else {
            MaterialTheme.typography.labelLarge
          },
          color = Primary,
        )
      }
    } else {
      Icon(
        painter = painterResource(R.drawable.ic_check_circle),
        contentDescription = null,
        tint = StatusSuccess,
        modifier = Modifier.size(badgeSize).background(White, CircleShape),
      )
    }
    Text(
      text = stringResource(R.string.home_data_upload),
      style = if (isTablet) ButtonTextTablet else MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(start = Dimens.SmallSpacing),
    )
  }
}

/**
 * Open (= [DashboardSummary.overdueVisitsCount] + [DashboardSummary.dueVisitsCount]) / Pending
 * Referral Follow-up — 2 tiles per the confirmed design. The Open tile also breaks out
 * [DashboardSummary.endingSoonVisitsCount] in purple, both on the value line ("12 (2)") and as an
 * "(Ending)" caption suffix, matching the Figma Home board. The ending count is a subset of Open,
 * not additional to it — always shown, including "(0)", so the wiring is visually verifiable even
 * against test accounts with nothing ending soon.
 */
@Composable
private fun ActiveVisitsCard(summary: DashboardSummary, onSeeVisitTracker: () -> Unit) {
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val openVisitsCount = summary.overdueVisitsCount + summary.dueVisitsCount
  val endingSoonCount = summary.endingSoonVisitsCount
  // Current month/year, e.g. "Jan 2026" — matches the design's Active Visits trailing label.
  // Locale-formatted directly (no string resource needed): unlike home_updated_on/
  // home_active_beneficiaries_total this carries no surrounding phrase to localize, just a
  // date already rendered in the device locale. SummaryCard renders it in labelSmall (12sp),
  // smaller than the "Updated {date}" caption under the Data Upload pill (bodyMedium/bodyLarge,
  // 14/16sp) per the confirmed design (CR: Home UI issues, 2026-09-10).
  val currentMonthYear = remember {
    DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()).format(LocalDate.now())
  }
  SummaryCard(title = stringResource(R.string.home_active_visits), trailingLabel = currentMonthYear) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
    ) {
      StatTile(
        icon = painterResource(R.drawable.ic_calendar_blank),
        value = buildAnnotatedString {
          append(openVisitsCount.toString())
          append(" ")
          withStyle(SpanStyle(color = Primary)) { append("($endingSoonCount)") }
        },
        caption = buildAnnotatedString {
          append(stringResource(R.string.home_open))
          append("\n")
          withStyle(SpanStyle(color = Primary)) { append(stringResource(R.string.home_ending)) }
        },
        modifier = Modifier.weight(1f),
      )
      StatTile(
        icon = painterResource(R.drawable.ic_users_round),
        value = buildAnnotatedString { append(summary.pendingFollowUpsCount.toString()) },
        caption = buildAnnotatedString { append(stringResource(R.string.home_pending_referral)) },
        modifier = Modifier.weight(1f),
      )
    }
    // Tablet: centered content-width pill, taller with wide inner padding;
    // mobile: full card width (per the respective designs).
    PrimaryButton(
      text = stringResource(R.string.home_see_visit_tracker),
      onClick = onSeeVisitTracker,
      trailingIcon = painterResource(R.drawable.ic_arrow_right),
      fullWidth = !isTablet,
      height = if (isTablet) Dimens.ButtonHeightTablet else Dimens.ButtonHeight,
      contentPaddingH = if (isTablet) Dimens.PillButtonPaddingHTablet else Dimens.ScreenPadding,
      modifier = Modifier
        .padding(top = Dimens.ItemSpacing)
        .align(Alignment.CenterHorizontally),
    )
  }
}

/**
 * Mothers / Infants — 2 tiles per the confirmed design, each showing the active count with its
 * high-risk subset alongside in red ("21 | 8"), replacing the earlier percent-only display.
 * Accompanied Referrals is not shown on Home (bharath, 2026-08-17) —
 * [DashboardSummary.accompaniedReferralsCount] is still fetched and modeled, just not rendered
 * here. [DashboardSummary.totalActiveBeneficiaries] is shown as the card's trailing label.
 */
@Composable
private fun ActiveBeneficiariesCard(summary: DashboardSummary) {
  SummaryCard(
    title = stringResource(R.string.home_active_beneficiaries),
    trailingLabel = stringResource(R.string.home_active_beneficiaries_total, summary.totalActiveBeneficiaries),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
    ) {
      StatTile(
        icon = painterResource(R.drawable.ic_woman),
        value = countWithHighRisk(summary.activeMothersCount, summary.activeMothersHighRiskCount),
        caption = buildAnnotatedString { append(stringResource(R.string.home_mothers)) },
        modifier = Modifier.weight(1f),
        valueStyle = KpiNumberSecondary,
      )
      StatTile(
        icon = painterResource(R.drawable.ic_baby),
        value = countWithHighRisk(summary.activeChildrenCount, summary.activeChildrenHighRiskCount),
        caption = buildAnnotatedString { append(stringResource(R.string.home_infants)) },
        modifier = Modifier.weight(1f),
        valueStyle = KpiNumberSecondary,
      )
    }
  }
}

/** "<count> | <highRiskCount>" with the high-risk number in [RiskHigh] red, per the Figma Active
 * Beneficiaries tile. The "| N" segment is a subset of count, not additional to it. */
@Composable
private fun countWithHighRisk(count: Int, highRiskCount: Int) = buildAnnotatedString {
  append(count.toString())
  append(" | ")
  withStyle(SpanStyle(color = RiskHigh)) { append(highRiskCount.toString()) }
}

/** Fixed bottom bar: All Beneficiaries and Register New each navigate via their callbacks. */
@Composable
private fun BottomActionBar(onAllBeneficiaries: () -> Unit, onRegisterNew: () -> Unit) {
  Surface(color = White, modifier = Modifier.softShadow()) {
    Column {
      HorizontalDivider(thickness = 1.dp, color = NeutralG50)
      Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        modifier = Modifier.fillMaxWidth().padding(Dimens.ItemSpacing),
      ) {
        SecondaryButton(
          text = stringResource(R.string.home_all_beneficiaries),
          onClick = onAllBeneficiaries,
          modifier = Modifier.weight(1f),
        )
        SecondaryButton(
          text = stringResource(R.string.home_register_new),
          onClick = onRegisterNew,
          trailingIcon = painterResource(R.drawable.ic_plus),
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}
