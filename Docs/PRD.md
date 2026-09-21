# PRD — Distributed Payment & Double-Entry Ledger Platform

Source of truth for design rationale: `payment-ledger-spec.md`. This PRD is the condensed, product-framed view of that spec — if the two ever disagree, the spec wins and this file is stale.

## 1. Problem Statement

Most portfolio payment projects are CRUD apps wearing a payments costume — a `transfer()` endpoint that mutates a balance column and calls it done. That doesn't survive five minutes of questioning from someone who has actually operated a payment system, because it never had to answer the questions that make payments hard:

- What happens if the same payment request is submitted twice (a client retry, a flaky network, a duplicate button-press)?
- What happens when two withdrawals hit the same account at the same instant?
- What happens if the database commits but the message that was supposed to tell the rest of the system never arrives?
- How do you *prove* the books balance, rather than asserting they do?

**The problem this project solves is narrow and specific:** build a payment/ledger core where those four questions have real, implemented, tested answers — correctness first, infrastructure only as far as it's earned. The target audience is a Java-track technical interview at the ₹10–12 LPA level, where the interviewer will pick one of the four questions above and go five levels deep rather than skim an architecture diagram.

## 2. Core Features (P0 / P1 — build these)

| # | Feature | Why it's core |
|---|---|---|
| 1 | Double-entry ledger (append-only `ledger_entries`, derived `cached_balance`) | The ledger, not the balance column, is the source of truth. Everything else depends on this being right. |
| 2 | Pessimistic row locking with deterministic lock order | Answers "two withdrawals, same account, same instant" with a mechanism, not a claim. |
| 3 | Idempotency via DB unique constraint + `SAVEPOINT`, scoped per-caller | Answers "submitted twice" correctly, including the business-final vs. transient failure distinction. |
| 4 | Transactional outbox + Kafka | Answers "DB commits, message never sent" — the single most common senior-sounding interview question in this space. |
| 5 | Two DB-level invariant triggers (ledger balance, posting completeness) | Makes double-entry correctness a database guarantee, not an assertion. |
| 6 | Maker-checker approval flow for large transfers/reversals | A named, real banking control — answers "how do you stop an insider moving money unilaterally." |
| 7 | Redis velocity checks (monetary sum, not event count) | A specific, previously-shipped bug (`ZCARD` counting events) fixed and documented — a stronger story than never having had a bug. |
| 8 | Reconciliation job with a defined remediation workflow | Answers "how do you catch a bug in your own balance logic before a customer does." |
| 9 | System funding account (`SYSTEM_CASH`) | Without it, there's no way to get money into the system without breaking the ledger invariant. |
| 10 | REST API + explicit error model | Makes this a working backend, not a design document. |

## 3. Future Features (P2 — explicitly deferred, do not build until P0/P1 are solid)

| # | Feature | Why it's deferred |
|---|---|---|
| 1 | Fraud ring detection (graph connected-components/cycle detection) | Real, but a supporting module — building it before the ledger is solid is decoration over an unfinished foundation. |
| 2 | Microservice split (Payment/Ledger/Account services) | Reintroduces distributed-transaction problems (loss of a single spanning DB transaction) that a modular monolith avoids for free. |
| 3 | Debezium/CDC outbox relay | The production upgrade over polling — worth naming, not worth building for a single-node portfolio system. |
| 4 | Multi-currency with FX conversion | v1 is explicitly INR-only; minor-unit exponent handling per currency is a real, separate feature. |
| 5 | Account sharding / actor-style per-account processing | Only justified once the hot-account throughput ceiling (§14 of the spec) is measured and shown to actually matter. |
| 6 | Full distributed tracing (OpenTelemetry spans across services) | Only meaningful once there's more than one service to trace across. |

## 4. Tech Stack & Constraints

- **Language/framework:** Java 21, Spring Boot. JPA for ordinary CRUD; native SQL/`JdbcTemplate` for lock-sensitive statements specifically (see `ARCHITECTURE.md`).
- **Datastore:** single PostgreSQL instance. No sharding, no read replicas, until a measured need exists.
- **Messaging:** Kafka, introduced only in Stage 2 (not before the core ledger/locking/idempotency logic is solid).
- **Cache/velocity:** Redis, sorted-set + Lua for atomic monetary-sum checks.
- **Testing:** JUnit 5, Mockito, Testcontainers (real Postgres/Redis/Kafka, not mocks, for integration tests), jqwik for property-based tests.
- **Hard constraints:**
  - Modular monolith first. Microservices are P2 and require an explicit, defensible reason to build, not a default.
  - No feature is added because it looks impressive in a diagram — every data structure and pattern must be traceable to a specific requirement (see spec §5's "what NOT to build" table).
  - v1 is INR-only.
  - Money is always an integer minor-unit (`BIGINT`), never a float.
  - The ledger is append-only at the permission level, not just by convention (`REVOKE UPDATE, DELETE` on `ledger_entries` for the app role).

## 5. Success Metrics & Validation

This project is validated by **evidence artifacts**, not a demo that "looks like it works":

- **Concurrency test** (`§11`): N concurrent withdrawals where `N × amount > balance` — exactly `floor(balance/amount)` succeed, zero lost updates, zero deadlocks left unresolved.
- **Idempotency test**: same key fired concurrently from two threads — one `transactions` row, identical response, identical `transactionId`, no duplicate ledger entries, no duplicate outbox event.
- **Deadlock test**: deterministically constructed via a `CyclicBarrier` (not timing luck) — reproduces before lock ordering, resolves after.
- **Outbox atomicity test**: crash injected between the ledger insert and the outbox insert — assert neither exists.
- **Reconciliation drift test**: manually corrupt `cached_balance`, assert the job detects it and does *not* silently auto-correct.
- **Property test**: thousands of generated transfer sequences, `Σcredits = Σdebits` holds after every one.
- **Benchmark artifact**: hot-account vs. independent-account throughput/p99 chart, with stated experimental conditions (hardware, dataset size, concurrency, warm-up, repetitions) — not a bare "requests/sec" number.
- **Acceptance criteria** (`spec §15`) passing as literal Given/When/Then tests, not just described in prose.

A feature is "done" when its corresponding acceptance criterion and test exist and pass — not when the happy-path endpoint returns `200`.
