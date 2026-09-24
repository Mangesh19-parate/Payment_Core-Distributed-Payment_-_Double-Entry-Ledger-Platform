package com.platform.fraud;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.account.persistence.AccountRepository;
import com.platform.fraud.api.FraudController;
import com.platform.fraud.domain.AccountRiskProfile;
import com.platform.fraud.domain.FraudRingReport;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class FraudRingDetectionTest extends BaseIntegrationTest {

    @Autowired
    private FraudController fraudController;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountApplicationService accountApplicationService;

    @Autowired
    private TransferApplicationService transferApplicationService;

    private UUID user1Id;
    private UUID user2Id;

    @BeforeEach
    void setUp() {
        user1Id = createTestUser();
        user2Id = createTestUser();
    }

    private Account createAndFundAccount(UUID userId, long initialBalance) {
        Account account = accountApplicationService.createAccount(userId, "INR");
        if (initialBalance > 0) {
            accountApplicationService.fundAccount(userId, "SEED_FUND_" + UUID.randomUUID(), account.id(), initialBalance);
        }
        return accountRepository.findById(account.id()).orElseThrow();
    }

    @Test
    @DisplayName("P2: Fraud Ring Cycle & Pass-Through Mule Detection")
    void testCircularFraudRingAndMuleDetection() {
        // Setup 3-node ring: A -> B -> C -> A
        Account accA = createAndFundAccount(user1Id, 50000);
        Account accB = createAndFundAccount(user1Id, 10000);
        Account accC = createAndFundAccount(user2Id, 10000);

        // Execute circular flow: A -> B, B -> C, C -> A
        TransferResult r1 = transferApplicationService.transfer(
                user1Id, "RING_TX_1_" + UUID.randomUUID(), accA.id(), accB.id(), 20000, "INR"
        );
        assertThat(r1).isInstanceOf(TransferResult.Posted.class);

        TransferResult r2 = transferApplicationService.transfer(
                user1Id, "RING_TX_2_" + UUID.randomUUID(), accB.id(), accC.id(), 18000, "INR"
        );
        assertThat(r2).isInstanceOf(TransferResult.Posted.class);

        TransferResult r3 = transferApplicationService.transfer(
                user2Id, "RING_TX_3_" + UUID.randomUUID(), accC.id(), accA.id(), 15000, "INR"
        );
        assertThat(r3).isInstanceOf(TransferResult.Posted.class);

        // Setup Rapid Pass-Through Mule Account: Source -> Mule -> Dest
        Account muleSource = createAndFundAccount(user1Id, 100000);
        Account muleAcc = createAndFundAccount(user2Id, 0);
        Account muleDest = createAndFundAccount(user2Id, 0);

        TransferResult mIn = transferApplicationService.transfer(
                user1Id, "MULE_IN_" + UUID.randomUUID(), muleSource.id(), muleAcc.id(), 40000, "INR"
        );
        assertThat(mIn).isInstanceOf(TransferResult.Posted.class);

        TransferResult mOut = transferApplicationService.transfer(
                user2Id, "MULE_OUT_" + UUID.randomUUID(), muleAcc.id(), muleDest.id(), 38000, "INR"
        );
        assertThat(mOut).isInstanceOf(TransferResult.Posted.class);

        // Verify Fraud Rings API Endpoint
        ResponseEntity<FraudRingReport> ringsResp = fraudController.getFraudRings(24);
        assertThat(ringsResp.getStatusCode().is2xxSuccessful()).isTrue();
        FraudRingReport report = ringsResp.getBody();
        assertThat(report).isNotNull();
        assertThat(report.detectedCircularRings()).isNotEmpty();
        assertThat(report.detectedCircularRings().get(0).hopCount()).isEqualTo(3);
        assertThat(report.suspectedMules()).isNotEmpty();
        assertThat(report.suspectedMules().stream().anyMatch(m -> m.accountId().equals(muleAcc.id()))).isTrue();

        // Verify Account Risk Endpoint for Ring Participant
        ResponseEntity<AccountRiskProfile> riskA = fraudController.getAccountRisk(accA.id());
        assertThat(riskA.getStatusCode().is2xxSuccessful()).isTrue();
        AccountRiskProfile profileA = riskA.getBody();
        assertThat(profileA).isNotNull();
        assertThat(profileA.inCircularRing()).isTrue();
        assertThat(profileA.riskScore()).isGreaterThanOrEqualTo(50);

        // Verify Account Risk Endpoint for Mule Account
        ResponseEntity<AccountRiskProfile> riskMule = fraudController.getAccountRisk(muleAcc.id());
        assertThat(riskMule.getStatusCode().is2xxSuccessful()).isTrue();
        AccountRiskProfile profileMule = riskMule.getBody();
        assertThat(profileMule).isNotNull();
        assertThat(profileMule.suspectedMule()).isTrue();
        assertThat(profileMule.riskScore()).isGreaterThanOrEqualTo(40);
    }
}
