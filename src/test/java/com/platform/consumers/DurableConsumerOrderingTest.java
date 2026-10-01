package com.platform.consumers;

import com.platform.BaseIntegrationTest;
import com.platform.consumers.persistence.ProcessedEventsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class DurableConsumerOrderingTest extends BaseIntegrationTest {

    @Autowired
    private ProcessedEventsRepository processedEventsRepository;

    @Test
    @DisplayName("REQ-044: Durable account event versioning detects and rejects stale/out-of-order versions across restarts")
    void durableEventOrdering_rejectsStaleVersion() {
        String consumerName = "notification_consumer";
        UUID accountId = UUID.randomUUID();

        // 1. Initial version 1 succeeds
        boolean v1 = processedEventsRepository.tryUpdateAccountVersion(consumerName, accountId, 1L);
        assertTrue(v1);

        // 2. Version 3 arrives (advances version)
        boolean v3 = processedEventsRepository.tryUpdateAccountVersion(consumerName, accountId, 3L);
        assertTrue(v3);

        // 3. Version 2 arrives out-of-order: must be rejected
        boolean v2 = processedEventsRepository.tryUpdateAccountVersion(consumerName, accountId, 2L);
        assertFalse(v2, "Stale version 2 should be rejected when version 3 is already applied");

        // 4. Duplicate version 3 arrives: must be rejected
        boolean v3Dup = processedEventsRepository.tryUpdateAccountVersion(consumerName, accountId, 3L);
        assertFalse(v3Dup, "Duplicate version 3 should be rejected");

        // 5. Version 4 arrives: succeeds
        boolean v4 = processedEventsRepository.tryUpdateAccountVersion(consumerName, accountId, 4L);
        assertTrue(v4);
    }
}
