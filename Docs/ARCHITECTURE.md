# Architecture

This document is the contract for anyone — human or AI agent — making changes to this codebase. It exists to answer the questions that actually cause damage when unanswered: what's allowed to touch what, what can never break, and when to stop and ask instead of guessing.

## What's in the system

One modular monolith (Stage 1–5; see `IMPLEMENTATION_PLAN.md`) with these logical modules inside it, plus Postgres, Kafka, and Redis as external dependencies:

```
payment-api        → Controllers, request validation, auth boundary
transfer            → TransferApplicationService, TransferDomainService (the core: locking, idempotency, posting)
ledger              → Ledger read models, invariant-adjacent queries
account              → Account CRUD, funding, status management
outbox               → Outbox write path (inside transfer's transaction) + relay process
consumers            → Settlement consumer, Notification consumer (each with its own processed_events usage)
velocity             → Redis-backed velocity check
reconciliation       → Scheduled job + incident workflow
approval             → Maker-checker: transaction_approvals, approve/reject endpoints
audit                → audit_log writes (called from every module above, owns nothing else)
```

Microservice extraction is P2 and out of scope until explicitly promoted — see `PRD.md §3`.

## Who's responsible for what

| Layer | Responsibility | Must NOT do |
|---|---|---|
| Controller | HTTP concerns: parse request, validate shape, map to/from DTOs, set status codes | Contain business logic, raw SQL, or lock acquisition |
| Application Service | Orchestration: idempotency check, calls domain service, writes outbox event, handles the transient/business-final split | Contain the actual locking or invariant logic — that's the domain service's job |
| Domain Service | The real business rules: lock ordering, balance sufficiency, entry construction, invariant assertions | Know about HTTP, Kafka topics, or JSON |
| Repository | Persistence, including the lock-sensitive native SQL | Contain business logic or validation beyond what the SQL itself enforces |
| Consumers | Deduplicate via `processed_events`, apply one business side effect, commit atomically with the dedupe record | Assume delivery order across accounts without checking `account_version` (`TRD REQ-044`) |

Concretely: `TransferController → TransferApplicationService → TransferDomainService → {AccountRepository, TransactionRepository, LedgerRepository, OutboxRepository}`. A controller method containing SQL, or a domain service importing `HttpServletRequest`, is a layering violation — flag it, don't work around it.

## Why it's built this way

- **Modular monolith before microservices.** A microservice split reintroduces distributed-transaction problems (no single Postgres transaction spanning Payment + Ledger) that this architecture avoids for free. Splitting is a deliberate, justified escalation (P2), not a default.
- **Ledger is source of truth, `cached_balance` is a protected projection.** Every other decision (rollback, reconciliation, reversal) follows from this one. See `BACKEND_SCHEMA.md`.
- **Pessimistic locking, not optimistic.** Chosen specifically because hot accounts (settlement accounts, house accounts) see genuine contention where optimistic retries would thrash. The cost (a throughput ceiling on any single account) is accepted and measured, not hidden — see `spec §14`.
- **Transactional outbox, not direct publish.** The only pattern that makes "DB commits, message never sent" and "message sent, DB never commits" both structurally impossible, by turning a distributed-atomicity problem into a local one.
- **SAVEPOINT-based idempotency with a transient/business-final split**, not a two-phase commit workflow. Simpler, and correct for a fast, in-process transfer; the one place a real multi-commit workflow *is* needed (maker-checker) is modeled as its own explicit state (`AWAITING_APPROVAL`), not smuggled into the fast path's status field.

## What's allowed to touch what

- **Only the `transfer` module writes to `ledger_entries`, `transactions`, or updates `accounts.cached_balance`.** Any other module needing this data reads it — it does not write it directly. This is what makes the invariant triggers meaningful: if every module could write the ledger, "the DB guarantees the invariant" would be true only for the one code path someone remembered to route through the domain service.
- **Only the `approval` module writes to `transaction_approvals`.**
- **Only the `outbox` relay changes `outbox_events.status`.** Application code that posts a transfer inserts a `PENDING` row and never touches it again.
- **Only the `audit` module writes to `audit_log`**, called from other modules but never queried-and-mutated by them.
- **The application's DB role has no `UPDATE`/`DELETE` grant on `ledger_entries` at all** (`BACKEND_SCHEMA.md`) — this is enforced at the database permission level, not just by module discipline, because module discipline is a convention and a `REVOKE` is a fact.
- **No module reaches into another module's repository directly.** `consumers` doesn't query `AccountRepository`; it goes through whatever read API `account`/`ledger` expose.

