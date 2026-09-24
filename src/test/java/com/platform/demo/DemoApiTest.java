package com.platform.demo;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.demo.api.DemoController;
import com.platform.demo.application.DemoService;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DemoApiTest extends BaseIntegrationTest {

    @Autowired
    private DemoController demoController;

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("Stage 6: Demo API Endpoints Integration Test")
    void testDemoApiEndpoints() {
        UUID owner = createTestUser();
        Account accA = accountService.createAccount(owner, "INR");
        Account accB = accountService.createAccount(owner, "INR");
        accountService.fundAccount(owner, UUID.randomUUID().toString(), accA.id(), 100_000L);

        TransferResult transfer = transferService.transfer(owner, UUID.randomUUID().toString(), accA.id(), accB.id(), 20_000L, "INR");
        assertThat(transfer).isInstanceOf(TransferResult.Posted.class);
        UUID txnId = ((TransferResult.Posted) transfer).transactionId();

        // 1. Invariants check
        ResponseEntity<DemoService.InvariantReport> invResponse = demoController.getInvariants();
        assertThat(invResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(invResponse.getBody()).isNotNull();
        assertThat(invResponse.getBody().invariants()).hasSize(7);
        assertThat(invResponse.getBody().invariants().stream().allMatch(DemoService.InvariantCheck::passed)).isTrue();

        // 2. Transaction Ledger Drilldown
        ResponseEntity<Map<String, Object>> ledgerResponse = demoController.getTransactionLedger(txnId);
        assertThat(ledgerResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ledgerResponse.getBody()).isNotNull();
        assertThat(ledgerResponse.getBody().get("entries")).isInstanceOf(List.class);
        assertThat((List<?>) ledgerResponse.getBody().get("entries")).hasSize(2);

        // 3. Benchmarks list
        ResponseEntity<List<Map<String, Object>>> benchResponse = demoController.getBenchmarks();
        assertThat(benchResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // 4. Timeline
        ResponseEntity<DemoService.TransactionTimeline> timelineResponse = demoController.getTimeline(txnId);
        assertThat(timelineResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(timelineResponse.getBody()).isNotNull();
        assertThat(timelineResponse.getBody().steps()).isNotEmpty();

        // 5. Reconciliation Overview & Simulation
        ResponseEntity<Map<String, Object>> reconResponse = demoController.getReconciliation();
        assertThat(reconResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // 6. Failure Lab Scenarios
        for (String scenario : List.of("duplicate-race", "concurrent-withdrawal", "deadlock-prevention", "outbox-atomicity")) {
            ResponseEntity<DemoService.ScenarioResult> scenarioResp = demoController.runFailureScenario(scenario);
            assertThat(scenarioResp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(scenarioResp.getBody()).isNotNull();
            assertThat(scenarioResp.getBody().outcome()).isEqualTo("PASSED");
        }
    }
}
