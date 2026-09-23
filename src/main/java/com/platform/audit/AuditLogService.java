package com.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void logAction(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String requestId,
            String result,
            String reason,
            String metadataJson
    ) {
        try {
            AuditLogEntry entry = AuditLogEntry.of(
                    actorId,
                    action,
                    resourceType,
                    resourceId,
                    requestId,
                    result,
                    reason,
                    metadataJson
            );
            auditLogRepository.insert(entry);
            log.info("Audit log recorded: actor={}, action={}, resource={}:{}, result={}",
                    actorId, action, resourceType, resourceId, result);
        } catch (Exception ex) {
            log.error("Failed to write to audit log for action: {} on resource: {}", action, resourceId, ex);
            // In a production system, failure to write audit log may trigger fatal error depending on compliance
        }
    }
}