## How data actually moves

See `APP_FLOW.md` for the full sequence diagrams. In one line: **request → pre-check (fast reject) → DB transaction (lock, post-lock re-validate, ledger write, outbox write, all atomic) → commit → relay (separate, short transactions, no lock held across the Kafka call) → consumers (dedupe, then side effect, atomic with each other).** Nothing outside the DB transaction is ever treated as having "happened" until that transaction commits — this is the answer to why the outbox pattern exists at all.

## What can never break

These are the non-negotiables. A change that would violate any of these needs a human review gate (see below), not a judgment call by whoever's writing the code that day:

1. `SUM(DEBIT) = SUM(CREDIT)` per transaction — enforced by `trg_ledger_balance`. Never disabled, never bypassed via a raw insert.
2. A `POSTED` transaction always has ≥2 ledger entries — enforced by `trg_posting_invariant`.
3. `ledger_entries` is never updated or deleted, by permission, not convention.
4. Two concurrent operations on the same account never both succeed against a balance that only supports one — the lock-ordering contract (`TRD REQ-020`).
5. The same idempotency key never produces two different business-final outcomes.
6. A transaction is never marked `POSTED` before its outbox event is written in the same transaction (breaks the atomicity the whole outbox pattern exists for).
7. `ux_one_reversal_per_transaction` is never worked around — one reversal per original transaction, structurally.

## Where new code belongs

- A new business rule about *whether* a transfer is allowed → `TransferDomainService`.
- A new orchestration step (call an additional service, publish an additional event) → the relevant Application Service.
- A new read-only report or projection → its own module reading existing tables, never a new writer to `ledger_entries`/`accounts`.
- A new consumer → its own package under `consumers`, with its own `processed_events` usage; never share dedupe state with an existing consumer.
- A new endpoint that mutates state → requires `Idempotency-Key` handling from day one (`TRD REQ-028`), not retrofitted later.
- Anything that doesn't fit one of the above → stop and ask (below) before creating a new top-level module.

## When does the agent stop and ask

Do not proceed autonomously — surface the decision to a human — for any of the following:

- **Any schema migration touching `ledger_entries`, `transactions.status`, `outbox_events.status`, or either invariant trigger.** These are the load-bearing walls; get them wrong and every downstream guarantee in this document is void.
- **Any change to lock acquisition order, isolation level, or `lock_timeout`.** This is the deadlock-prevention mechanism; a "small optimization" here is exactly how deadlocks come back.
- **Any change to the idempotency key scope, the canonical hash fields, or the business-final/transient failure classification.** Getting this wrong reintroduces the exact contradiction this design spent multiple rounds resolving (see `spec §1.3`'s history).
- **Disabling or weakening a `CHECK` constraint or invariant trigger "temporarily" for a migration or a test.** If a migration genuinely requires this, it needs a human-reviewed plan for re-enabling and re-verifying, not a follow-up TODO.
- **Introducing a second write path to any table listed under "what's allowed to touch what."**
- **Adding a new external dependency** (a new datastore, a new message broker, a new library that changes the persistence or concurrency model) not already named in `PRD.md`'s tech stack.
- **Any request that would make the system claim a stronger guarantee than it actually provides** (e.g., "exactly-once" instead of "effectively-once," or a benchmark number without the methodology in `TRD REQ-122`) — this document's credibility depends on not overclaiming, and that applies to whoever's writing the code, human or agent.
- **Anything that would touch more than one module's "owns" boundary in a single change**, per the "what's allowed to touch what" section — that's a sign the change is bigger than it looks, not a sign to just push through it.

When in doubt, the default is to ask, implement the smallest change that doesn't touch a non-negotiable, and flag the rest — not to make the most plausible-looking guess and move on.
