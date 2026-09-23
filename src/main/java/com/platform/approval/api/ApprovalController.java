package com.platform.approval.api;

import com.platform.approval.application.ApprovalApplicationService;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.api.TransferResponse;
import com.platform.transfer.domain.TransactionStatus;
import com.platform.transfer.domain.TransferResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
public class ApprovalController {

    private final ApprovalApplicationService approvalApplicationService;

    public ApprovalController(ApprovalApplicationService approvalApplicationService) {
        this.approvalApplicationService = approvalApplicationService;
    }

    public record ApprovalRequest(UUID principalId, String reason) {}

    @PostMapping("/{id}/approve")
    public ResponseEntity<TransferResponse> approve(
            @PathVariable("id") UUID transactionId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody ApprovalRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key header is required");
        }

        TransferResult result = approvalApplicationService.approve(
                request.principalId(),
                idempotencyKey.trim(),
                transactionId
        );

        if (result instanceof TransferResult.Posted p) {
            return ResponseEntity.ok(new TransferResponse(
                    p.transactionId(),
                    TransactionStatus.POSTED,
                    p.amount().amount(),
                    p.amount().currency(),
                    null,
                    p.postedAt()
            ));
        }

        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unexpected approval outcome");
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable("id") UUID transactionId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody ApprovalRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key header is required");
        }

        approvalApplicationService.reject(
                request.principalId(),
                idempotencyKey.trim(),
                transactionId,
                request.reason() != null ? request.reason() : "Rejected by checker"
        );

        return ResponseEntity.ok(Map.of(
                "transactionId", transactionId,
                "status", "REJECTED",
                "timestamp", Instant.now().toString()
        ));
    }
}
