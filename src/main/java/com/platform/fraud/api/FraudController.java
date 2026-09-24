package com.platform.fraud.api;

import com.platform.fraud.domain.AccountRiskProfile;
import com.platform.fraud.domain.FraudRingDetectionService;
import com.platform.fraud.domain.FraudRingReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudRingDetectionService fraudRingDetectionService;

    public FraudController(FraudRingDetectionService fraudRingDetectionService) {
        this.fraudRingDetectionService = fraudRingDetectionService;
    }

    @GetMapping("/rings")
    public ResponseEntity<FraudRingReport> getFraudRings(
            @RequestParam(defaultValue = "168") int lookbackHours
    ) {
        FraudRingReport report = fraudRingDetectionService.analyzeNetwork(lookbackHours);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/accounts/{accountId}/risk")
    public ResponseEntity<AccountRiskProfile> getAccountRisk(
            @PathVariable UUID accountId
    ) {
        AccountRiskProfile profile = fraudRingDetectionService.evaluateAccountRisk(accountId);
        return ResponseEntity.ok(profile);
    }
}
