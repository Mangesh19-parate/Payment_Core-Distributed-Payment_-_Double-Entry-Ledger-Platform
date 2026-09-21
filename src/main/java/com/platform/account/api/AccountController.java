package com.platform.account.api;

import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.api.TransferResponse;
import com.platform.transfer.domain.LedgerEntry;
import com.platform.transfer.domain.TransferResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountApplicationService accountApplicationService;

    public AccountController(AccountApplicationService accountApplicationService) {
        this.accountApplicationService = accountApplicationService;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account created = accountApplicationService.createAccount(request.ownerId(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/fund")
    public ResponseEntity<TransferResponse> fundAccount(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @PathVariable("id") UUID accountId,
            @Valid @RequestBody FundAccountRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key header is required");
        }

        TransferResult result = accountApplicationService.fundAccount(
                request.principalId(),
                idempotencyKey.trim(),
                accountId,
                request.amount()
        );

        return switch (result) {
            case TransferResult.Posted p -> ResponseEntity.status(HttpStatus.CREATED).body(new TransferResponse(
                    p.transactionId(),
                    com.platform.transfer.domain.TransactionStatus.POSTED,
                    p.amount().amount(),
                    p.amount().currency(),
                    null,
                    p.postedAt()
            ));
            case TransferResult.BusinessFailure f -> throw new BusinessException(f.errorCode(), f.reason());
            case TransferResult.IdempotentReplay r -> ResponseEntity.status(
                    r.status() == com.platform.transfer.domain.TransactionStatus.POSTED ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY
            ).body(new TransferResponse(
                    r.transactionId(),
                    r.status(),
                    r.amount().amount(),
                    r.amount().currency(),
                    r.failureReason(),
                    Instant.now()
            ));
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccount(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(accountApplicationService.getAccount(id));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<AccountApplicationService.AccountBalanceSummary> getBalance(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(accountApplicationService.getBalanceSummary(id));
    }

    @GetMapping("/{id}/ledger")
    public ResponseEntity<List<LedgerEntry>> getLedger(
            @PathVariable("id") UUID id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(accountApplicationService.getAccountLedger(id, limit, offset));
    }
}
