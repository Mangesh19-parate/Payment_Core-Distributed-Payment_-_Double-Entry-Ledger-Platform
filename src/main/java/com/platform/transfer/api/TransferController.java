package com.platform.transfer.api;

import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.Transaction;
import com.platform.transfer.domain.TransferResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferApplicationService transferApplicationService;

    public TransferController(TransferApplicationService transferApplicationService) {
        this.transferApplicationService = transferApplicationService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key header is required");
        }

        TransferResult result = transferApplicationService.transfer(
                request.principalId(),
                idempotencyKey.trim(),
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency().toUpperCase().trim()
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
            case TransferResult.AwaitingApproval a -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new TransferResponse(
                    a.transactionId(),
                    com.platform.transfer.domain.TransactionStatus.AWAITING_APPROVAL,
                    a.amount().amount(),
                    a.amount().currency(),
                    "Transfer requires maker-checker approval",
                    a.createdAt()
            ));
            case TransferResult.BusinessFailure f -> {
                throw new BusinessException(f.errorCode(), f.reason());
            }
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

    public record ReversalRequest(UUID callerId, String reason) {}

    @PostMapping("/transactions/{id}/reverse")
    public ResponseEntity<TransferResponse> reverseTransaction(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody ReversalRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key header is required");
        }

        TransferResult result = transferApplicationService.reverseTransaction(
                request.callerId(),
                idempotencyKey.trim(),
                id,
                request.reason() != null ? request.reason() : "Transaction reversed"
        );

        if (result instanceof TransferResult.Posted p) {
            return ResponseEntity.status(HttpStatus.CREATED).body(new TransferResponse(
                    p.transactionId(),
                    com.platform.transfer.domain.TransactionStatus.POSTED,
                    p.amount().amount(),
                    p.amount().currency(),
                    null,
                    p.postedAt()
            ));
        } else if (result instanceof TransferResult.IdempotentReplay r) {
            return ResponseEntity.ok(new TransferResponse(
                    r.transactionId(),
                    r.status(),
                    r.amount().amount(),
                    r.amount().currency(),
                    r.failureReason(),
                    Instant.now()
            ));
        }

        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unexpected reversal outcome");
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<Transaction> getTransaction(@PathVariable("id") UUID id) {
        return transferApplicationService.getTransaction(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));
    }
}
