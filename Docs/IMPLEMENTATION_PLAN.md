# Implementation Plan

Sequencing rule, stated once and applied throughout: **if time runs out, it runs out in P2, never in P0.** An unfinished fraud-graph module or demo screen is fine to show up with. An unfinished idempotency implementation is not. Do not start a later stage's code before the current stage's tests (see `TRD.md`) pass.

## Stage 1 — Core (P0)

**Scope:** single Spring Boot service, single Postgres. No Kafka, no Redis, no UI.

1. Flyway migrations for `users`, `accounts`, `transactions`, `ledger_entries`, `transaction_approvals`, `audit_log` (`BACKEND_SCHEMA.md`).
2. Both invariant triggers (`trg_ledger_balance`, `trg_posting_invariant`).
3. `SYSTEM_CASH` seed account + `/accounts/{id}/fund` endpoint.
4. `TransferDomainService`: lock ordering, post-lock re-validation, ledger entry construction, balance update.
5. `TransferApplicationService`: idempotency (`SAVEPOINT` + business-final/transient split), orchestration.
6. `POST /transfers`, `GET /accounts/{id}`, `GET /accounts/{id}/balance`, `GET /accounts/{id}/ledger`, `GET /transactions/{id}`.
7. Concurrent-withdrawal test, idempotency race test, deterministic deadlock test (`CyclicBarrier`), property test (`Σcredits = Σdebits`).

**Exit criterion:** every REQ-0xx/REQ-02x in `TRD.md` has a passing test. Stage 1 alone, defended well, is the floor this project has to clear — do not proceed to Stage 2 with a shaky Stage 1.

## Stage 2 — Kafka & Outbox (P1)

1. `outbox_events`, `processed_events` migrations.
2. Outbox write (both `TransactionPosted` and `AccountBalanceChanged`, versioned) inside the Stage 1 posting transaction.
3. Relay: claim (SKIP LOCKED) → publish (no tx held) → mark, with lease-expiry recovery.
4. Settlement consumer + Notification consumer, both using the `processed_events` inbox pattern; notification's external-side-effect caveat explicitly handled (`TRD REQ-047`).
5. Dead-letter topic + DLQ processor.
6. Outbox atomicity test (crash-injected), consumer-crash-redelivery test.

**Exit criterion:** the "Kafka publishes, DB fails" and "DB commits, Kafka fails" questions both have a working, tested answer — not a description of one.

## Stage 3 — Redis & Reconciliation (P1)

1. Velocity check (Lua script, monetary sum, fail-closed) in the Payment API path, before the Transaction Coordinator.
2. Reconciliation scheduled job + incident/remediation workflow.
3. Observability: structured logs with correlation IDs (should already exist from Stage 1 per `TRD REQ-120`), outbox health metrics (`TRD REQ-121`), Kafka consumer lag, DLQ depth.
4. Reconciliation drift test, velocity fail-closed test.

**Exit criterion:** a manually-corrupted `cached_balance` is caught by the reconciliation job and produces an incident, not a silent fix.

## Stage 4 — Maker-Checker & API completeness (P1)

1. `transaction_approvals`, `AWAITING_APPROVAL`/`REJECTED` states.
2. `POST /transactions/{id}/approve`, `/reject`, `/reverse` — all with required `Idempotency-Key`.
3. Authorization rules (`TRD REQ-100`–`104`).
4. End-to-end idempotency test across all four mutating endpoints, not just `/transfers`.

**Exit criterion:** the state machine in `BACKEND_SCHEMA.md` is fully exercisable through the API, not just present in the schema.

## Stage 5 — Benchmarks (P1, run once Stages 1–4 are stable)

1. Hot-account vs. independent-account throughput/p99, with stated experimental conditions.
2. Idempotency mechanism comparison (naive vs. `ON CONFLICT`).
3. Outbox vs. direct-publish failure-injection comparison.
4. Redis velocity variant comparison.
5. DB connection pool saturation measured alongside lock contention.

**Exit criterion:** a chart, not a claim, for each of the above — see `spec §11` for the exact experiments.

## Stage 6 — Demo layer (P1/P2 boundary — build last)

The five screens in `UI_UX_DESIGN.md`. Build only after Stage 5's data exists to visualize — a demo screen for a benchmark that hasn't been run yet is decoration.

## P2 — do not start before Stage 1–5 are solid

- Fraud ring detection module (`spec §9`).
- Microservice extraction (Stage 4 of the original spec's roadmap — renamed here to avoid confusion with this plan's Stage 4).
- Debezium/CDC outbox relay.
- Multi-currency support.
- Account sharding / actor-style processing (only if Stage 5's benchmark actually shows the hot-account ceiling matters at a volume this project cares about).

## Dependency notes

- Stage 2 cannot start correctly until Stage 1's `SAVEPOINT`/transient-vs-final split is actually implemented and tested — the outbox insert sits inside that same transaction, so a shaky Stage 1 transaction boundary breaks Stage 2 silently.
- Stage 4's approval flow reuses Stage 1's posting logic (`AWAITING_APPROVAL → POSTED` runs the same lock/re-validate/post path as a normal transfer) — don't write a second, parallel posting implementation for the approved-transfer case.
- Stage 6 has no backend dependencies of its own; every number it shows comes from Stages 1–5.
