# UI/UX Design

**This is not a consumer product.** The primary deliverable is the API and the backend correctness behind it (see `PRD.md`, `TRD.md`). The UI described here is explicitly the "demo layer" from `spec §17` — five thin, mostly read-only screens built *last*, after P0/P1 backend work is done, whose only job is to make already-existing backend guarantees visible in a 2–5 minute screen-share. If time runs out, it runs out here, never in the backend.

Do not build more than these five. Do not build them before the backend they visualize exists.

## Design principles

- **No screen invents new backend logic.** Every screen is a view over data/metrics/tests that already exist per the TRD. If a screen needs a new endpoint that does more than read state or trigger an already-specified test, that's scope creep — stop and check `ARCHITECTURE.md`'s "where does new code belong" section.
- **Numbers are real or explicitly labeled as such.** No screen shows a plausible-looking fake number. If a benchmark hasn't been run, the screen shows "not yet run," not a placeholder chart.
- **Failure states are first-class, not an afterthought.** This system's entire value proposition is correct behavior under failure — a demo screen that only shows the happy path undersells the actual work.

## Screen 1 — Failure Lab

**Purpose:** turn "my system handles failures" from a claim into something clicked and watched. Highest-leverage screen on this list.

**Layout:** a table, one row per scenario (from `APP_FLOW.md §7`), columns: Scenario name, `[Run]` button, Result (✓/✗/pending), Detail link.

**Interaction:** clicking `[Run]` triggers the corresponding backend failure-injection test against a live/local instance and streams the outcome back (a duplicate-payment race actually fires two concurrent requests; a deadlock scenario actually constructs one via the barrier-based test). Detail link expands to show what happened step by step (maps to the timeline data in Screen 4).

**States:** not run / running / passed / failed. A failed state here is not hidden — if a scenario legitimately fails, that's more informative than not showing it.

## Screen 2 — Ledger Integrity Monitor

**Purpose:** visualize the seven invariants from `spec §1.1` as live, checked facts rather than asserted ones.

**Layout:** a status panel with one row per invariant (debit=credit, posted-requires-entries, currency consistency, cached-balance drift = 0, duplicate-transaction count = 0, amount bounds, distinct accounts), each showing ✓/count/last-checked. Below it, a transaction drill-down: pick a `transaction_id`, show its ledger entries side by side (debit amount, credit amount, visually balancing to zero).

**Data source:** direct queries against `ledger_entries`/`transactions`, no new computation.

## Screen 3 — Hot Account Contention Observatory

**Purpose:** present the benchmark from `spec §14` as a picture of an accepted trade-off, not a marketing number.

**Layout:** two line charts side by side — throughput/p99 latency for (a) all traffic against one hot account, (b) traffic spread across independent accounts. A caption states the experimental conditions (hardware, concurrency levels, dataset size, warm-up excluded) per `TRD REQ-122` — a chart without this caption is not acceptable per that requirement.

**Framing to preserve in the UI copy:** (1) is labeled "structurally guaranteed by the locking design," (2) and (3) from the spec are labeled "hypothesis, measured" — the UI should not flatten this distinction into a single confident-looking chart.

## Screen 4 — Distributed Transaction Timeline

**Purpose:** visualize the outbox/Kafka pipeline per `APP_FLOW.md §1` as a per-transaction event sequence, using data `TRD REQ-120` already requires the system to log.

**Layout:** given a `transaction_id`, a vertical timeline: lock acquired → ledger written → outbox committed → Kafka published → settlement consumed → notification sent, each with a timestamp and the relevant IDs (`correlation_id`, `event_id`, partition/offset if available).

**Data source:** structured logs + `outbox_events`/`processed_events` tables, correlated by `transaction_id`. No new instrumentation beyond what the TRD already requires.

## Screen 5 — Reconciliation Incident Center

**Purpose:** visualize `APP_FLOW.md §6`'s remediation workflow, including the "never silently auto-correct" rule as a visible constraint, not just a backend policy nobody sees.

**Layout:** a table of accounts (cached balance vs. ledger-derived balance vs. delta vs. status), sorted by non-zero delta first. Clicking a drifted row opens an incident view following the six-step workflow (detect → flag → alert → investigate → correct → record), with the "correct" step requiring an explicit, logged action — the UI should make it structurally awkward to silently dismiss a drift incident, mirroring the backend rule.

## Explicitly not built

**Fraud Ring Explorer** — the graph-analysis module (`spec §9`) is P2. A demo screen for a P2 backend feature that doesn't exist yet, built ahead of finishing the five above, is the same sequencing mistake the spec argues against throughout. Build it after, if there's time, not instead.

Any customer-facing transfer UI (a "send money" form, a wallet view) — out of scope entirely. This project is an infrastructure/correctness demonstration, not a fintech product; a polished consumer UI would spend effort on the part of the project that isn't the point.
