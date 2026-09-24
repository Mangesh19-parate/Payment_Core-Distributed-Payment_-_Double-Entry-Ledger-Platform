package com.platform.transfer.application;

import com.platform.account.domain.Account;
import com.platform.account.persistence.AccountRepository;
import com.platform.common.IdempotencyKey;
import com.platform.common.Money;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.domain.*;
import com.platform.transfer.persistence.LedgerRepository;
import com.platform.transfer.persistence.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.events.AccountBalanceChangedEvent;
import com.platform.outbox.events.EventEnvelope;
import com.platform.outbox.events.TransactionPostedEvent;
import com.platform.outbox.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class TransferApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TransferApplicationService.class);
    private static final long MAX_AMOUNT_PAISE = 1_000_000_000L; // ₹1 crore

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final OutboxRepository outboxRepository;
    private final TransferDomainService transferDomainService;
    private final com.platform.velocity.VelocityCheckService velocityCheckService;
    private final com.platform.approval.persistence.ApprovalRepository approvalRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final long makerCheckerThresholdPaise;

    public TransferApplicationService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerRepository ledgerRepository,
            OutboxRepository outboxRepository,
            TransferDomainService transferDomainService,
            com.platform.velocity.VelocityCheckService velocityCheckService,
            com.platform.approval.persistence.ApprovalRepository approvalRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Value("${app.maker-checker.threshold-paise:10000000}") long makerCheckerThresholdPaise
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
        this.outboxRepository = outboxRepository;
        this.transferDomainService = transferDomainService;
        this.velocityCheckService = velocityCheckService;
        this.approvalRepository = approvalRepository;
        this.objectMapper = objectMapper;
        this.makerCheckerThresholdPaise = makerCheckerThresholdPaise;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TransferResult transfer(
            UUID principalId,
            String idempotencyKey,
            UUID sourceAccountId,
            UUID destinationAccountId,
            long amountPaise,
            String currency
    ) {
        // Fast-fail validations pre-transaction
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER, "Source and destination accounts must be distinct");
        }
        if (amountPaise <= 0 || amountPaise > MAX_AMOUNT_PAISE) {
            throw new BusinessException(ErrorCode.AMOUNT_OUT_OF_BOUNDS, "Transfer amount must be between 1 and " + MAX_AMOUNT_PAISE + " paise");
        }

        Money amount = Money.ofPaise(amountPaise, currency);
        String requestHash = IdempotencyKey.computeRequestHash(sourceAccountId, destinationAccountId, amountPaise, currency);
        UUID transactionId = UUID.randomUUID();

        // REQ-060, REQ-061, REQ-062, REQ-063: Pre-transaction velocity control check before Transaction Coordinator (exempt SYSTEM_CASH)
        if (velocityCheckService != null && !com.platform.account.application.AccountApplicationService.SYSTEM_CASH_ACCOUNT_ID.equals(sourceAccountId)) {
            velocityCheckService.checkAndRecord(sourceAccountId, amountPaise, idempotencyKey);
        }

        return transactionTemplate.execute(status -> {
            // 1. Check for existing transaction under this idempotency key
            Optional<Transaction> existingOpt = transactionRepository.findByPrincipalAndIdempotencyKey(principalId, idempotencyKey);
            if (existingOpt.isPresent()) {
                Transaction existingTxn = existingOpt.get();
                if (!existingTxn.requestHash().equals(requestHash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key reused with different request payload");
                }
                log.info("Idempotent replay for transaction {}", existingTxn.id());
                return new TransferResult.IdempotentReplay(
                        existingTxn.id(),
                        existingTxn.status(),
                        Money.ofPaise(existingTxn.amount(), existingTxn.currency()),
                        existingTxn.failureReason()
                );
            }

            // Maker-Checker threshold check (REQ-102) - only applies to regular customer/user transfers, not SYSTEM_CASH funding
            if (amountPaise > makerCheckerThresholdPaise && !com.platform.account.application.AccountApplicationService.SYSTEM_CASH_ACCOUNT_ID.equals(sourceAccountId)) {
                Optional<UUID> awaitingTxnId = transactionRepository.tryInsertAwaitingApprovalTransaction(
                        transactionId,
                        principalId,
                        idempotencyKey,
                        requestHash,
                        sourceAccountId,
                        destinationAccountId,
                        amountPaise,
                        currency
                );
                UUID effId = awaitingTxnId.orElseGet(() ->
                        transactionRepository.findByPrincipalAndIdempotencyKey(principalId, idempotencyKey).orElseThrow().id()
                );
                approvalRepository.createApproval(effId, principalId);
                log.info("Transfer {} requires maker-checker approval (amount={} > threshold={})",
                        effId, amountPaise, makerCheckerThresholdPaise);
                return new TransferResult.AwaitingApproval(effId, sourceAccountId, destinationAccountId, amount, Instant.now());
            }

            // 2. Acquire account row locks in ascending UUID order FIRST (REQ-020)
            List<Account> lockedAccounts = accountRepository.findAccountsForUpdate(sourceAccountId, destinationAccountId);
            if (lockedAccounts.size() < 2) {
                return new TransferResult.BusinessFailure(ErrorCode.ACCOUNT_NOT_FOUND, "One or both accounts do not exist");
            }

            // 3. Attempt atomic initial insert (REQ-023)
            Optional<UUID> insertedTxnId = transactionRepository.tryInsertInitialTransaction(
                    transactionId,
                    principalId,
                    idempotencyKey,
                    requestHash,
                    sourceAccountId,
                    destinationAccountId,
                    amountPaise,
                    currency,
                    TransactionType.TRANSFER
            );

            if (insertedTxnId.isEmpty()) {
                Transaction concurrentTxn = transactionRepository.findByPrincipalAndIdempotencyKey(principalId, idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Transaction with key exists but cannot be retrieved"));
                if (!concurrentTxn.requestHash().equals(requestHash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key reused with different request payload");
                }
                return new TransferResult.IdempotentReplay(
                        concurrentTxn.id(),
                        concurrentTxn.status(),
                        Money.ofPaise(concurrentTxn.amount(), concurrentTxn.currency()),
                        concurrentTxn.failureReason()
                );
            }

            UUID effectiveTxnId = insertedTxnId.get();

            // 4. Establish SAVEPOINT for the business path (REQ-025, REQ-026)
            Object savepoint = status.createSavepoint();

            try {
                Account src = lockedAccounts.stream().filter(a -> a.id().equals(sourceAccountId)).findFirst().orElseThrow();
                Account dst = lockedAccounts.stream().filter(a -> a.id().equals(destinationAccountId)).findFirst().orElseThrow();

                Instant now = Instant.now();

                // 5. Post-lock Domain Validation (REQ-022)
                TransferDomainService.DomainValidationResult validation = transferDomainService.prepareTransfer(
                        effectiveTxnId, src, dst, amount, now
                );

                if (!validation.isSuccess()) {
                    // Business-final failure: rollback to savepoint & durably record status=FAILED (REQ-025)
                    status.rollbackToSavepoint(savepoint);
                    transactionRepository.updateStatus(effectiveTxnId, TransactionStatus.FAILED, validation.failureReason(), null);
                    log.warn("Transfer {} failed business validation: {}", effectiveTxnId, validation.failureReason());
                    return new TransferResult.BusinessFailure(validation.errorCode(), validation.failureReason());
                }

                // 6. Post transfer: write ledger entries and update cached balances
                TransferDomainService.ExecutionPlan plan = validation.plan();

                ledgerRepository.insertEntries(plan.entries());
                accountRepository.updateBalanceAndVersion(src.id(), plan.updatedSourceAccount().cachedBalance(), src.version());
                accountRepository.updateBalanceAndVersion(dst.id(), plan.updatedDestinationAccount().cachedBalance(), dst.version());

                transactionRepository.updateStatus(effectiveTxnId, TransactionStatus.POSTED, null, now);

                // 7. Atomic Outbox Event Persistence (REQ-040, REQ-044, REQ-045)
                List<OutboxEvent> outboxEvents = createOutboxEvents(
                        effectiveTxnId, principalId, src, dst, plan, amountPaise, currency, now
                );
                outboxRepository.saveAll(outboxEvents);

                status.releaseSavepoint(savepoint);

                log.info("Transfer {} successfully POSTED (source={}, dest={}, amount={})",
                        effectiveTxnId, sourceAccountId, destinationAccountId, amountPaise);

                return new TransferResult.Posted(
                        effectiveTxnId,
                        sourceAccountId,
                        destinationAccountId,
                        amount,
                        now
                );

            } catch (TransientDataAccessException ex) {
                // Transient failure: let exception propagate to trigger full transaction rollback (REQ-026)
                log.error("Transient error during transfer {}: aborting entire transaction", effectiveTxnId, ex);
                throw ex;
            } catch (Exception ex) {
                if (ex instanceof BusinessException) {
                    status.rollbackToSavepoint(savepoint);
                    transactionRepository.updateStatus(effectiveTxnId, TransactionStatus.FAILED, ex.getMessage(), null);
                    throw ex;
                }
                log.error("Unexpected error during transfer {}: aborting transaction", effectiveTxnId, ex);
                throw ex;
            }
        });
    }

    /**
     * Executes approved transfer posting reusing core locking and double-entry ledger logic (REQ-102).
     */
    public TransferResult executeApprovedPosting(UUID transactionId, UUID approverId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));

        if (txn.status() != TransactionStatus.AWAITING_APPROVAL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Transaction is not awaiting approval: status=" + txn.status());
        }

        Money amount = Money.ofPaise(txn.amount(), txn.currency());

        return transactionTemplate.execute(status -> {
            List<Account> lockedAccounts = accountRepository.findAccountsForUpdate(txn.sourceAccountId(), txn.destinationAccountId());
            if (lockedAccounts.size() < 2) {
                throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Accounts not found during approval");
            }

            Account src = lockedAccounts.stream().filter(a -> a.id().equals(txn.sourceAccountId())).findFirst().orElseThrow();
            Account dst = lockedAccounts.stream().filter(a -> a.id().equals(txn.destinationAccountId())).findFirst().orElseThrow();
            Instant now = Instant.now();

            TransferDomainService.DomainValidationResult validation = transferDomainService.prepareTransfer(
                    txn.id(), src, dst, amount, now
            );

            if (!validation.isSuccess()) {
                transactionRepository.updateStatus(txn.id(), TransactionStatus.FAILED, validation.failureReason(), null);
                approvalRepository.updateStatus(txn.id(), approverId, "REJECTED", validation.failureReason());
                throw new BusinessException(validation.errorCode(), validation.failureReason());
            }

            TransferDomainService.ExecutionPlan plan = validation.plan();
            ledgerRepository.insertEntries(plan.entries());
            accountRepository.updateBalanceAndVersion(src.id(), plan.updatedSourceAccount().cachedBalance(), src.version());
            accountRepository.updateBalanceAndVersion(dst.id(), plan.updatedDestinationAccount().cachedBalance(), dst.version());

            transactionRepository.updateStatus(txn.id(), TransactionStatus.POSTED, null, now);
            approvalRepository.updateStatus(txn.id(), approverId, "APPROVED", "Approved by checker");

            List<OutboxEvent> outboxEvents = createOutboxEvents(
                    txn.id(), txn.principalId(), src, dst, plan, txn.amount(), txn.currency(), now
            );
            outboxRepository.saveAll(outboxEvents);

            log.info("Transaction {} successfully approved and POSTED by {}", txn.id(), approverId);
            return new TransferResult.Posted(txn.id(), txn.sourceAccountId(), txn.destinationAccountId(), amount, now);
        });
    }

    /**
     * Rejects an awaiting-approval transaction (REQ-102).
     */
    public void executeRejection(UUID transactionId, UUID rejecterId, String reason) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));

        if (txn.status() != TransactionStatus.AWAITING_APPROVAL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Transaction is not awaiting approval: status=" + txn.status());
        }

        transactionTemplate.executeWithoutResult(status -> {
            transactionRepository.updateStatus(txn.id(), TransactionStatus.REJECTED, reason, null);
            approvalRepository.updateStatus(txn.id(), rejecterId, "REJECTED", reason);
            log.info("Transaction {} REJECTED by {}", txn.id(), rejecterId);
        });
    }

    /**
     * Executes reversal of a POSTED transaction (REQ-101).
     * Reversal is posted with reversed debit/credit and reference_txn_id pointing to original.
     * Enforced structurally by partial unique index ux_one_reversal_per_transaction.
     */
    public TransferResult reverseTransaction(UUID callerId, String idempotencyKey, UUID originalTransactionId, String reason) {
        Transaction originalTxn = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND, "Original transaction not found"));

        if (originalTxn.status() != TransactionStatus.POSTED) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Only POSTED transactions can be reversed: status=" + originalTxn.status());
        }

        // REQ-101: Caller cannot be the initiator of the original transaction
        if (callerId.equals(originalTxn.principalId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Initiator of original transaction cannot authorize reversal");
        }

        UUID reversalTxnId = UUID.randomUUID();
        UUID revSourceId = originalTxn.destinationAccountId();
        UUID revDestId = originalTxn.sourceAccountId();
        long amountPaise = originalTxn.amount();
        String currency = originalTxn.currency();
        Money amount = Money.ofPaise(amountPaise, currency);
        String requestHash = IdempotencyKey.computeRequestHash(revSourceId, revDestId, amountPaise, currency);

        return transactionTemplate.execute(status -> {
            Optional<Transaction> existingOpt = transactionRepository.findByPrincipalAndIdempotencyKey(callerId, idempotencyKey);
            if (existingOpt.isPresent()) {
                Transaction existingTxn = existingOpt.get();
                return new TransferResult.IdempotentReplay(
                        existingTxn.id(), existingTxn.status(), Money.ofPaise(existingTxn.amount(), existingTxn.currency()), existingTxn.failureReason()
                );
            }

            Optional<UUID> insertedId = transactionRepository.tryInsertReversalTransaction(
                    reversalTxnId, callerId, idempotencyKey, requestHash, revSourceId, revDestId, amountPaise, currency, originalTransactionId
            );

            if (insertedId.isEmpty()) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Reversal idempotency conflict");
            }

            List<Account> lockedAccounts = accountRepository.findAccountsForUpdate(revSourceId, revDestId);
            Account src = lockedAccounts.stream().filter(a -> a.id().equals(revSourceId)).findFirst().orElseThrow();
            Account dst = lockedAccounts.stream().filter(a -> a.id().equals(revDestId)).findFirst().orElseThrow();
            Instant now = Instant.now();

            TransferDomainService.DomainValidationResult validation = transferDomainService.prepareTransfer(
                    reversalTxnId, src, dst, amount, now
            );

            if (!validation.isSuccess()) {
                transactionRepository.updateStatus(reversalTxnId, TransactionStatus.FAILED, validation.failureReason(), null);
                throw new BusinessException(validation.errorCode(), validation.failureReason());
            }

            TransferDomainService.ExecutionPlan plan = validation.plan();
            ledgerRepository.insertEntries(plan.entries());
            accountRepository.updateBalanceAndVersion(src.id(), plan.updatedSourceAccount().cachedBalance(), src.version());
            accountRepository.updateBalanceAndVersion(dst.id(), plan.updatedDestinationAccount().cachedBalance(), dst.version());

            // Update reversal transaction to POSTED, original to REVERSED
            transactionRepository.updateStatus(reversalTxnId, TransactionStatus.POSTED, null, now);
            transactionRepository.updateStatus(originalTransactionId, TransactionStatus.REVERSED, "Reversed by txn " + reversalTxnId + ": " + reason, now);

            List<OutboxEvent> outboxEvents = createOutboxEvents(
                    reversalTxnId, callerId, src, dst, plan, amountPaise, currency, now
            );
            outboxRepository.saveAll(outboxEvents);

            log.info("Transaction {} successfully reversed via reversal transaction {}", originalTransactionId, reversalTxnId);
            return new TransferResult.Posted(reversalTxnId, revSourceId, revDestId, amount, now);
        });
    }

    public Optional<Transaction> getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId);
    }

    private List<OutboxEvent> createOutboxEvents(
            UUID transactionId,
            UUID principalId,
            Account src,
            Account dst,
            TransferDomainService.ExecutionPlan plan,
            long amountPaise,
            String currency,
            Instant now
    ) {
        try {
            // 1. TransactionPosted event (REQ-040, REQ-045)
            TransactionPostedEvent txnPayload = new TransactionPostedEvent(
                    transactionId, principalId, src.id(), dst.id(), amountPaise, currency, "TRANSFER", now
            );
            EventEnvelope<TransactionPostedEvent> txnEnvelope = EventEnvelope.of(
                    "TransactionPosted", 1, transactionId, txnPayload
            );
            OutboxEvent txnOutboxEvent = OutboxEvent.pending(
                    transactionId, "TransactionPosted", objectMapper.writeValueAsString(txnEnvelope)
            );

            // 2. AccountBalanceChanged for source account with new version (REQ-044)
            AccountBalanceChangedEvent srcPayload = new AccountBalanceChangedEvent(
                    src.id(), transactionId, -amountPaise, plan.updatedSourceAccount().cachedBalance(),
                    src.version() + 1, currency, now
            );
            EventEnvelope<AccountBalanceChangedEvent> srcEnvelope = EventEnvelope.of(
                    "AccountBalanceChanged", 1, src.id(), srcPayload
            );
            OutboxEvent srcOutboxEvent = OutboxEvent.pending(
                    src.id(), "AccountBalanceChanged", objectMapper.writeValueAsString(srcEnvelope)
            );

            // 3. AccountBalanceChanged for destination account with new version (REQ-044)
            AccountBalanceChangedEvent dstPayload = new AccountBalanceChangedEvent(
                    dst.id(), transactionId, amountPaise, plan.updatedDestinationAccount().cachedBalance(),
                    dst.version() + 1, currency, now
            );
            EventEnvelope<AccountBalanceChangedEvent> dstEnvelope = EventEnvelope.of(
                    "AccountBalanceChanged", 1, dst.id(), dstPayload
            );
            OutboxEvent dstOutboxEvent = OutboxEvent.pending(
                    dst.id(), "AccountBalanceChanged", objectMapper.writeValueAsString(dstEnvelope)
            );

            return List.of(txnOutboxEvent, srcOutboxEvent, dstOutboxEvent);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox event JSON", ex);
        }
    }
}
