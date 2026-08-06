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

## UI work — design system lives elsewhere

If (and only if) this task builds or changes a screen, read
**`.claude/design-system.md`** before Step 2. It holds the design-fidelity checklist,
the full colour/typography/spacing/icon token tables, the tablet-first form-factor
rules, and the UI verification loop. Non-UI tasks must not read it.

## Step 5 — Final summary

After development is complete, provide:

- List of files changed
- Summary of the implementation
- Any assumptions made
- Commands required to run or test the changes (`./gradlew :app:testDebugUnitTest` —
  NOTE: detekt is NOT yet configured in this repo despite the standards mentioning it;
  adding the detekt plugin is an open tech-debt task)
- Follow-up improvements or known limitations
