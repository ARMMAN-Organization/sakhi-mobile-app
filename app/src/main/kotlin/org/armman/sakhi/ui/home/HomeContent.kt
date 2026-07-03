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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.armman.sakhi.ui.theme.NeutralG200
import org.armman.sakhi.ui.theme.NeutralG400
import org.armman.sakhi.ui.theme.NeutralG50
import org.armman.sakhi.ui.theme.Primary
import org.armman.sakhi.ui.theme.RiskHigh
import org.armman.sakhi.ui.theme.StatusSuccess
import org.armman.sakhi.ui.theme.White
import org.armman.sakhi.ui.theme.softShadow
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Success-state body of the Home dashboard: Sakhi row, cards and bottom bar. */
@Composable
internal fun HomeContent(
  summary: DashboardSummary,
  onAllBeneficiaries: () -> Unit = {},
) {
  Column(modifier = Modifier.fillMaxSize()) {
    Column(
      verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Dimens.ItemSpacing, vertical = Dimens.ScreenPadding),
    ) {
      SakhiRow(summary)
      ActiveVisitsCard(summary)
      ActiveBeneficiariesCard(summary)
    }
    BottomActionBar(onAllBeneficiaries = onAllBeneficiaries)
  }
}

/** Sakhi name + Data Upload pill on one row, "Updated" caption under the pill. */
@Composable
private fun SakhiRow(summary: DashboardSummary) {
  val updatedOn = summary.lastUploadedOn.format(
    DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()),
  )
  Row(
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
    modifier = Modifier.fillMaxWidth(),
  ) {
    // Name centers against the pill's height; caption centers under the pill.
    val isTabletRow = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
    val pillHeight = if (isTabletRow) Dimens.ButtonHeightTablet else Dimens.ButtonHeight
    Text(
      text = summary.sakhiName,
      style = MaterialTheme.typography.headlineSmall,
      color = NeutralG400,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .weight(1f)
        .padding(end = Dimens.SmallSpacing)
        .height(pillHeight)
        .wrapContentHeight(Alignment.CenterVertically),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
      DataUploadPill(pendingCount = summary.pendingUploadCount)
      Text(
        text = stringResource(R.string.home_updated_on, updatedOn),
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
private fun DataUploadPill(pendingCount: Int) {
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  val badgeSize = if (isTablet) 28.dp else 24.dp
  Button(
    onClick = { /* no-op: upload flow not built yet */ },
    contentPadding = PaddingValues(horizontal = Dimens.PillButtonPaddingH),
    modifier = Modifier.height(if (isTablet) Dimens.ButtonHeightTablet else Dimens.ButtonHeight),
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

@Composable
private fun ActiveVisitsCard(summary: DashboardSummary) {
  val visits = summary.activeVisits
  val month = visits.month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  SummaryCard(title = stringResource(R.string.home_active_visits), trailingLabel = month) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
    ) {
      StatTile(
        icon = painterResource(R.drawable.ic_calendar_blank),
        value = buildAnnotatedString {
          append(visits.openCount.toString())
          withStyle(SpanStyle(color = Primary)) { append(" (${visits.endingCount})") }
        },
        caption = buildAnnotatedString {
          append(stringResource(R.string.home_open))
          withStyle(SpanStyle(color = Primary)) {
            append(" " + stringResource(R.string.home_ending))
          }
        },
        modifier = Modifier.weight(1f),
      )
      StatTile(
        icon = painterResource(R.drawable.ic_referral),
        value = buildAnnotatedString { append(visits.pendingReferralCount.toString()) },
        caption = buildAnnotatedString { append(stringResource(R.string.home_pending_referral)) },
        modifier = Modifier.weight(1f),
      )
    }
    // Tablet: centered content-width pill, taller with wide inner padding;
    // mobile: full card width (per the respective designs).
    PrimaryButton(
      text = stringResource(R.string.home_see_visit_tracker),
      onClick = { /* no-op: visit tracker not built yet */ },
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

@Composable
private fun ActiveBeneficiariesCard(summary: DashboardSummary) {
  val b = summary.activeBeneficiaries
  val isTablet = LocalConfiguration.current.screenWidthDp >= Dimens.TabletMinWidthDp
  SummaryCard(title = stringResource(R.string.home_active_beneficiaries)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
      modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
    ) {
      StatTile(
        icon = painterResource(R.drawable.ic_woman),
        value = totalWithRisk(b.mothersTotal, b.mothersHighRisk, isTablet),
        caption = buildAnnotatedString { append(stringResource(R.string.home_mothers)) },
        modifier = Modifier.weight(1f),
      )
      StatTile(
        icon = painterResource(R.drawable.ic_baby),
        value = totalWithRisk(b.infantsTotal, b.infantsHighRisk, isTablet),
        caption = buildAnnotatedString { append(stringResource(R.string.home_infants)) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * Formats the total with the high-risk count in red —
 * tablet design: "21 (8)"; mobile design: "21 | 8".
 */
private fun totalWithRisk(total: Int, highRisk: Int, isTablet: Boolean) = buildAnnotatedString {
  append(total.toString())
  if (isTablet) {
    withStyle(SpanStyle(color = RiskHigh)) { append(" ($highRisk)") }
  } else {
    withStyle(SpanStyle(color = NeutralG200)) { append(" | ") }
    withStyle(SpanStyle(color = RiskHigh)) { append(highRisk.toString()) }
  }
}

/** Fixed bottom bar: All Beneficiaries navigates; Register New is a no-op for now. */
@Composable
private fun BottomActionBar(onAllBeneficiaries: () -> Unit) {
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
          onClick = { /* no-op: enrolment flow not built yet */ },
          trailingIcon = painterResource(R.drawable.ic_plus),
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}
