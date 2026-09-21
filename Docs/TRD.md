# TRD — Technical Requirements Document

Each requirement has an ID (`REQ-NNN`) so `TRACKER.md` and commit messages can reference it directly. Full rationale for each lives in `payment-ledger-spec.md`; this document states the requirement itself, testably, without the narrative.

## 1. Data & Ledger

| ID | Requirement |
|---|---|
| REQ-001 | `ledger_entries` is append-only: the application DB role has `SELECT, INSERT` only — no `UPDATE`/`DELETE` grant. |
| REQ-002 | For every `transaction_id`, `SUM(DEBIT entries) = SUM(CREDIT entries)`, enforced by a `DEFERRABLE INITIALLY DEFERRED` constraint trigger (`trg_ledger_balance`) on `ledger_entries`, checked at `COMMIT`. |
| REQ-003 | A transaction transitioning to `POSTED` must have at least 2 `ledger_entries`, enforced by a constraint trigger (`trg_posting_invariant`) on `transactions(status)`. |
| REQ-004 | `accounts.cached_balance` is a transactionally-maintained projection, never the value checked in a dispute — the ledger is authoritative. |
| REQ-005 | All entries for one transaction share one currency, matching `transactions.currency` — enforced at application level before the DB transaction opens. |
| REQ-006 | `transactions.amount` equals both the debit-side and credit-side ledger sum at posting time — asserted in application code immediately before `COMMIT`. |
| REQ-007 | `source_account_id <> destination_account_id`, enforced by a `CHECK` constraint. |
| REQ-008 | Amount bounds: `0 < amount <= 1,000,000,000` paise (₹1 crore), enforced by a `CHECK` constraint. Configurable per-transfer/per-day business limits sit below this as application config, not schema. |
| REQ-009 | v1 supports `currency = 'INR'` only. Multi-currency (variable minor-unit exponents) is explicitly out of scope. |
| REQ-010 | A reserved `SYSTEM_CASH` account (`is_system_account = true`) is the only legal counterparty for funding an account; it is exempt from the sufficiency check and permitted a negative `cached_balance`. |

## 2. Concurrency & Idempotency

