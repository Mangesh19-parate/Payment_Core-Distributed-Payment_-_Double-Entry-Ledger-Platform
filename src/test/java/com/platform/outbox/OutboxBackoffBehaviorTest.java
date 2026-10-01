package com.platform.outbox;

import com.platform.BaseIntegrationTest;
import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.domain.OutboxEventStatus;
import com.platform.outbox.persistence.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class OutboxBackoffBehaviorTest extends BaseIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("REQ-043: Failed outbox events in PENDING status with future lease_until are NOT claimed until backoff expires")
    void failedEventWithBackoff_isNotClaimedPrematurely() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(aggregateId, "TestEvent", "{\"test\":true}");
        outboxRepository.saveAll(List.of(event));

        // 1. Claim the event
        List<OutboxEvent> claimed = outboxRepository.claimPendingEvents(10, Duration.ofSeconds(10));
        assertFalse(claimed.isEmpty());
        long eventId = claimed.get(0).id();

        // 2. Mark failed with 10s backoff -> status becomes PENDING, lease_until is now + 10s
        outboxRepository.markFailed(eventId, "Simulated transient Kafka failure", 1, 5, Duration.ofSeconds(10));

        // 3. Immediate poll: MUST NOT be claimed because lease_until is in the future
        List<OutboxEvent> prematureClaim = outboxRepository.claimPendingEvents(10, Duration.ofSeconds(10));
        boolean claimedAgain = prematureClaim.stream().anyMatch(e -> e.id() == eventId);
        assertFalse(claimedAgain, "Event was claimed before backoff expired!");

        // 4. Update lease_until to the past in DB to simulate backoff expiration
        testJdbcTemplate.update("UPDATE outbox_events SET lease_until = now() - INTERVAL '1 second' WHERE id = ?", eventId);

        // 5. Poll again: MUST be claimed now
        List<OutboxEvent> postBackoffClaim = outboxRepository.claimPendingEvents(10, Duration.ofSeconds(10));
        boolean claimedAfterBackoff = postBackoffClaim.stream().anyMatch(e -> e.id() == eventId);
        assertTrue(claimedAfterBackoff, "Event should be claimed after backoff expired");
    }
}
