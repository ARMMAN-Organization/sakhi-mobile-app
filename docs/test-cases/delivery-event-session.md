# Test cases — Delivery Event Session (CR-042)

Spec for the session: `Delivery form` -> auto-created child(ren) -> `PP1` -> `NN1`/`NN2` (or
neither). Covers the new session orchestration, the `DELIVERY_VISIT` submission's
`childBeneficiaryIds` contract, and the same-session NN visit selection. Does not re-cover
`VisitScheduleCoordinator.onDeliveryRecorded` or `NnScheduleGenerator`'s own scenario math —
those already have full unit coverage; this session only calls them.

## 1. Delivery submission -> schedule wiring

1. Submitting `DELIVERY_VISIT` with a delivery date calls `VisitScheduleCoordinator.onDeliveryRecorded`
   exactly once, and only after the submission itself succeeds (offline-queued counts as
   succeeded for this purpose — matches existing offline-first pattern).
2. All open ANC visits for the mother are lapsed as a visible, immediate effect of submission
   (already covered at the coordinator level; this case is the session-level regression check
   that the call actually happens).
3. The PP series is generated and visible on the mother's schedule immediately after submission,
   before any network round trip completes.
4. A double-submit (retry after a dropped connection, same `localSubmissionUuid`) does not
   generate a second PP/NN series — coordinator-level idempotency already covers this; verify the
   session doesn't call `onDeliveryRecorded` a second time on retry either.

## 2. `childBeneficiaryIds` handling (session-level, on top of the DTO-level tests)

5. `childBeneficiaryIds == null` (no live birth) -> session skips the child-registration step
   entirely and proceeds straight to PP1. No "0 children" loop, no empty-state child screen.
6. `childBeneficiaryIds` has one id -> exactly one child-registration screen opens, prefilled from
   the delivery form's own echoed answers for that child (`child1_*` fields).
7. `childBeneficiaryIds` has two or three ids (twins/triplets) -> one child-registration screen per
   id, in order, each prefilled from its matching `child1_/child2_/child3_*` block. The Sakhi
   completes each in turn; the session does not proceed to PP1 until all are done or explicitly
   deferred (see case 12).
8. Each auto-created child, once its registration form is submitted, triggers
   `ChildEnrolmentScheduleTrigger` and gets its own INC series — verify this fires per child, not
   once for the whole delivery.

## 3. Same-session NN visit selection

9. Delivery form filled same day as delivery (Scenario A) -> NN1 opens in-session; NN2 does not
   (it belongs on the tracker for Day 15).
10. Delivery form filled Day 15–27 (Scenario B) -> NN1 is never generated and never appears in the
    session; NN2 opens in-session instead.
11. Delivery form filled exactly Day 28 (Scenario C) -> NN2 opens in-session.
12. Delivery form filled after Day 28 -> no NN step in the session at all; the session ends after
    PP1 (or after child registration if there's no live birth... n/a, contradiction — if there's no
    live birth there's no NN step regardless of timing; this case is specifically the "child exists
    but form filed late" path).
13. If a delivery produces multiple children, the same-session NN step applies **per child** —
    twins delivered same-day each get their own NN1 opened in sequence, not one shared form.

## 4. Session interruption and resume

14. App is killed after the delivery form submits successfully but before any child registration
    starts -> relaunching from the mother's profile resumes at the child-registration step; the
    delivery form is not re-shown or re-submitted.
15. App is killed mid-way through a twin/triplet child-registration sequence (child1 done, child2
    in progress) -> resume continues at child2, does not restart child1.
16. App is killed after all children are registered but before PP1 is completed -> resume opens
    directly at PP1.
17. The Sakhi backs out of the session (not a crash, deliberate exit) partway through -> same
    resume behavior as a crash; re-entering from the profile's Delivery button (or a "resume
    delivery" affordance, TBD in the plan) picks up where she left off, does not offer to start a
    second, parallel delivery session for the same mother.
18. A fully completed session (all steps done) leaves no resumable state — re-tapping Delivery
    from the profile after completion does not re-open a finished session (design question: what
    does it do instead — nothing, since delivery can only happen once? Flag for product decision
    if unclear).

## 5. Offline behavior

19. The entire session (delivery form through PP1/NN) completes fully offline, with every
    submission queued for sync — matches the existing offline-first pattern for every other form.
20. If connectivity returns mid-session, in-flight steps are not disrupted; sync happens in the
    background per the existing sync executor pattern.

## 6. Failure and edge cases

21. Delivery form submission fails validation (e.g. delivery date before LMP) -> session does not
    advance past the delivery step; existing form validation UX applies unchanged.
22. `childBeneficiaryIds` present but the child-registration draft fails to save locally (Room
    failure) -> per the existing "failure never blocks the enrolment" principle used elsewhere in
    this codebase, the delivery submission itself is not rolled back; the specific child is
    flagged for manual follow-up rather than silently lost. (Design question for the plan: what
    does "flagged" mean concretely — a badge on the mother's profile? Needs a decision before
    this case can be implemented, not just tested.)
23. Mother's phase-advance to `PP` fails server-side (per backend's documented best-effort
    behavior) -> session is unaffected; this is a background reconciliation concern per backend's
    own handoff note, not a client-visible failure.

## 7. Out of scope for this CR (do not write test cases against these yet)

- `currentPhase` displayed anywhere in the UI — not part of this CR.
- GoRules DELIVERY pack activation — Kotlin fallback path is what's under test here.
- Risk grading / referral triggers on delivery-specific danger signs — RISK-category GoRules packs
  are unseeded repo-wide per backend's own note; separate work.
