# Memory

Persistent context across sessions. This file exists so decisions don't get relitigated and known gaps don't get "rediscovered" and re-debated. Append to it; don't rewrite history in it. Format for new entries at the bottom of each section: `[YYYY-MM-DD] decision/gap — one line — pointer to detail`.

## Decisions already made — do not re-propose without reading why

- **Ledger is source of truth; `cached_balance` is a protected projection, not "untrusted."** Precise framing matters: it's authoritative enough to gate a transfer *because* it's protected by invariant triggers + reconciliation, not despite being untrusted. See [Docs/BACKEND_SCHEMA.md](file:///d:/Python/Data%20sets%20by%20campusx/PaymentCore/Docs/BACKEND_SCHEMA.md).
- **Pessimistic locking (`SELECT ... FOR UPDATE`, ascending ID order) over optimistic locking.** Chosen for hot-account contention behavior, not because it's simpler. The throughput ceiling this creates is accepted and measured (Stage 5), not treated as a bug.
- **SAVEPOINT-based idempotency, not a two-phase/multi-commit workflow — for the fast path.** A multi-commit workflow (durable `PENDING`, polling) is real machinery this design specifically avoided for ordinary transfers, and specifically does use for maker-checker (`AWAITING_APPROVAL`), because that case genuinely needs a durable, hours-long wait. Don't conflate the two again.
- **Transient failures (lock timeout) and business-final failures (insufficient funds) are handled differently in the idempotency path** — this was a real caught bug (see "Bugs caught during design" below). Never let a retry-after-lock-timeout be told "no" forever.
- **Two-tier Kafka event model** (`TransactionPosted` whole-transaction + `AccountBalanceChanged` per-account, both written directly into the outbox in the same transaction, versioned via `accounts.version`) — not a derived/downstream-consumer approach. The derived approach was tried in an earlier design round and had a real ordering gap; don't reintroduce it.
- **Modular monolith first; microservices are P2.** A microservice split reintroduces the exact distributed-transaction problem (no single spanning DB transaction) this architecture exists to avoid. Splitting needs an explicit, measured justification, not a default "at scale you'd need microservices" assumption.
- **v1 is INR-only, by explicit scoping decision, not by oversight.** `currency` stays in the schema to demonstrate the concept is first-class; a real minor-unit-exponent table is the named extension point, not silently assumed to work today.
- **`SYSTEM_CASH` is the only legal funding counterparty**, exempt from the sufficiency check, permitted a negative balance by design (represents external capital). Without it, there is no way to seed an account without violating the ledger invariant — this was a real, late-discovered gap.
- **Sequential ascending UUID lock acquisition before mutating SQL**: Acquiring row locks via `findAccountsForUpdate` before inserting transaction rows prevents foreign key `KEY SHARE` lock inversions that caused deadlock in PostgreSQL during opposing concurrent transfers.
- **Foreign keys in transaction and ledger tables are DEFERRABLE INITIALLY DEFERRED**: Prevents intermediate statement-level lock acquisition on referenced accounts during multi-step transactions.
- **`SYSTEM_CASH` is exempt from customer velocity and maker-checker threshold gates**: Funding platform capital is an administrative platform action, not a customer transaction; subjecting it to velocity checks or employee maker-checker would block system account initialization.

## Bugs caught during the design process (told precisely, worth keeping as real examples)

- **Postgres Foreign Key `KEY SHARE` Lock Inversion in Opposing Transfers**: When inserting transactions with foreign keys `(source_account_id, destination_account_id REFERENCES accounts)` before acquiring ascending-order row locks, PostgreSQL took `KEY SHARE` locks in the order the foreign keys were checked ($A \to B$ vs $B \to A$), deadlocking concurrent opposing transfers. Fixed by acquiring `SELECT ... FOR UPDATE` locks in strictly ascending UUID order before the insert and setting foreign keys to `DEFERRABLE INITIALLY DEFERRED`.

- **Redis velocity check originally used `ZCARD`** (event count) against a monetary (₹) threshold — counted events, not rupees, so four small transfers and one large one registered identically. Fixed with a Lua-scripted atomic sum. If re-implementing velocity checks anywhere else in this system, check for this exact class of mistake (count vs. sum) again.
- **Idempotency design had an internal contradiction**: described one atomic transaction, then separately described a client polling a durably-committed `PENDING` state — those can't both be true under Postgres MVCC (uncommitted rows are invisible to other transactions). Fixed with the `SAVEPOINT` design. If anyone proposes adding polling/`202 Processing` back to the ordinary transfer path, that's very likely this same bug returning — check against `TRD REQ-025`/`REQ-026` before accepting it.
- **Amount-cap arithmetic was wrong twice** in two separate design rounds (paise-to-rupee conversion). If touching `TRD REQ-008`'s value, recompute the conversion explicitly and have a second pass check it — this specific class of error has already happened twice.
- **Kafka per-account ordering was assumed, not guaranteed**, in an intermediate design (deriving `AccountBalanceChanged` from a downstream consumer reading `TransactionPosted`, keyed by `account_id`) — two transactions touching the same account could still arrive out of order at that consumer, since `TransactionPosted` itself isn't account-partitioned. Fixed by writing `AccountBalanceChanged` directly into the outbox with `account_version`, checked by the consumer.
- **Account status was checked only before lock acquisition**, not after — a real TOCTOU race (suspend an account between the pre-check and the lock). Fixed by requiring a post-lock re-validation as the authoritative check (`TRD REQ-022`).
- **`transaction_approvals` initially had no `UNIQUE(transaction_id)`** — nothing stopped multiple approval rows per transaction. Fixed; v1 assumes one approval level (a quorum/multi-level scheme is out of scope).
- **`outbox_events` prose referenced a `claimed_at` column that didn't exist in the schema.** Fixed by adding `lease_until`, which does double duty (claim expiry + retry backoff) rather than adding two separate columns that could drift out of sync.

## Known gaps / explicitly deferred (P2) — don't "discover" these as if new

- Fraud ring detection (graph connected-components/cycle detection) — real, but explicitly P2, a supporting module built only after P0/P1 backend work is solid.
- Microservice extraction — see "Decisions already made" above.
- Debezium/CDC outbox relay — named as the production upgrade over polling, not built here.
- Multi-currency FX support — v1 is INR-only by decision, not oversight.
- Account sharding / actor-style per-account processing — only justified if Stage 5's benchmark actually shows the hot-account ceiling matters at a volume this project cares about; don't build speculatively.

## Glossary (terms that have caused confusion across design rounds — use precisely)

- **`CREATED`** — a transaction's initial status for the ordinary fast path. Not `PENDING` — that word was deliberately retired for transactions specifically because it was being overloaded with two different meanings (in-flight vs. awaiting human approval).
- **`AWAITING_APPROVAL`** — the durable, externally-visible state for maker-checker-gated transfers. This is the one place a real multi-commit workflow is correct.
- **Business-final failure** — a failure where the business question has been answered (no) and retrying with the same inputs won't change that. Durably recorded, idempotency-cacheable.
- **Transient failure** — a failure where nothing was actually decided (infrastructure hiccup). Must not be durably recorded against an idempotency key; the whole transaction aborts so a retry is genuinely fresh.
- **"Effectively-once"** — the correct term for at-least-once delivery + idempotent consumers. Not "exactly-once" — that claims something the outbox pattern alone doesn't provide. Never used loosely in this codebase's docs or comments.
- **`cached_balance`** — a transactionally-maintained projection, protected by invariants + reconciliation. Not "untrusted" (imprecise) and not "the truth" (wrong) — see the Decisions section above for the exact framing to use.

## Open questions not yet resolved

(Append here as they come up during implementation — this section should not stay empty for long once coding starts.)
