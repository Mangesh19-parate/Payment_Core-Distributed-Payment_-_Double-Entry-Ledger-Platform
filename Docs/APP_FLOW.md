# App Flow

This is a backend-first system — "app flow" here means request/data sequences, not screen-to-screen navigation. The five demo screens (if built — see `UI_UX_DESIGN.md`) are thin views over these flows, not separate flows of their own.

## 1. Standard transfer (the core flow everything else depends on)

```
Client                Payment API           DB (single tx)              Outbox/Kafka
  │                        │                       │                        │
  ├─ POST /transfers ─────▶│                       │                        │
  │  Idempotency-Key: K    │                       │                        │
  │                        ├─ pre-check: principal ▶│  (fast reject path)    │
  │                        │  auth, account ACTIVE  │                        │
  │                        ├─ INSERT transactions ─▶│                        │
  │                        │  status=CREATED,       │                        │
  │                        │  ON CONFLICT(principal,│                        │
  │                        │  key) DO NOTHING       │                        │
  │                        │                        │                        │
  │              no row returned (someone else      │                        │
  │              holds this key) ──────────────────▶│  SELECT existing row  │
  │              row returned: this caller owns it ─│  proceed              │
  │                        │                        │                        │
  │                        ├─ SAVEPOINT ───────────▶│                        │
  │                        ├─ SELECT accounts        │                        │
  │                        │  ORDER BY id FOR UPDATE▶│  (both accounts       │
  │                        │                        │   locked, ascending)  │
  │                        ├─ re-check status/       │                        │
  │                        │  currency/balance       │  (post-lock, real)    │
  │                        │                        │                        │
  │            [insufficient funds / business-final] │                        │
  │                        ├─ ROLLBACK TO SAVEPOINT ▶│                        │
  │                        ├─ UPDATE status=FAILED ─▶│                        │
  │                        ├─ COMMIT ───────────────▶│                        │
  │◀─ 422 INSUFFICIENT_FUNDS                         │                        │
  │                        │                        │                        │
  │            [lock timeout / transient]            │                        │
  │                        ├─ let exception abort    │                        │
  │                        │  ENTIRE transaction ───▶│  nothing commits      │
  │◀─ 5xx ACCOUNT_LOCK_TIMEOUT (retryable, same key) │                        │
  │                        │                        │                        │
  │            [success]                             │                        │
  │                        ├─ INSERT ledger_entries ▶│  (2 rows, sum to 0)    │
  │                        ├─ UPDATE cached_balance ▶│  (both accounts)       │
  │                        ├─ UPDATE status=POSTED ─▶│                        │
  │                        ├─ INSERT outbox_events ─▶│  TransactionPosted +   │
  │                        │                        │  AccountBalanceChanged │
  │                        │                        │  x2 (versioned)        │
  │                        ├─ COMMIT ───────────────▶│                        │
  │◀─ 201 POSTED           │                        │                        │
  │                        │                        ├─ relay claims rows ───▶│
  │                        │                        │  (SKIP LOCKED)         │
  │                        │                        │◀─ publish (no tx held)─┤
  │                        │                        │  mark PUBLISHED        │
```

## 2. Idempotent retry (client never received the response)

```
Client retries POST /transfers with the SAME Idempotency-Key
  → INSERT ... ON CONFLICT(principal_id, idempotency_key) DO NOTHING
  → no row returned (key already exists from the original attempt)
  → SELECT existing row by (principal_id, idempotency_key)
  → branch on status:
      POSTED  → return original 201 body, same transactionId
      FAILED  → return original 422/error body (business-final failures are cached)
      (no durable in-flight state reachable in normal operation — see TRD REQ-025/026)
  → NO new transaction is created, NO new ledger entries, NO new outbox event
```

## 3. Funding an account (getting money into the system)

```
POST /accounts/{id}/fund
  → internally: an ordinary TRANSFER-type transaction
    source = SYSTEM_CASH (is_system_account = true, exempt from sufficiency check)
    destination = the target account
  → goes through the EXACT SAME posting/locking/ledger path as flow #1
  → SYSTEM_CASH.cached_balance is allowed to go negative (represents external capital)
```

## 4. Maker-checker (large transfer / reversal above threshold)

```
Maker: POST /transfers (amount > threshold)
  → transaction created with status = AWAITING_APPROVAL (durable, externally visible —
    this is the one case in the system where a multi-request workflow is correct,
    because a human approval genuinely takes hours)
  → transaction_approvals row created (status = PENDING, requested_by = maker)

Checker: POST /transactions/{id}/approve   [Idempotency-Key required]
  → validates: approved_by <> requested_by (CHECK constraint, not just app logic)
  → transaction_approvals.status = APPROVED
  → transaction transitions AWAITING_APPROVAL → POSTED (runs the actual posting
    logic from flow #1 — lock, re-validate, post)

  OR

Checker: POST /transactions/{id}/reject   [Idempotency-Key required]
  → transaction_approvals.status = REJECTED
  → transaction transitions AWAITING_APPROVAL → REJECTED
```

## 5. Reversal

```
POST /transactions/{id}/reverse   [Idempotency-Key required]
  → AUTHORIZE: reference_transaction.status = POSTED
             AND caller has REVERSAL_APPROVER role
             AND caller <> transaction.initiated_by
  → ux_one_reversal_per_transaction (partial unique index) makes a second
    reversal structurally impossible, not just policed by the check above
  → a NEW transaction is created (status will end at POSTED), debit/credit
    reversed relative to the original, reference_txn_id = original.id
  → the ORIGINAL transaction transitions POSTED → REVERSED
  → the REVERSAL transaction itself ends at POSTED (it is not itself "reversed")
```

## 6. Reconciliation (background, not user-triggered)

```
Scheduled job:
  SELECT accounts.cached_balance vs SUM(ledger_entries) per account
  → mismatch found:
      1. DETECT   (the query above)
      2. FLAG     create incident record, do NOT auto-freeze by default
      3. ALERT    page/notify
      4. INVESTIGATE  human determines which number is wrong
      5. CORRECT  explicit, recorded action — never a silent background UPDATE
      6. RECORD   write the correction to audit_log with the incident reference
  → confirmed drift → account.status = SUSPENDED
```

## 7. Failure injection (what the Failure Lab demo, if built, actually triggers)

Each of these is a specific, already-required test from `spec §11` — the demo layer is a UI wrapper around these, not new logic:

- Duplicate-request race → flow #2 fired concurrently from two threads.
- Concurrent withdrawal → flow #1 fired N times against one account where `N × amount > balance`.
- Deadlock → two transfers, opposite account order, without lock ordering (constructed via `CyclicBarrier`, not timing).
- Kafka unavailable → flow #1 completes (DB commits), outbox stays `PENDING`, relay retries on Kafka recovery.
- Consumer crash after side effect, before offset commit → redelivery, `processed_events` dedupes, side effect not repeated.
- Outbox relay crash mid-claim → `lease_until` expires, row becomes claimable again automatically.
- Ledger corruption (manual `cached_balance` tamper in a test DB) → reconciliation flow #6 detects it.
- Redis unavailable → velocity check fails closed, transfer rejected.
