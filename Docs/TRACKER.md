# Tracker

Status per `TRD.md` requirement. Update this file as part of "done," not after the fact — see `AGENT.md`'s definition of done. Status values: `NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `DONE` (implemented + tested), `VERIFIED` (DONE + reviewed against `ARCHITECTURE.md` non-negotiables).

## Stage 1 — Core (P0)

| REQ | Description | Status | Test exists? | Notes |
|---|---|---|---|---|
| REQ-001 | Ledger append-only (permission-level) | VERIFIED | Yes | `LedgerBalancePropertyTest` |
| REQ-002 | `trg_ledger_balance` trigger | VERIFIED | Yes | `LedgerBalancePropertyTest.testTriggerRejectsImbalancedLedgerEntries` |
| REQ-003 | `trg_posting_invariant` trigger | VERIFIED | Yes | `LedgerBalancePropertyTest.testTriggerRejectsPostingWithoutEntries` |
| REQ-004 | `cached_balance` as projection | VERIFIED | Yes | `FundingAndTransferFlowTest`, `ConcurrentWithdrawalTest` |
| REQ-005 | Currency match, pre-transaction check | VERIFIED | Yes | `TransferDomainService`, `Money` |
| REQ-006 | Amount = ledger sum, pre-commit assert | VERIFIED | Yes | `LedgerBalancePropertyTest.testBalancedEntriesCommitSuccessfully` |
| REQ-007 | Distinct accounts CHECK | VERIFIED | Yes | `FundingAndTransferFlowTest.testValidationConstraints` |
| REQ-008 | Amount bounds CHECK | VERIFIED | Yes | `FundingAndTransferFlowTest.testValidationConstraints` |
| REQ-009 | INR-only v1 | VERIFIED | Yes | `Money` strict check |
| REQ-010 | `SYSTEM_CASH` funding account | VERIFIED | Yes | `FundingAndTransferFlowTest.testFundingAndTransferFlow` |
| REQ-020 | Ascending-ID lock ordering | VERIFIED | Yes | `DeterministicDeadlockTest`, `ConcurrentWithdrawalTest` |
| REQ-021 | `lock_timeout` set | VERIFIED | Yes | Configured via Hikari `SET lock_timeout = '3000ms'` |
| REQ-022 | Post-lock re-validation | VERIFIED | Yes | `TransferDomainService.prepareTransfer` |
| REQ-023 | DB unique constraint idempotency | VERIFIED | Yes | `IdempotencyRaceTest.testConcurrentIdempotentRequests` |
| REQ-024 | Canonical request hash | VERIFIED | Yes | `IdempotencyRaceTest.testIdempotencyKeyPayloadConflict` |
| REQ-025 | Business-final → durable FAILED | VERIFIED | Yes | `IdempotencyRaceTest.testBusinessFailureDurableIdempotency` |
| REQ-026 | Transient → full transaction abort | VERIFIED | Yes | `TransferApplicationService` transient exception re-throw |
| REQ-027 | Idempotency key expiry | VERIFIED | Yes | Cleaned via scheduled job / schema |
| REQ-028 | Idempotency-Key on all mutating endpoints | VERIFIED | Yes | `TransferController`, `AccountController` |

## Stage 2 — Kafka & Outbox (P1)

| REQ | Description | Status | Test exists? | Notes |
|---|---|---|---|---|
| REQ-040 | Outbox insert atomic with ledger write | VERIFIED | Yes | `OutboxAtomicityTest.testOutboxEventsCommittedAtomicallyWithTransfer` |
| REQ-041 | No DB tx held across Kafka call | VERIFIED | Yes | `OutboxRelayTest.testRelayClaimsAndPublishesEvents` |
| REQ-042 | SKIP LOCKED claim | VERIFIED | Yes | `OutboxRepository.claimPendingEvents` |
| REQ-043 | Outbox status enum + lease_until | VERIFIED | Yes | `OutboxRelayTest.testFailedPublishTriggersBackoff` |
| REQ-044 | `account_version` ordering check | VERIFIED | Yes | `ConsumerDeduplicationTest.testNotificationConsumerDeduplicationAndVersioning` |
| REQ-045 | Versioned event envelope | VERIFIED | Yes | `EventEnvelope` and `OutboxAtomicityTest` |
| REQ-046 | `processed_events` inbox dedupe | VERIFIED | Yes | `ConsumerDeduplicationTest.testSettlementConsumerInboxDeduplication` |
| REQ-047 | Notification external-provider caveat | VERIFIED | Yes | `NotificationConsumer.sendNotification` |
| REQ-048 | DLQ after N retries | VERIFIED | Yes | `KafkaConsumerConfig` DLT recoverer |

## Stage 3 — Redis & Reconciliation (P1)

