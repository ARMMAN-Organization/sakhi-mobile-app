# sakhi-mobile-app — Standard Development Workflow

> **SESSION BOOTSTRAP:** first read the workspace root `../CLAUDE.md` and
> `../docs/claude-context.md` for current project state. Design source =
> `../docs/project-docs/Arogya Sakhi - Revamp/` (per-section boards; **build to the
> CURRENT purple frames**, orange = superseded). Don't ask the user to re-explain
> context that lives there.

This file complements `.claude/CLAUDE.md` (architecture & code standards). The workflow below
is MANDATORY for every develop/modify/refactor request. Never skip a step. Never start coding
before Steps 2 AND 3 are explicitly approved by the user.

## Step 1 — Understand the requirement

- Read the full request carefully; be sure the objective is fully understood.
- If anything is unclear or ambiguous, ask simple, direct clarification questions.
- Do not make assumptions.

## Step 2 — Implementation plan (STOP: wait for approval)

Once the requirement is clear, provide a detailed plan covering:

- Objective of the change
- Overall approach
- Files to be created, modified, or removed
- Components, APIs, database, or services affected
- Edge cases and risks
- Expected output after implementation

Wait for explicit confirmation before proceeding.

## Step 3 — Test cases (STOP: wait for approval)

After the plan is approved, write all functional test cases before any implementation:

- Positive, negative, and edge-case scenarios
- Validation and error-handling tests

Wait for explicit approval of the test cases before coding.

## Step 4 — Development

Only after test cases are approved:

- Implement the feature following the existing project architecture and coding standards
  (see `.claude/CLAUDE.md` code-review checklist).
- Keep code modular, reusable, and maintainable.
- Avoid any changes outside the agreed scope unless explicitly instructed.

## Design-fidelity checklist (MANDATORY for every screen built from Figma)

Before delivering any UI, compare the implementation against the design export
element-by-element and confirm ALL of the following. "Roughly similar" is not done.

1. **Container hierarchy** — reproduce surfaces exactly: lavender background shows only
   where the design shows it; content below headers sits on the white rounded-top sheet
   (`Dimens.SheetRadius`); cards get their designed elevation/border.
2. **Row grouping & alignment** — elements the design puts on one row stay on one row,
   with the same alignment (e.g. name + action pill centered on a shared axis). Never let
   layout convenience change the design's grouping.
3. **Typography per element** — correct family AND size AND weight. Serif titles use
   `SerifTitle` (Libre Baskerville); KPI numbers use `KpiNumber`; measure sizes from the
   design export instead of guessing from existing tokens. New sizes become named tokens.
4. **Spacing & proportions** — paddings, tile heights, corner radii measured from the
   design (base-4 scale), added to `Dimens` — never inline.
5. **Self-diff before delivery** — render the design page crop at high resolution, walk
   the screen top-to-bottom listing every visual difference, and fix them BEFORE handing
   over. Known intentional deviations (e.g. placeholder icons) must be listed explicitly
   in the final summary.

## Design tokens (MANDATORY — the single source of truth for pixels & colors)

Every color, size, spacing and font in this app MUST come from these tokens
(`ui/theme/Color.kt`, `Dimens.kt`, `Type.kt`, `Shadows.kt`). Inline hex values or
`.dp`/`.sp` literals in screens/components are forbidden. If a design needs a value
that has no token, CREATE the token first, then use it — and keep this table updated.

### Colors (`Color.kt`)

| Token | Hex | Use |
|---|---|---|
| `Primary` | #7C4DFF | brand lavender: buttons, active tabs, links |
| `BackgroundLavender` | #F1EDF9 | screen background behind headers |
| `PrimarySurface` | #EDE7FB | selected chips, highlight badges ("N days remaining") |
| `RiskHigh` / error | #D32F2F | high risk, error states |
| `RiskModerate` | #F57C00 | moderate risk |
| `RiskMild` / warning | #FBC02D | mild risk (dark text on it) |
| `RiskLow` / `StatusSuccess` | #2E7D32 | low risk, success |
| `StatusSuccessSurface` | #F1F8F2 | success banner background |
| Information | #1D79E5 | info banners (add token when first used) |
| `NeutralG10` | #F7F9FC | avatar/light surfaces |
| `NeutralG50` | #E6E6E6 | hairline borders, dividers |
| `NeutralG75` | #B3B3B3 | input borders |
| `NeutralG100` | #999999 | placeholders |
| `NeutralG200` | #656565 | secondary text, captions |
| `NeutralG400` | #333333 | primary text |
| `White` | #FFFFFF | sheets, cards |
| `ShadowTint` | #26000000 | ONLY via `Modifier.softShadow()` |

### Typography (`Type.kt`) — Cabin (UI) + Libre Baskerville (select titles)

| Token | Font/Weight/Size | Use |
|---|---|---|
| `SerifTitleLarge` | Baskerville Bold 24 | page titles ("My Beneficiaries") |
| `SerifTitle` | Baskerville Bold 18 | card/section titles |
| `KpiNumber` | Cabin Bold 40 | dashboard stat numbers |
| `headlineLarge` | Cabin Bold 32 | H2 KPI |
| `headlineMedium` | Cabin Bold 28 | screen titles (Login) |
| `headlineSmall` | Cabin Bold 24 | prominent names |
| `titleLarge` | Cabin Bold 18 | H4, card names |
| `titleMedium` | Cabin SemiBold 16 | tabs, chips, Body 1 |
| `bodyLarge` | Cabin Regular 16 | body copy |
| `bodyMedium` | Cabin Regular 14 | meta text |
| `labelLarge` | Cabin SemiBold 14 | badges, field labels |
| `labelMedium` | Cabin Medium 14 | small buttons |
| `labelSmall` | Cabin Regular 12 | captions, dates |