| ID | Requirement |
|---|---|
| REQ-020 | Multi-account operations acquire account row locks (`SELECT ... FOR UPDATE`) in ascending `id` order, unconditionally. |
| REQ-021 | `lock_timeout` is set (target: 3s) so a stuck transaction fails fast rather than queuing indefinitely. |
| REQ-022 | Account `status`/`currency`/`cached_balance` are re-validated *after* the lock is acquired, not only before the transaction opens (post-lock check is authoritative; pre-lock check is fast-fail only). |
| REQ-023 | Idempotency is enforced by a DB unique constraint on `(principal_id, idempotency_key)`, not application-layer deduplication. |
| REQ-024 | `request_hash` is computed over a canonical, fixed-order field concatenation (not raw JSON) — SHA-256 of `sourceAccountId|destinationAccountId|amount|currency`. |
| REQ-025 | A business-final failure (`INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `ACCOUNT_SUSPENDED`, `SAME_ACCOUNT_TRANSFER`) is durably recorded as `FAILED` against the idempotency key (`ROLLBACK TO SAVEPOINT`, then commit the outer transaction). |
| REQ-026 | A transient failure (`ACCOUNT_LOCK_TIMEOUT`, dropped DB connection) aborts the *entire* outer transaction — nothing commits, including the idempotency row — so a retry with the same key is a genuinely fresh attempt. |
| REQ-027 | Idempotency keys expire (`expires_at`, default 24h) and are cleaned up by a scheduled job. |
| REQ-028 | Every state-changing endpoint (`/transfers`, `/reverse`, `/approve`, `/reject`) requires an `Idempotency-Key` header, not just `/transfers`. |

## 3. Kafka / Outbox

| ID | Requirement |
|---|---|
| REQ-040 | The outbox insert (`TransactionPosted` + one `AccountBalanceChanged` per account touched) happens in the same DB transaction as the ledger write. |
| REQ-041 | The relay never holds a DB transaction open across a Kafka network call — claim (short tx) → publish (no tx) → mark result (short tx). |
| REQ-042 | Claiming uses `SELECT ... FOR UPDATE SKIP LOCKED` so multiple relay instances don't double-claim. |
| REQ-043 | `outbox_events.status` is one of `PENDING, PUBLISHING, PUBLISHED, FAILED`; `lease_until` governs both claim-expiry and retry backoff. |
| REQ-044 | `AccountBalanceChanged` carries `account_version` (from `accounts.version`), enabling a consumer to detect and reject/buffer out-of-order delivery — ordering is enforced by version check, not assumed from Kafka partitioning alone. |
| REQ-045 | Every event carries a versioned envelope: `eventId, eventType, eventVersion, occurredAt`. |
| REQ-046 | Consumers deduplicate via a `processed_events(consumer_name, event_id)` inbox table; the dedupe insert and the business side effect commit in the same transaction. |
| REQ-047 | Notification consumers calling an external provider do not claim the same atomic guarantee as DB-only consumers — either accept at-least-once delivery for that channel or use the provider's own idempotency-key support. |
| REQ-048 | Consumers retry with exponential backoff, then route to a dead-letter topic after N attempts — no unbounded retry blocking a partition. |

## 4. Velocity Control

| ID | Requirement |
|---|---|
| REQ-060 | The velocity check computes a **monetary sum** within the time window, not an event count (`ZCARD` is explicitly disallowed for this purpose). |
| REQ-061 | The check-and-record sequence executes atomically via a Redis Lua script. |
| REQ-062 | On Redis unavailability, the check **fails closed** (rejects the transfer). |
| REQ-063 | v1 implements post-transaction monitoring (P0). Pre-authorization reserve/release is P1, explicitly not required for v1. |

## 5. Reconciliation

| ID | Requirement |
|---|---|
| REQ-080 | A scheduled job compares `accounts.cached_balance` against `SUM(ledger_entries)` per account. |
| REQ-081 | Any mismatch creates an incident record and alerts — it is never silently auto-corrected. |
| REQ-082 | Remediation follows a defined workflow: detect → flag → alert → investigate → correct (explicit action) → record (audit log). |
| REQ-083 | An account with confirmed drift transitions to `status = 'SUSPENDED'` rather than continuing to authorize transfers against known-bad data. |

## 6. Security & Authorization

| ID | Requirement |
|---|---|
| REQ-100 | Every account-mutating endpoint checks: authenticated principal, ownership/authorization on the source account, both accounts `ACTIVE` — before reaching the Transaction Coordinator. |
| REQ-101 | Reversal requires: original transaction `POSTED`, caller has `REVERSAL_APPROVER` role, caller `<> transaction.initiated_by`. Structurally enforced by `ux_one_reversal_per_transaction` (one reversal per original, ever). |
| REQ-102 | Transfers above a configured threshold require a second, distinct approver via `transaction_approvals` (`approved_by <> requested_by`, enforced by `CHECK`). |
| REQ-103 | Every state-changing action is written to an append-only `audit_log(actor_id, action, resource_type, resource_id, request_id, result, reason, metadata, created_at)`. |
| REQ-104 | Rate limiting is scoped to authenticated principal + IP, not an undefined "API key." |

## 7. Non-Functional Requirements

| ID | Requirement |
|---|---|
| REQ-120 | Structured JSON logs carry `correlation_id`, `transaction_id`, `idempotency_key` from Stage 1 onward. |
| REQ-121 | Outbox health is tracked as `pending_outbox_events`, `publishing_outbox_events`, `failed_outbox_events`, `oldest_pending_event_age` — not a single `published_at IS NULL` count. |
| REQ-122 | Benchmarks report hardware, dataset size, concurrency level, connection pool configuration, warm-up period excluded, and repetition count — never a bare throughput number. |
| REQ-123 | Layering is enforced: `Controller → ApplicationService → DomainService → Repository`. No controller method contains raw SQL. |

Traceability: `IMPLEMENTATION_PLAN.md` maps each `REQ-*` to a stage; `TRACKER.md` tracks per-requirement completion state.
