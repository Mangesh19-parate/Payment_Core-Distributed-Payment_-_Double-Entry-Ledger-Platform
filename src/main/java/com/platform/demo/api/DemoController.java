package com.platform.demo.api;

import com.platform.demo.application.DemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/invariants")
    public ResponseEntity<DemoService.InvariantReport> getInvariants() {
        return ResponseEntity.ok(demoService.checkInvariants());
    }

    @GetMapping("/transactions/{id}/ledger")
    public ResponseEntity<Map<String, Object>> getTransactionLedger(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(demoService.getTransactionLedger(id));
    }

    @GetMapping("/benchmarks")
    public ResponseEntity<List<Map<String, Object>>> getBenchmarks() {
        return ResponseEntity.ok(demoService.getBenchmarkResults());
    }

    @GetMapping("/timeline/{transactionId}")
    public ResponseEntity<DemoService.TransactionTimeline> getTimeline(@PathVariable("transactionId") UUID transactionId) {
        return ResponseEntity.ok(demoService.getTransactionTimeline(transactionId));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<Map<String, Object>> getReconciliation() {
        return ResponseEntity.ok(demoService.getReconciliationOverview());
    }

    public record DriftRequest(UUID accountId, long driftPaise) {}

    @PostMapping("/reconciliation/simulate-drift")
    public ResponseEntity<Map<String, Object>> simulateDrift(@RequestBody DriftRequest request) {
        return ResponseEntity.ok(demoService.simulateBalanceDrift(request.accountId(), request.driftPaise()));
    }

    public record RemediateRequest(UUID incidentId, UUID adminId, Long customBalance, String notes) {}

    @PostMapping("/reconciliation/remediate")
    public ResponseEntity<Map<String, Object>> remediateIncident(@RequestBody RemediateRequest request) {
        return ResponseEntity.ok(demoService.remediateIncident(request.incidentId(), request.adminId(), request.customBalance(), request.notes()));
    }

    @PostMapping("/failure-lab/{scenarioId}")
    public ResponseEntity<DemoService.ScenarioResult> runFailureScenario(@PathVariable("scenarioId") String scenarioId) {
        return ResponseEntity.ok(demoService.runFailureScenario(scenarioId));
    }
}
