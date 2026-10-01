package com.platform.velocity;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class VelocityIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private TransferApplicationService transferApplicationService;

    @Autowired
    private AccountApplicationService accountApplicationService;

    private UUID userA;
    private UUID userB;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        userA = createTestUser();
        userB = createTestUser();
        accountA = accountApplicationService.createAccount(userA, "INR");
        accountB = accountApplicationService.createAccount(userB, "INR");
        accountApplicationService.fundAccount(userA, "fund-" + UUID.randomUUID(), accountA.id(), 1_000_000L);
    }

    @Test
    @DisplayName("Idempotent replay does not double-accumulate against Redis velocity limit")
    void idempotentReplay_doesNotExceedVelocityLimit() {
        String key = "idem-vel-" + UUID.randomUUID();
        long amount = 50_000L; // 500 INR

        // First transfer: success
        TransferResult result1 = transferApplicationService.transfer(
                userA, key, accountA.id(), accountB.id(), amount, "INR"
        );
        assertTrue(result1 instanceof TransferResult.Posted);

        // Immediate replay with same idempotency key
        TransferResult result2 = transferApplicationService.transfer(
                userA, key, accountA.id(), accountB.id(), amount, "INR"
        );
        assertTrue(result2 instanceof TransferResult.IdempotentReplay);
        assertEquals(((TransferResult.Posted) result1).transactionId(),
                ((TransferResult.IdempotentReplay) result2).transactionId());
    }
}
