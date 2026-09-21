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
    private final TransferDomainService transferDomainService;
    private final TransactionTemplate transactionTemplate;

    public TransferApplicationService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerRepository ledgerRepository,
            TransferDomainService transferDomainService,
            PlatformTransactionManager transactionManager
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
        this.transferDomainService = transferDomainService;
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

    public Optional<Transaction> getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId);
    }
}