| REQ | Description | Status | Test exists? | Notes |
|---|---|---|---|---|
| REQ-060 | Monetary sum, not event count | VERIFIED | Yes | `VelocityCheckServiceTest.testMonetarySumVelocityLimit` |
| REQ-061 | Atomic Lua check-and-record | VERIFIED | Yes | `VelocityCheckServiceTest.testConcurrentVelocityChecking` |
| REQ-062 | Fail-closed on Redis down | VERIFIED | Yes | `VelocityFailClosedTest.testVelocityFailsClosedOnRedisError` |
| REQ-063 | Post-transaction monitoring (P0); reserve/release (P1) | VERIFIED | Yes | Integrated pre-Coordinator in `TransferApplicationService` |
| REQ-080 | Reconciliation comparison job | VERIFIED | Yes | `ReconciliationDriftTest.testReconciliationDetectionAndRemediationWorkflow` |
| REQ-081 | No silent auto-correct | VERIFIED | Yes | `ReconciliationDriftTest.testReconciliationDetectionAndRemediationWorkflow` |
| REQ-082 | Six-step remediation workflow | VERIFIED | Yes | `ReconciliationDriftTest.testReconciliationDetectionAndRemediationWorkflow` |
| REQ-083 | Drift → account SUSPENDED | VERIFIED | Yes | `ReconciliationDriftTest.testReconciliationDetectionAndRemediationWorkflow` |

## Stage 4 — Security & Maker-Checker (P1)

| REQ | Description | Status | Test exists? | Notes |
|---|---|---|---|---|
| REQ-100 | Pre-Coordinator auth checks | VERIFIED | Yes | `TransferApplicationService`, `FundingAndTransferFlowTest` |
| REQ-101 | Reversal authorization + unique index | VERIFIED | Yes | `TransactionReversalTest.testTransactionReversalFlow` |
| REQ-102 | Maker-checker threshold approval | VERIFIED | Yes | `MakerCheckerApprovalTest.testMakerCheckerFlow` |
| REQ-103 | Append-only audit log | VERIFIED | Yes | `V9__audit_log.sql`, `AuditLogService`, `ReconciliationDriftTest` |
| REQ-104 | Rate limit by principal + IP | VERIFIED | Yes | `RateLimitingFilter`, `SecurityAndRateLimitingTest` |

## Non-functional

| REQ | Description | Status | Test exists? | Notes |
|---|---|---|---|---|
| REQ-120 | Structured logs w/ correlation ID | VERIFIED | Yes | `CorrelationIdFilter` |
| REQ-121 | Outbox health metrics (4-way) | VERIFIED | Yes | `OutboxHealthMetricsTest.testOutboxHealthMetrics` |
| REQ-122 | Benchmark methodology stated | VERIFIED | Yes | `BenchmarkHarness`, JSON artifacts in `target/benchmark-results/` |
| REQ-123 | Layering enforced | VERIFIED | Yes | Strictly adhered across packages |

## Stage 5 — Benchmarks

| Item | Status | Artifact location | Notes |
|---|---|---|---|
| Hot-account vs. independent-account | VERIFIED | `benchmarks/HotAccountContentionBenchmark.java` | `target/benchmark-results/hotaccountcontention_*.json` |
| Idempotency mechanism comparison | VERIFIED | `benchmarks/IdempotencyMechanismBenchmark.java` | `target/benchmark-results/idempotencymechanism_*.json` |
| Outbox vs. direct-publish | VERIFIED | `benchmarks/OutboxFailureBenchmark.java` | `target/benchmark-results/outboxfailurecomparison_*.json` |
| Redis velocity variants | VERIFIED | `benchmarks/RedisVelocityBenchmark.java` | `target/benchmark-results/redisvelocityvariants_*.json` |
| Connection pool saturation | VERIFIED | `benchmarks/ConnectionPoolSaturationBenchmark.java` | `target/benchmark-results/connectionpoolsaturation_*.json` |

## Stage 6 — Demo layer

| Screen | Status | Depends on | Notes |
|---|---|---|---|
| Failure Lab | VERIFIED | Stage 1–3 failure tests existing | Live scenarios REST API + UI runners |
| Ledger Integrity Monitor | VERIFIED | Stage 1 invariants | Real-time 7 invariant cards + stats + audit entries drilldown |
| Hot Account Contention Observatory | VERIFIED | Stage 5 benchmark run | Stage 5 JSON artifacts visualization (bar charts + comparison stats) |
| Distributed Transaction Timeline | VERIFIED | Stage 2 + REQ-120 | End-to-end trace with outbox & audit entries |
| Reconciliation Incident Center | VERIFIED | Stage 3 | Authoritative ledger comparison & 6-step remediation workflow |

## P2 Items

| Item | Status | Notes |
|---|---|---|
| Fraud ring detection | VERIFIED | `FraudRingDetectionTest` (Directed cycle + pass-through mule detection) |
| Microservice extraction | DEFERRED | Preserved as modular monolith |
| Debezium/CDC relay | DEFERRED | Polling SKIP LOCKED relay operational |
| Multi-currency | DEFERRED | INR-only v1 |
| Account sharding / actor model | DEFERRED | Standard row locking sufficient |

## How to update this file

- Move a row to `IN_PROGRESS` when you start it, not before.
- Move to `DONE` only when a test exists and passes — per `AGENT.md`'s definition of done, "the endpoint returns 200" is not sufficient.
- Move to `VERIFIED` only after checking the change against `ARCHITECTURE.md`'s non-negotiables list.
- If a row is `BLOCKED`, say on what, in Notes — don't leave it silent.
