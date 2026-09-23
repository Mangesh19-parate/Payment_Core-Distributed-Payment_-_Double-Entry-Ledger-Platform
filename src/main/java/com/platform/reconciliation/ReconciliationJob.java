package com.platform.reconciliation;

import com.platform.reconciliation.application.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${app.reconciliation.cron:0 */15 * * * *}")
    public void runReconciliation() {
        log.info("Starting scheduled reconciliation job (REQ-080)...");
        try {
            var incidents = reconciliationService.reconcileAllAccounts();
            if (!incidents.isEmpty()) {
                log.warn("Reconciliation job detected {} new drift incidents", incidents.size());
            } else {
                log.info("Reconciliation job completed with zero drift detected across all accounts");
            }
        } catch (Exception ex) {
            log.error("Reconciliation job encountered an unexpected error", ex);
        }
    }
}
