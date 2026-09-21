package com.platform.transfer.domain;

import com.platform.account.domain.Account;
import com.platform.common.Money;
import com.platform.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TransferDomainService {

    public record ExecutionPlan(
            Account updatedSourceAccount,
            Account updatedDestinationAccount,
            List<LedgerEntry> entries
    ) {}

    public record DomainValidationResult(
            boolean isSuccess,
            ErrorCode errorCode,
            String failureReason,
            ExecutionPlan plan
    ) {
        public static DomainValidationResult success(ExecutionPlan plan) {
            return new DomainValidationResult(true, null, null, plan);
        }

        public static DomainValidationResult failure(ErrorCode errorCode, String failureReason) {
            return new DomainValidationResult(false, errorCode, failureReason, null);
        }
    }

    /**
     * Executes post-lock domain validations and constructs balanced ledger entries.
     */
    public DomainValidationResult prepareTransfer(
            UUID transactionId,
            Account sourceAccount,
            Account destinationAccount,
            Money amount,
            Instant timestamp
    ) {
        // REQ-022: Authoritative post-lock check for ACTIVE status
        if (!sourceAccount.isActive()) {
            return DomainValidationResult.failure(
                    ErrorCode.ACCOUNT_SUSPENDED,
                    "Source account is " + sourceAccount.status()
            );
        }
        if (!destinationAccount.isActive()) {
            return DomainValidationResult.failure(
                    ErrorCode.ACCOUNT_SUSPENDED,
                    "Destination account is " + destinationAccount.status()
            );
        }

        // REQ-005 & REQ-009: Currency validation
        if (!sourceAccount.currency().equalsIgnoreCase(amount.currency())) {
            return DomainValidationResult.failure(
                    ErrorCode.CURRENCY_MISMATCH,
                    "Source account currency " + sourceAccount.currency() + " does not match transfer currency " + amount.currency()
            );
        }
        if (!destinationAccount.currency().equalsIgnoreCase(amount.currency())) {
            return DomainValidationResult.failure(
                    ErrorCode.CURRENCY_MISMATCH,
                    "Destination account currency " + destinationAccount.currency() + " does not match transfer currency " + amount.currency()
            );
        }

        // Balance sufficiency check (exempt if source is system funding account per REQ-010)
        if (!sourceAccount.isSystemAccount() && sourceAccount.cachedBalance() < amount.amount()) {
            return DomainValidationResult.failure(
                    ErrorCode.INSUFFICIENT_FUNDS,
                    String.format("Insufficient funds in source account: required %d, available %d",
                            amount.amount(), sourceAccount.cachedBalance())
            );
        }

        // Construct balanced double-entry ledger records (REQ-002, REQ-006)
        LedgerEntry debitEntry = new LedgerEntry(
                null,
                transactionId,
                sourceAccount.id(),
                EntryType.DEBIT,
                amount.amount(),
                amount.currency(),
                timestamp
        );

        LedgerEntry creditEntry = new LedgerEntry(
                null,
                transactionId,
                destinationAccount.id(),
                EntryType.CREDIT,
                amount.amount(),
                amount.currency(),
                timestamp
        );

        // Updated accounts with projected balances
        Account updatedSource = new Account(
                sourceAccount.id(),
                sourceAccount.ownerId(),
                sourceAccount.currency(),
                sourceAccount.cachedBalance() - amount.amount(),
                sourceAccount.version(),
                sourceAccount.status(),
                sourceAccount.isSystemAccount(),
                sourceAccount.createdAt()
        );

        Account updatedDest = new Account(
                destinationAccount.id(),
                destinationAccount.ownerId(),
                destinationAccount.currency(),
                destinationAccount.cachedBalance() + amount.amount(),
                destinationAccount.version(),
                destinationAccount.status(),
                destinationAccount.isSystemAccount(),
                destinationAccount.createdAt()
        );

        return DomainValidationResult.success(new ExecutionPlan(
                updatedSource,
                updatedDest,
                List.of(debitEntry, creditEntry)
        ));
    }
}