### Spacing & sizes (`Dimens.kt`) — base-4 scale: 4 / 8 / 12 / 16 / 20 / 24

`ScreenPadding` 24 · `ItemSpacing` 16 · `ChipSpacing` 12 · `SmallSpacing` 8 ·
`ButtonHeight` 48 · `SmallButtonHeight` 44 · `SearchBarHeight` 52 · `ChipHeight` 40 ·
`AvatarSize` 44 · `SheetRadius` 40 (top of content sheets) · `CardRadius` 16 ·
`TileRadius` 12 · `TilePadding` 20 · `CardAccentHeight` 6 · `TabIndicatorHeight` 4 ·
`PillButtonPaddingH` 16 (compact pill content padding) ·
`TabletMinWidthDp` 600 (tablet layout breakpoint — the ONLY device check allowed)

### Icons

Design icons live as VectorDrawables in `res/drawable/ic_*.xml` (38, converted from
Figma SVG exports; sources in `../design/icons/`). ALWAYS prefer `ic_*` drawables
via `painterResource` over Material `Icons.*` — Material icons are placeholders
only when no design icon exists. Key mappings: calendar `ic_calendar_blank`/
`ic_calendar_dots` · referral `ic_referral` · mother `ic_woman` · infant `ic_baby` ·
visit/ANC `ic_anc` · location `ic_location` · phone `ic_phone_call` · arrows
`ic_arrow_left`/`ic_arrow_right` · plus `ic_plus` · mic `ic_microphone` · search
`ic_magnifying_glass` · filter `ic_funnel_simple` · close `ic_x` · check
`ic_check`/`ic_check_circle` · warning `ic_warning`/`ic_warning_circle`.

### Effects

Box shadows: ONLY `Modifier.softShadow(cornerRadius)` from `Shadows.kt`
(20dp blur, 4dp y-offset, `ShadowTint`). Never `Modifier.shadow`/Material elevation.

## Form factors — TABLET FIRST, ZERO COMPROMISE ON EITHER

The Sakhi app targets tablets (design frames 800×1280) as the priority device AND
phones (376×884). Priority sets the build/verify ORDER — it is NOT a quality
tradeoff: both form factors must match their respective Figma designs pixel-close.
A screen is not "done" until BOTH pass. For EVERY screen:

1. Build and verify the **tablet layout first**, then the mobile layout — both to
   full design fidelity.
2. Adaptive layout, never a stretched phone UI on tablet (e.g. modal 640dp wide on
   tablet vs 335dp on mobile per the style guide).
3. The Figma PDF has separate mobile and tablet sections ("Tablet Screens: 20
   Screens") — extract and measure BOTH before planning a screen.
4. The user sends TWO QA screenshots per screen (tablet + mobile); collect deltas
   from both into one batched fix round.
5. Use `WindowSizeClass` (or width breakpoints as tokens in `Dimens`) to switch
   layouts — no hardcoded device checks.

## UI verification loop (MANDATORY for every screen)

Root causes of past rework: eyeballing low-res exports, mapping to nearest existing token
instead of measuring, and trusting platform defaults to match Figma. Therefore:

1. **Measure, never eyeball.** Before coding, render the design page at high resolution
   (150dpi+) and derive px values for font sizes, paddings, radii, icon sizes, element
   heights. Nearest-existing-token mapping is forbidden; if a measured value has no
   token, create one.
2. **Ask for Dev Mode specs first.** If the user can provide Figma Dev Mode values
   (typography, spacing, effects/shadows) for the screen, request them at Step 1 —
   exact specs eliminate measurement error entirely.
3. **Distrust platform defaults.** Known traps: Material elevation ≠ Figma box-shadow
   (use `Modifier.softShadow` from `ui/theme/Shadows.kt`); default Button height ≠
   design pill height (set `Dimens.ButtonHeight`); system font ≠ brand fonts (use
   `CabinFamily`/`SerifTitle` tokens). When a design effect depends on rendering, state
   how it was implemented and why in the summary.
4. **One visual QA round is part of the workflow.** The agent cannot screenshot the
   running app, so after delivery, ask the user for ONE device screenshot per screen and
   fix all reported deltas in a single batch — not one message per fix. Collect every
   difference before editing.
5. **Alignment intent, not just placement.** For every row/group, replicate the design's
   alignment axis (e.g. label centered to a button's height, caption centered under its
   pill). When composing, state the intended axis in a code comment so review can catch
   drift.

## Step 5 — Final summary

After development is complete, provide:

- List of files changed
- Summary of the implementation
- Any assumptions made
- Commands required to run or test the changes (`./gradlew :app:testDebugUnitTest` —
  NOTE: detekt is NOT yet configured in this repo despite the standards mentioning it;
  adding the detekt plugin is an open tech-debt task)
- Follow-up improvements or known limitations
