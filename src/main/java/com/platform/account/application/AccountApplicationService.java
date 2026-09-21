package com.platform.account.application;

import com.platform.account.domain.Account;
import com.platform.account.domain.AccountStatus;
import com.platform.account.persistence.AccountRepository;
import com.platform.common.Money;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.LedgerEntry;
import com.platform.transfer.domain.TransferResult;
import com.platform.transfer.persistence.LedgerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountApplicationService {

    public static final UUID SYSTEM_CASH_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final TransferApplicationService transferApplicationService;

    public AccountApplicationService(
            AccountRepository accountRepository,
            LedgerRepository ledgerRepository,
            TransferApplicationService transferApplicationService
    ) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.transferApplicationService = transferApplicationService;
    }

    public Account createAccount(UUID ownerId, String currency) {
        if (!Money.DEFAULT_CURRENCY.equalsIgnoreCase(currency)) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "Unsupported currency: " + currency);
        }
        UUID id = UUID.randomUUID();
        Account account = new Account(
                id,
                ownerId,
                currency.toUpperCase(),
                0L,
                0L,
                AccountStatus.ACTIVE,
                false,
                Instant.now()
        );
        accountRepository.insertAccount(account);
        return account;
    }

    /**
     * Funds an account by executing a transfer with SYSTEM_CASH as the counterparty (REQ-010).
     */
    public TransferResult fundAccount(UUID principalId, String idempotencyKey, UUID accountId, long amountPaise) {
        Account targetAccount = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Target account not found"));

        return transferApplicationService.transfer(
                principalId,
                idempotencyKey,
                SYSTEM_CASH_ACCOUNT_ID,
                targetAccount.id(),
                amountPaise,
                targetAccount.currency()
        );
    }

    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    }

    public record AccountBalanceSummary(
            UUID accountId,
            String currency,
            long cachedBalance,
            long ledgerDerivedBalance,
            long delta
    ) {}

    public AccountBalanceSummary getBalanceSummary(UUID accountId) {
        Account account = getAccount(accountId);
        long ledgerBalance = ledgerRepository.computeBalanceFromLedger(accountId);
        long delta = account.cachedBalance() - ledgerBalance;
        return new AccountBalanceSummary(
                account.id(),
                account.currency(),
                account.cachedBalance(),
                ledgerBalance,
                delta
        );
    }

    public List<LedgerEntry> getAccountLedger(UUID accountId, int limit, int offset) {
        // Ensure account exists
        getAccount(accountId);
        return ledgerRepository.findByAccountId(accountId, limit, offset);
    }
}
