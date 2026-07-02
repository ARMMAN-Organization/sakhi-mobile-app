# sakhi-mobile-app — Standard Development Workflow

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

## Step 5 — Final summary

After development is complete, provide:

- List of files changed
- Summary of the implementation
- Any assumptions made
- Commands required to run or test the changes (`./gradlew detekt :app:testDebugUnitTest`)
- Follow-up improvements or known limitations
