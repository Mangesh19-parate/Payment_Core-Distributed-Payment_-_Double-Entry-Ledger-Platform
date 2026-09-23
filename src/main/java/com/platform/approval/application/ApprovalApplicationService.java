package com.platform.approval.application;

import com.platform.approval.domain.TransactionApproval;
import com.platform.approval.persistence.ApprovalRepository;
import com.platform.audit.AuditLogService;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ApprovalApplicationService {

    private final ApprovalRepository approvalRepository;
    private final TransferApplicationService transferApplicationService;
    private final AuditLogService auditLogService;

    public ApprovalApplicationService(
            ApprovalRepository approvalRepository,
            TransferApplicationService transferApplicationService,
            AuditLogService auditLogService
    ) {
        this.approvalRepository = approvalRepository;
        this.transferApplicationService = transferApplicationService;
        this.auditLogService = auditLogService;
    }

    public TransferResult approve(UUID checkerId, String idempotencyKey, UUID transactionId) {
        TransactionApproval approval = approvalRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "Approval request not found"));

        // REQ-102: Checker must be distinct from requester
        if (checkerId.equals(approval.requestedBy())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Maker cannot approve their own transfer");
        }

        TransferResult result = transferApplicationService.executeApprovedPosting(transactionId, checkerId);

        auditLogService.logAction(
                checkerId,
                "APPROVE_TRANSACTION",
                "TRANSACTION",
                transactionId,
                idempotencyKey,
                "SUCCESS",
                "Approved by checker",
                "{}"
        );

        return result;
    }

    public void reject(UUID checkerId, String idempotencyKey, UUID transactionId, String reason) {
        TransactionApproval approval = approvalRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "Approval request not found"));

        if (checkerId.equals(approval.requestedBy())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Maker cannot reject their own transfer");
        }

        transferApplicationService.executeRejection(transactionId, checkerId, reason);

        auditLogService.logAction(
                checkerId,
                "REJECT_TRANSACTION",
                "TRANSACTION",
                transactionId,
                idempotencyKey,
                "SUCCESS",
                reason,
                "{}"
        );
    }
}
