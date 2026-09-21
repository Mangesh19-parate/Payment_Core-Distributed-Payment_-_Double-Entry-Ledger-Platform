# AGENT.md — Entry point for the AI agent working on this repo

Read this file first, every session. It tells you what this project is, where the actual rules live, and — most importantly — when to stop and ask instead of guessing.

## What this project is

A Java/Spring Boot distributed payment and double-entry ledger platform, built as a correctness-first portfolio project (see `PRD.md §1` for the actual problem statement). The point of this project is **provable correctness under concurrency and failure**, not feature count. Every design decision in this repo has already been argued through multiple rounds of review — don't relitigate a decision without reading why it was made first.

## Reading order for a new session

1. `MEMORY.md` — decisions already made and why, so you don't re-propose something already rejected.
2. `TRACKER.md` — current state: what's done, what's in progress, what's next.
3. `ARCHITECTURE.md` — what's allowed to touch what, and when to stop and ask (read this even if you think you already know the codebase — it's the contract, not a suggestion).
4. Whichever of `TRD.md` / `BACKEND_SCHEMA.md` / `APP_FLOW.md` / `IMPLEMENTATION_PLAN.md` is relevant to the specific task.

Do not start writing code before reading `ARCHITECTURE.md`'s "when does the agent stop and ask" section. That section exists specifically because this system has non-negotiable invariants (also listed there) that a plausible-looking small change can violate silently.

## How to work a task

1. Find or confirm the task's `REQ-NNN` ID in `TRD.md`. If the task doesn't map to an existing requirement, that's itself a signal — check `IMPLEMENTATION_PLAN.md`'s stage sequencing before assuming it's fine to add scope.
2. Check `TRACKER.md` for the requirement's current status and any prior notes.
3. Check `ARCHITECTURE.md`'s ownership table — confirm which module owns the code you're about to touch, and that you're not writing a second path to a table another module owns.
4. If the task falls under any "stop and ask" trigger in `ARCHITECTURE.md`, stop and ask before writing code, not after.
5. Implement the smallest change that satisfies the requirement, following `PROJECT_STRUCTURE.md`'s layering (Controller → Application Service → Domain Service → Repository — no shortcuts).
6. Write or extend the test that proves the requirement, per `TRD.md`'s stated acceptance form and `spec §11`'s testing philosophy: a claim without a test is not done.
7. Update `TRACKER.md` with the result. Append a decision entry to `MEMORY.md` if you made a judgment call worth remembering (see `MEMORY.md`'s format).

## Non-negotiables (the short version — full list in `ARCHITECTURE.md`)

- Never let `ledger_entries` be written outside the `transfer` module.
- Never disable, weaken, or work around an invariant trigger or `CHECK` constraint to make a migration or test pass — fix the underlying issue or stop and ask.
- Never treat a transient failure (lock timeout) and a business-final failure (insufficient funds) the same way in the idempotency path — this exact conflation was a real, caught bug (see `MEMORY.md`).
- Never claim a stronger guarantee than the system provides ("exactly-once" instead of "effectively-once," a benchmark without stated methodology).
- Money is always an integer minor-unit value. Never introduce a float for an amount, anywhere, for any reason.

## What "done" means for any task in this repo

Not "the endpoint returns 200." A task is done when:
- The corresponding `TRD.md` requirement is satisfied.
- A test exists that would fail if the requirement were violated (not a test that merely exercises the happy path).
- `ARCHITECTURE.md`'s ownership and non-negotiable rules are still true after the change.
- `TRACKER.md` reflects the new state.

## When you're uncertain

Prefer asking over guessing, specifically because this codebase's whole value proposition is that its guarantees are actually true, not plausible. A wrong guess that "looks like it works" is worse here than in most codebases, because the entire point of this project is that things that look like they work (a naive idempotency check, a direct Kafka publish, an unenforced invariant) are exactly what's being deliberately avoided throughout. If a change would touch a non-negotiable, or doesn't clearly map to an existing `TRD.md` requirement, say so and ask rather than proceeding on the most reasonable-sounding interpretation.
