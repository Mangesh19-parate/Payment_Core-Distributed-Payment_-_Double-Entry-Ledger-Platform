# Project Structure

Package-per-module, layered within each module, matching `ARCHITECTURE.md`'s ownership table exactly — if a file doesn't fit this structure, that's a signal to re-check which module actually owns it before inventing a new location.

```
payment-ledger-platform/
├── AGENT.md                           # entry point for AI agents, reading order, non-negotiables
├── MEMORY.md                          # persistent decision log, design history, caught bugs
├── PROJECT_STRUCTURE.md               # directory layout, module layering, ownership rules
├── Docs/                              # project documentation suite
│   ├── PRD.md                         # product requirements, problem statement, P0/P1/P2 scope
│   ├── TRD.md                         # technical requirements (REQ-001 through REQ-123)
│   ├── ARCHITECTURE.md                # system architecture, ownership boundaries, stop-and-ask triggers
│   ├── BACKEND_SCHEMA.md              # authoritative DDL, invariant triggers, state machines
│   ├── APP_FLOW.md                    # sequence diagrams for transfers, idempotency, maker-checker
│   ├── IMPLEMENTATION_PLAN.md         # stage roadmap, dependency sequencing, exit criteria
│   ├── TRACKER.md                     # requirement-by-requirement implementation tracker
│   └── UI_UX_DESIGN.md                # Stage 6 demo observatory screens
│
├── src/main/java/com/platform/
│   ├── transfer/                      # OWNS: transactions, ledger_entries writes, cached_balance writes
│   │   ├── api/
│   │   │   └── TransferController.java
│   │   ├── application/
│   │   │   └── TransferApplicationService.java   # idempotency orchestration, outbox write call
│   │   ├── domain/
│   │   │   ├── TransferDomainService.java        # locking, invariants, entry construction
│   │   │   └── TransferResult.java               # sealed interface: Posted / InsufficientFunds / ...
│   │   └── persistence/
│   │       ├── TransactionRepository.java
│   │       └── LedgerRepository.java             # native SQL for FOR UPDATE / ON CONFLICT lives here
│   │
│   ├── account/                       # OWNS: accounts (except cached_balance mutation, which is transfer's)
│   │   ├── api/AccountController.java
│   │   ├── application/AccountApplicationService.java   # includes /fund via transfer module
│   │   ├── domain/AccountDomainService.java
│   │   └── persistence/AccountRepository.java
│   │
│   ├── approval/                      # OWNS: transaction_approvals
│   │   ├── api/ApprovalController.java            # /approve, /reject
│   │   ├── application/ApprovalApplicationService.java
│   │   ├── domain/ApprovalDomainService.java       # approved_by <> requested_by, maker-checker rules
│   │   └── persistence/ApprovalRepository.java
│   │
│   ├── outbox/                        # OWNS: outbox_events status transitions
│   │   ├── OutboxRepository.java                  # written to by transfer's TX, never by other modules
│   │   ├── OutboxRelay.java                        # claim → publish → mark, three short operations
│   │   └── events/                                 # TransactionPosted, AccountBalanceChanged DTOs + envelope
│   │
│   ├── consumers/                     # OWNS: processed_events (per consumer_name)
│   │   ├── settlement/SettlementConsumer.java
│   │   └── notification/NotificationConsumer.java  # external-provider idempotency caveat lives here
│   │
│   ├── velocity/                      # Redis-backed, no DB tables of its own
│   │   ├── VelocityCheckService.java
│   │   └── scripts/velocity_check.lua
│   │
│   ├── reconciliation/                # OWNS: reconciliation incident records
│   │   ├── ReconciliationJob.java                  # scheduled
│   │   └── IncidentRepository.java
│   │
│   ├── audit/                         # OWNS: audit_log — called by other modules, owns nothing else
│   │   └── AuditLogService.java
│   │
│   ├── security/                      # cross-cutting: JWT auth, authorization checks
│   │   ├── AuthenticationFilter.java
│   │   └── AuthorizationService.java               # the AUTHORIZE(...) rules from spec §6
│   │
│   └── common/                        # shared, dependency-free: error model, ID generation, money type
│       ├── error/ErrorCode.java                    # the explicit enum from spec §13
│       ├── Money.java                              # wraps BIGINT minor units, never a float
│       └── IdempotencyKey.java
│
├── src/main/resources/
│   └── db/migration/                  # Flyway — one migration per logical change, see BACKEND_SCHEMA.md
│       ├── V1__users.sql
│       ├── V2__accounts.sql
│       ├── V3__transactions.sql
│       ├── V4__ledger_entries.sql
│       ├── V5__ledger_invariant_triggers.sql
│       ├── V6__transaction_approvals.sql
│       ├── V7__outbox_events.sql
│       ├── V8__processed_events.sql
│       └── V9__audit_log.sql
│
├── src/test/java/com/platform/
│   ├── transfer/
│   │   ├── ConcurrentWithdrawalTest.java           # spec §11
│   │   ├── DeterministicDeadlockTest.java          # CyclicBarrier-based
│   │   ├── IdempotencyRaceTest.java
│   │   └── LedgerBalancePropertyTest.java          # jqwik
│   ├── outbox/
│   │   └── OutboxAtomicityTest.java                # crash-injected
│   ├── reconciliation/
│   │   └── ReconciliationDriftTest.java
│   └── integration/                                # Testcontainers: real Postgres/Redis/Kafka
│       └── EndToEndTransferIT.java
│
├── benchmarks/                        # spec §11's benchmark module — separate from src/test
│   ├── HotAccountContentionBenchmark.java
│   ├── IdempotencyMechanismBenchmark.java
│   └── OutboxFailureBenchmark.java
│
└── demo-ui/                           # Stage 6 only — see UI_UX_DESIGN.md. Built last.
    ├── failure-lab/
    ├── ledger-integrity-monitor/
    ├── hot-account-observatory/
    ├── transaction-timeline/
    └── reconciliation-incident-center/
```

## Rules this structure encodes

- If you're about to add a file that writes to `ledger_entries`, `transactions.status`, or `accounts.cached_balance` **outside `transfer/`**, stop — per `ARCHITECTURE.md`, only `transfer` owns those writes.
- Native/lock-sensitive SQL lives in the `persistence` package of the module that owns the table, never inline in a controller or application service.
- `common/` has no dependency on any other module — if something in `common` starts importing from `transfer` or `account`, it's not actually common, move it.
- `demo-ui/` has zero backend logic of its own — every number it renders comes from an existing endpoint or metric. If a demo screen needs a new endpoint that does more than read, that's a sign it belongs in a real module first.
